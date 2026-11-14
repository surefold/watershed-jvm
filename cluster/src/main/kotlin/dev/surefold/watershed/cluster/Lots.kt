package dev.surefold.watershed.cluster

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Behaviour
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.stay
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.ensureNotNull
import dev.surefold.fail.fold
import dev.surefold.fail.mapLeft
import dev.surefold.logInfo
import dev.surefold.watershed.Gpu
import dev.surefold.watershed.Held
import dev.surefold.watershed.Patch
import dev.surefold.watershed.Plan
import dev.surefold.watershed.Refused
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

// A lot is an entity of the cluster's sharding: an actor with an id, run on whichever worker owns its shard, and
// reached from any node by that id. It does none of the work itself. It hands what it is told to its node's bench,
// which runs one lot at a time, and remembers how it went, so that being told the same thing twice does it once.
// What it holds, it holds for as long as it stays on its worker: an entity that moves starts with nothing.

// What a lot is doing, and what it holds.
internal data class Desk(val doing: Doing = Doing.Idle, val held: Held? = null) {
    sealed interface Doing {
        data object Idle : Doing

        /** With the bench, for the round tagged [tag]. */
        data class Working(val tag: String) : Doing

        /** Done for the round tagged [tag], with this [outcome], told again to whoever asks again. */
        data class Done(val tag: String, val outcome: FromLot) : Doing
    }
}

// What the bench tells a lot when it has run what the lot gave it.
internal data class Finished(val tag: String, val outcome: FromLot, val from: ActorRef<FromLot>, val held: Held?) : LotTurn

// What a lot gives the bench: what it was told, what it holds, and itself to tell back.
internal data class Work(val told: Task, val held: Held?, val back: ActorRef<LotTurn>)

internal fun lot(worker: String, bench: ActorRef<Work>): Behaviour<LotTurn, Desk, Nothing> = behaviour(Desk()) { ctx, desk, told ->
    when (told) {
        is Where -> stay().also { told.from.tell(At(told.tag, told.lot, worker, told.id)) }

        is Task -> when {
            // Told again what it is already doing, or has done: the coordinator asks again when it has heard nothing.
            desk.doing == Desk.Doing.Working(told.tag) -> stay()
            desk.doing is Desk.Doing.Done && desk.doing.tag == told.tag -> stay().also { told.from.tell(desk.doing.outcome) }
            told is BringUpToDate && desk.held == null -> stay().also { told.from.tell(NotHeld(told.tag, told.lot, worker)) }
            else -> {
                told.from.tell(Queued(told.tag, told.lot, worker))
                bench.tell(Work(told, desk.held, ctx.self))
                become(desk.copy(doing = Desk.Doing.Working(told.tag)))
            }
        }

        // Only what it is still working on counts: a lot told something newer meanwhile has moved on.
        is Finished -> if (desk.doing == Desk.Doing.Working(told.tag)) {
            told.from.tell(told.outcome)
            become(Desk(Desk.Doing.Done(told.tag, told.outcome), told.held ?: desk.held))
        } else {
            stay()
        }

        is NotUnderstood -> stay().also { logInfo("lot: a message of kind ${told.kind} was not understood") }
    }
}

// What a bench is given once and never changes.
internal class Rig(val worker: String, val store: Store, val card: Gpu?, val gpuBytes: Long?, val beforeRun: (Int) -> Unit) {
    val on: String = if (card == null) "cpu" else "gpu"
}

// What a bench has fetched; each lot it runs answers the next of these. A plan, and a job's scenarios, are fetched
// once and kept.
internal data class Bench(
    val plans: Map<String, Plan> = emptyMap(),
    val patches: Map<String, Patch> = emptyMap(),
    val inputs: Map<String, DoubleArray> = emptyMap(),
)

// How long a lot took to get ready and to run, and what it now holds.
private data class Took(val loading: Duration, val running: Duration, val held: Held?)

private fun Duration.seconds(): Double = toDouble(DurationUnit.SECONDS)

private fun now(): Instant = clock.get().now().toKotlinInstant()

/**
 * A worker's bench: one actor to a node, which runs the lots of that node one to a turn, so that a GPU is never
 * given two at once. A turn may block for as long as a lot takes; the lots themselves go on answering meanwhile.
 */
internal fun bench(rig: Rig): Behaviour<Work, Bench, Nothing> = behaviour(Bench()) { _, bench, work ->
    val told = work.told
    told.from.tell(Begun(told.tag, told.lot, rig.worker))
    val done = attempt<String, Pair<Bench, Took>> {
        when (told) {
            is Run -> run(rig, bench, told)
            is BringUpToDate -> bringUpToDate(rig, bench, told, ensureNotNull(work.held) { "lot ${told.lot} holds nothing" })
        }
    }
    val outcome = done.fold(
        { why -> Failed(told.tag, told.lot, rig.worker, why) },
        { (_, took) -> Ran(told.tag, told.lot, rig.worker, told.id, rig.on, took.loading.seconds(), took.running.seconds()) },
    )
    work.back.tell(Finished(told.tag, outcome, told.from, done.fold({ null }, { it.second.held })))
    done.fold({ stay() }, { (next, _) -> become(next) })
}

// What the engine refuses is, here, why a lot failed, told to the coordinator.
context(_: Fail<String>)
private fun <A> engine(block: Fail<Refused>.() -> A): A = attempt(block).mapLeft { it.why }.bind()

context(_: Fail<String>)
private fun <A> Either<JobFailure.File, A>.filed(): A = mapLeft { "${it.key}: ${it.why}" }.bind()

context(_: Fail<String>)
private fun run(rig: Rig, bench: Bench, lot: Run): Pair<Bench, Took> {
    val started = now()
    val plan = bench.plans[lot.plan] ?: rig.store.fetch(lot.plan).filed().let { saved -> engine { Plan.from(saved) } }
    val all = bench.inputs[lot.inputs] ?: rig.store.read(lot.inputs).filed()
    val from = lot.first * plan.inputs
    val until = from + lot.count.toLong() * plan.inputs
    ensure(from >= 0 && until <= all.size) { "${lot.inputs}: it has no scenario ${lot.first + lot.count - 1}" }
    val inputs = all.copyOfRange(from.toInt(), until.toInt())
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
    val held = if (lot.hold) engine { Held.of(plan.inputs, inputs, answers) } else null
    val chosen = when {
        columns.isEmpty() -> answers
        held != null -> engine { held.values(columns) }
        else -> DoubleArray(lot.count * columns.size) { answers[it / columns.size * plan.outputs + columns[it % columns.size]] }
    }
    rig.store.write(lot.answers, chosen).filed()
    return bench.copy(plans = bench.plans + (lot.plan to plan), inputs = bench.inputs + (lot.inputs to all)) to
        Took(loaded - started, ran - running, held)
}

context(_: Fail<String>)
private fun bringUpToDate(rig: Rig, bench: Bench, lot: BringUpToDate, held: Held): Pair<Bench, Took> {
    val started = now()
    val patch = bench.patches[lot.patch] ?: rig.store.fetch(lot.patch).filed().let { saved -> engine { Patch.from(saved) } }
    val loaded = now()
    rig.beforeRun(lot.lot)
    val running = now()
    engine { held.refresh(patch, rig.card, rig.gpuBytes) }
    val ran = now()
    val columns = if (lot.columns.isEmpty()) IntArray(patch.width) { it } else lot.columns.toIntArray()
    rig.store.write(lot.answers, engine { held.values(columns) }).filed()
    return bench.copy(patches = bench.patches + (lot.patch to patch)) to Took(loaded - started, ran - running, held)
}
