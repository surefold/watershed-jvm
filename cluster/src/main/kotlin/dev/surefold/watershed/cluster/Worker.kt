package dev.surefold.watershed.cluster

import dev.surefold.Scope
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Behaviour
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.RemoteNode
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensureNotNull
import dev.surefold.fail.fold
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import dev.surefold.watershed.Gpu
import dev.surefold.watershed.Held
import dev.surefold.watershed.Patch
import dev.surefold.watershed.Plan
import dev.surefold.watershed.Refused
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * EXPERIMENTAL. Makes this node a worker: it runs one lot at a time, on its GPU if it has one and [gpu] is not
 * false, and otherwise on the CPU. Give the node the [WORKER] role when it joins the cluster. A worker that
 * shares its GPU with others is told how many bytes of its memory it may use, with [gpuBytes]. Asked for a GPU
 * with [gpu] true, a node that has none answers [NoGpu] and is no worker.
 *
 * Join the cluster with `ready = Ready.WhenTold` and call `admit()` after this, so that the coordinator does not
 * hear of the node before it can take a lot; a lot offered too early is only offered again a few seconds later.
 *
 * A lot it is asked to hold stays in its memory, every output of every scenario, until the process stops; a
 * [Refresh] brings such a lot up to date with a patch and writes its answers again.
 *
 * A plan is read once and kept, so only a worker's first lot of a job pays for loading it. [beforeRun] is called
 * with each lot's number just before it is run; tests use it to hold a worker up.
 *
 * It is two actors. The worker itself only answers: it says at once whether it takes what it is given, so it is
 * never held up. Its child, the bench, runs one lot per turn, and a turn may block for as long as the lot takes.
 * The plans, the patches and the lots held are the bench's own state, so nothing here is shared or locked.
 */
fun Scope<*>.worker(
    node: RemoteNode,
    gpu: Boolean? = null,
    gpuBytes: Long? = null,
    beforeRun: (Int) -> Unit = {},
): Either<NoGpu, Unit> = card(gpu).map { card ->
    val name = node.self.toString()
    val actor = spawn(
        "worker",
        behaviour<WorkerTurn, Desk>(Desk()) { ctx, desk, told ->
            // A child, so it is stopped with this actor, and its turn is interrupted when the scope closes.
            val bench = desk.bench ?: ctx.spawn("bench", bench(Rig(name, card, gpuBytes, beforeRun)))
            val seated = desk.copy(bench = bench)
            when (told) {
                is Finished -> {
                    // Free before the outcome is told, so that the next lot it brings is taken.
                    told.from.tell(told.outcome)
                    become(seated.copy(busy = false, held = seated.held + listOfNotNull(told.holds)))
                }

                is ToWorker -> {
                    val taking = when {
                        told is Refresh && told.job to told.lot !in desk.held -> Taking.NotHeld
                        desk.busy -> Taking.Busy
                        else -> Taking.Taken
                    }
                    told.from.tell(Answer(told.tag, told.lot, name, taking))
                    if (taking == Taking.Taken) bench.tell(Work(told, ctx.self))
                    become(seated.copy(busy = desk.busy || taking == Taking.Taken))
                }
            }
        },
    )
    node.expose<ToWorker>(actor, ToWorkerCodec)
}

// The GPU to run on, or none: asked for one outright, not finding it is a failure; left to choose, it is the CPU.
private fun card(gpu: Boolean?): Either<NoGpu, Gpu?> = when (gpu) {
    false -> null.right()
    true -> attempt<Refused, Gpu?> { Gpu.open() }.mapLeft { NoGpu(it.why) }
    null -> attempt<Refused, Gpu?> { Gpu.open() }.fold({ null }, { it }).right()
}

// What the worker's actor gives its bench to do, and what the bench tells it back.
private data class Work(val told: ToWorker, val back: ActorRef<WorkerTurn>)

private data class Finished(val outcome: FromWorker, val from: ActorRef<FromWorker>, val holds: Pair<String, Int>?) : WorkerTurn

// What the worker's actor knows: whether its bench is at work, and which lots, by job and number, the bench holds.
private data class Desk(
    val busy: Boolean = false,
    val held: Set<Pair<String, Int>> = emptySet(),
    val bench: ActorRef<Work>? = null,
)

// What a bench is given once and never changes.
private class Rig(val name: String, val card: Gpu?, val gpuBytes: Long?, val beforeRun: (Int) -> Unit) {
    val on: String = if (card == null) "cpu" else "gpu"
}

// What a bench has read and holds; each lot it runs answers the next of these.
private data class Bench(
    val plans: Map<String, Plan> = emptyMap(),
    val patches: Map<String, Patch> = emptyMap(),
    val held: Map<Pair<String, Int>, Held> = emptyMap(),
)

// How long a lot took to get ready and to run.
private data class Took(val loading: Duration, val running: Duration)

private fun Duration.seconds(): Double = toNanos() / 1e9

private fun now(): Instant = clock.get().now()

// Runs what it is given, one lot to a turn, and tells the worker how each went.
private fun bench(rig: Rig): Behaviour<Work, Bench, Nothing> = behaviour(Bench()) { _, bench, work ->
    val told = work.told
    val done = attempt<String, Pair<Bench, Took>> {
        when (told) {
            is Assign -> run(rig, bench, told)
            is Refresh -> refresh(rig, bench, told)
        }
    }
    val outcome = done.fold(
        { why -> Failed(told.tag, told.lot, rig.name, why) },
        { (_, took) -> Ran(told.tag, told.lot, rig.name, rig.on, took.loading.seconds(), took.running.seconds()) },
    )
    val holds = (told as? Assign)?.takeIf { it.hold && done.isRight() }?.let { it.job to it.lot }
    work.back.tell(Finished(outcome, told.from, holds))
    done.fold({ stay() }, { (next, _) -> become(next) })
}

// What the engine refuses is, here, why a lot failed, told to the coordinator.
context(_: Fail<String>)
private fun <A> engine(block: Fail<Refused>.() -> A): A = attempt(block).mapLeft { it.why }.bind()

context(_: Fail<String>)
private fun <A> Either<JobFailure.File, A>.read(): A = mapLeft { "${it.path}: ${it.why}" }.bind()

context(_: Fail<String>)
private fun run(rig: Rig, bench: Bench, lot: Assign): Pair<Bench, Took> {
    val started = now()
    val plan = bench.plans[lot.plan] ?: engine { Plan.load(Path.of(lot.plan)) }
    val inputs = readNumbers(Path.of(lot.inputs), lot.first * plan.inputs, lot.count.toLong() * plan.inputs).read()
    val loaded = now()
    rig.beforeRun(lot.lot)
    val running = now()
    val answers = engine {
        when {
            rig.card == null -> plan.runCpu(inputs)
            rig.gpuBytes == null -> rig.card.run(plan, inputs)
            else -> rig.card.run(plan, inputs, rig.gpuBytes)
        }
    }
    val ran = now()
    val columns = lot.columns.toIntArray()
    val holding = if (lot.hold) engine { Held.of(plan.inputs, inputs, answers) } else null
    val chosen = when {
        columns.isEmpty() -> answers
        holding != null -> engine { holding.values(columns) }
        else -> DoubleArray(lot.count * columns.size) { answers[it / columns.size * plan.outputs + columns[it % columns.size]] }
    }
    writeNumbers(Path.of(lot.answers), chosen).read()
    val next = bench.copy(
        plans = bench.plans + (lot.plan to plan),
        held = if (holding == null) bench.held else bench.held + ((lot.job to lot.lot) to holding),
    )
    return next to Took(Duration.between(started, loaded), Duration.between(running, ran))
}

context(_: Fail<String>)
private fun refresh(rig: Rig, bench: Bench, lot: Refresh): Pair<Bench, Took> {
    val holding = ensureNotNull(bench.held[lot.job to lot.lot]) { "lot ${lot.lot} of ${lot.job} is not held" }
    val started = now()
    val patch = bench.patches[lot.patch] ?: engine { Patch.load(Path.of(lot.patch)) }
    val loaded = now()
    rig.beforeRun(lot.lot)
    val running = now()
    engine { holding.refresh(patch, rig.card, rig.gpuBytes) }
    val ran = now()
    val columns = if (lot.columns.isEmpty()) IntArray(patch.width) { it } else lot.columns.toIntArray()
    writeNumbers(Path.of(lot.answers), engine { holding.values(columns) }).read()
    return bench.copy(patches = bench.patches + (lot.patch to patch)) to
        Took(Duration.between(started, loaded), Duration.between(running, ran))
}
