package dev.surefold.watershed.cluster

import dev.surefold.Scope
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Behaviour
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.RemoteNode
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.watershed.Gpu
import dev.surefold.watershed.Held
import dev.surefold.watershed.Patch
import dev.surefold.watershed.Plan
import dev.surefold.watershed.WatershedException
import java.nio.file.Path

// What the worker's actor gives its bench to do, and what the bench tells it back.
private data class Work(val told: ToWorker, val back: ActorRef<WorkerTurn>)

private data class Ran(val outcome: Outcome, val from: ActorRef<FromWorker>, val holds: Pair<String, Int>?) : WorkerTurn

// What the worker's actor knows: whether its bench is at work, and which lots, by job and number, the bench holds.
private data class Desk(
    val busy: Boolean = false,
    val held: Set<Pair<String, Int>> = emptySet(),
    val bench: ActorRef<Work>? = null,
)

/**
 * EXPERIMENTAL. Makes this node a worker: it runs one lot at a time, on its GPU if it has one and [gpu] is not
 * false, and otherwise on the CPU. Give the node the [WORKER] role when it joins the cluster. A worker that
 * shares its GPU with others is told how many bytes of its memory it may use, with [gpuBytes].
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
 * The plans, the patches and the lots held are the bench's alone, so nothing here is locked.
 */
fun Scope<*>.worker(node: RemoteNode, gpu: Boolean? = null, gpuBytes: Long? = null, beforeRun: (Int) -> Unit = {}) {
    val name = node.self.toString()
    val card: Gpu? = when (gpu) {
        false -> null
        true -> Gpu()
        null -> try {
            Gpu()
        } catch (none: WatershedException) {
            null
        }
    }
    val actor = spawn(
        "worker",
        behaviour<WorkerTurn, Desk>(Desk()) { ctx, desk, told ->
            // A child, so it is stopped with this actor, and its turn is interrupted when the scope closes.
            val bench = desk.bench ?: ctx.spawn("bench", bench(name, card, gpuBytes, beforeRun))
            fun give(tag: String, lot: Int, from: ActorRef<FromWorker>, work: ToWorker) = if (desk.busy) {
                from.tell(Answer(tag, lot, name, BUSY))
                become(desk.copy(bench = bench))
            } else {
                from.tell(Answer(tag, lot, name, TAKEN))
                bench.tell(Work(work, ctx.self))
                become(desk.copy(busy = true, bench = bench))
            }
            when (told) {
                is Assign -> give(told.tag, told.lot, told.from, told)

                is Refresh -> if (told.job to told.lot in desk.held) {
                    give(told.tag, told.lot, told.from, told)
                } else {
                    told.from.tell(Answer(told.tag, told.lot, name, NOT_HELD))
                    stay()
                }

                // Free before the outcome is told, so that the next lot it brings is taken.
                is Ran -> {
                    told.from.tell(told.outcome)
                    become(desk.copy(busy = false, held = desk.held + listOfNotNull(told.holds)))
                }
            }
        },
    )
    node.expose<ToWorker>(actor, ToWorkerCodec)
}

// Runs what it is given, one lot to a turn, and tells the worker how each went.
private fun bench(name: String, card: Gpu?, gpuBytes: Long?, beforeRun: (Int) -> Unit): Behaviour<Work, Unit, Nothing> {
    val on = if (card == null) "cpu" else "gpu"
    val plans = HashMap<String, Plan>()
    val patches = HashMap<String, Patch>()
    val held = HashMap<Pair<String, Int>, Held>()

    fun timed(tag: String, lot: Int, work: (loaded: (Double) -> Unit) -> Double): Outcome {
        var loaded = 0.0
        var ran = 0.0
        val failed = try {
            ran = work { loaded = it }
            null
        } catch (stopped: InterruptedException) {
            // The scope is closing: the turn ends here, and nobody is told.
            throw stopped
        } catch (thrown: Exception) {
            thrown.toString()
        }
        return Outcome(tag, lot, name, failed, loaded, ran, on)
    }

    fun run(lot: Assign): Outcome = timed(lot.tag, lot.lot) { loaded ->
        val started = System.nanoTime()
        val plan = plans.getOrPut(lot.plan) { Plan.load(Path.of(lot.plan)) }
        val inputs = readNumbers(Path.of(lot.inputs), lot.first * plan.inputs, Math.multiplyExact(lot.count, plan.inputs))
        loaded((System.nanoTime() - started) / 1e9)
        beforeRun(lot.lot)
        val running = System.nanoTime()
        val answers = when {
            card == null -> plan.runCpu(inputs)
            gpuBytes == null -> card.run(plan, inputs)
            else -> card.run(plan, inputs, gpuBytes)
        }
        val ran = (System.nanoTime() - running) / 1e9
        val columns = lot.columns.toIntArray()
        if (lot.hold) {
            val holding = Held(plan.inputs, inputs, answers)
            held[lot.job to lot.lot] = holding
            writeNumbers(Path.of(lot.answers), if (columns.isEmpty()) answers else holding.values(columns))
        } else {
            val outputs = plan.outputs
            val chosen = if (columns.isEmpty()) {
                answers
            } else {
                DoubleArray(lot.count * columns.size) { answers[it / columns.size * outputs + columns[it % columns.size]] }
            }
            writeNumbers(Path.of(lot.answers), chosen)
        }
        ran
    }

    fun refresh(lot: Refresh): Outcome = timed(lot.tag, lot.lot) { loaded ->
        val holding = held[lot.job to lot.lot] ?: error("lot ${lot.lot} of ${lot.job} is not held")
        val started = System.nanoTime()
        val patch = patches.getOrPut(lot.patch) { Patch.load(Path.of(lot.patch)) }
        loaded((System.nanoTime() - started) / 1e9)
        beforeRun(lot.lot)
        val running = System.nanoTime()
        holding.refresh(patch, card, gpuBytes)
        val ran = (System.nanoTime() - running) / 1e9
        val columns = if (lot.columns.isEmpty()) IntArray(patch.width) { it } else lot.columns.toIntArray()
        writeNumbers(Path.of(lot.answers), holding.values(columns))
        ran
    }

    return behaviour(Unit) { _, _, work ->
        when (val told = work.told) {
            is Assign -> {
                val outcome = run(told)
                work.back.tell(Ran(outcome, told.from, (told.job to told.lot).takeIf { told.hold && outcome.failed == null }))
            }

            is Refresh -> work.back.tell(Ran(refresh(told), told.from, null))
        }
        stay()
    }
}
