package dev.surefold.watershed.cluster

import dev.surefold.Scope
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    val on = if (card == null) "cpu" else "gpu"
    val plans = HashMap<String, Plan>()
    val patches = HashMap<String, Patch>()
    // The lots held, by job and number. The actor asks whether one is there while the runner may be adding another.
    val held = ConcurrentHashMap<Pair<String, Int>, Held>()
    val busy = AtomicBoolean(false)
    // The lot is run off the actor's own thread, so that the actor can go on answering while it runs.
    val runner = Executors.newSingleThreadExecutor { work -> Thread(work, "watershed-lot").apply { isDaemon = true } }

    fun timed(job: String, lot: Int, work: (loaded: (Double) -> Unit) -> Double): Outcome {
        var loaded = 0.0
        var ran = 0.0
        val failed = try {
            ran = work { loaded = it }
            null
        } catch (thrown: Exception) {
            thrown.toString()
        }
        return Outcome(job, lot, name, failed, loaded, ran, on)
    }

    fun run(lot: Assign): Outcome = timed(lot.job, lot.lot) { loaded ->
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

    fun refresh(lot: Refresh, holding: Held): Outcome = timed(lot.tag, lot.lot) { loaded ->
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

    fun start(work: () -> Outcome, done: dev.surefold.actor.ActorRef<Outcome>) = runner.execute {
        val outcome = try {
            work()
        } finally {
            // Free before the outcome is told, so that the next lot it brings is taken.
            busy.set(false)
        }
        done.tell(outcome)
    }

    val actor = spawn(
        "worker",
        behaviour<ToWorker, Unit>(Unit) { _, _, told ->
            when (told) {
                is Assign -> if (!busy.compareAndSet(false, true)) {
                    told.accepted(BUSY)
                } else {
                    told.accepted(TAKEN)
                    start({ run(told) }, told.done)
                }

                is Refresh -> {
                    val holding = held[told.job to told.lot]
                    when {
                        holding == null -> told.accepted(NOT_HELD)
                        !busy.compareAndSet(false, true) -> told.accepted(BUSY)
                        else -> {
                            told.accepted(TAKEN)
                            start({ refresh(told, holding) }, told.done)
                        }
                    }
                }
            }
            stay()
        },
    )
    node.expose(actor, ToWorkerCodec)
}
