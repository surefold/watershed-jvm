package dev.surefold.watershed.cluster

import dev.surefold.Scope
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.RemoteNode
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.watershed.Gpu
import dev.surefold.watershed.Plan
import dev.surefold.watershed.WatershedException
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EXPERIMENTAL. Makes this node a worker: it runs one lot at a time, on its GPU if it has one and [gpu] is not
 * false, and otherwise on the CPU. Give the node the [WORKER] role when it joins the cluster.
 *
 * A plan is read once and kept, so only a worker's first lot of a job pays for loading it. [beforeRun] is called
 * with each lot's number just before it is run; tests use it to hold a worker up.
 */
fun Scope<*>.worker(node: RemoteNode, gpu: Boolean? = null, beforeRun: (Int) -> Unit = {}) {
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
    val busy = AtomicBoolean(false)
    // The lot is run off the actor's own thread, so that the actor can go on answering while it runs.
    val runner = Executors.newSingleThreadExecutor { work -> Thread(work, "watershed-lot").apply { isDaemon = true } }

    fun run(lot: Assign): Outcome {
        var loaded = 0.0
        var ran = 0.0
        val failed = try {
            val started = System.nanoTime()
            val plan = plans.getOrPut(lot.plan) { Plan.load(Path.of(lot.plan)) }
            val inputs = readNumbers(Path.of(lot.inputs), lot.first * plan.inputs, Math.multiplyExact(lot.count, plan.inputs))
            loaded = (System.nanoTime() - started) / 1e9
            beforeRun(lot.lot)
            val running = System.nanoTime()
            val answers = if (card == null) plan.runCpu(inputs) else card.run(plan, inputs)
            ran = (System.nanoTime() - running) / 1e9
            writeNumbers(Path.of(lot.answers), answers)
            null
        } catch (thrown: Exception) {
            thrown.toString()
        }
        return Outcome(lot.job, lot.lot, name, failed, loaded, ran, on)
    }

    val actor = spawn(
        "worker",
        behaviour<Assign, Unit>(Unit) { _, _, lot ->
            if (!busy.compareAndSet(false, true)) {
                lot.accepted(false)
            } else {
                lot.accepted(true)
                runner.execute {
                    val outcome = try {
                        run(lot)
                    } finally {
                        // Free before the outcome is told, so that the next lot it brings is taken.
                        busy.set(false)
                    }
                    lot.done.tell(outcome)
                }
            }
            stay()
        },
    )
    node.expose(actor, AssignCodec)
}
