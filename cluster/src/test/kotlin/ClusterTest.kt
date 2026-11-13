import dev.surefold.Scope
import dev.surefold.actor.remote.RemoteNode
import dev.surefold.actor.remote.node
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Downing
import dev.surefold.cluster.Gossiping
import dev.surefold.cluster.Ready
import dev.surefold.cluster.cluster
import dev.surefold.scope
import dev.surefold.watershed.Expr
import dev.surefold.watershed.Graph
import dev.surefold.watershed.Node
import dev.surefold.watershed.Plan
import dev.surefold.watershed.cluster.Coordinator
import dev.surefold.watershed.cluster.Job
import dev.surefold.watershed.cluster.WORKER
import dev.surefold.watershed.cluster.coordinator
import dev.surefold.watershed.cluster.worker
import dev.surefold.watershed.cluster.writeNumbers
import dev.surefold.watershed.plus
import dev.surefold.watershed.minus
import dev.surefold.watershed.times
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Several nodes of one cluster in this JVM, each in a scope of its own on a thread of its own. */
private class Nodes : AutoCloseable {
    private val seed = free()
    private val stops = ArrayList<Pair<CountDownLatch, Thread>>()

    private fun free(): Int = ServerSocket(0).use { it.localPort }

    /** Starts a node and gives back what [body] made on it, and a way to stop it. */
    fun <T> start(name: String, roles: Set<String>, first: Boolean = false, body: Scope<Nothing>.(RemoteNode, Cluster) -> T): Pair<T, () -> Unit> {
        val made = CompletableFuture<T>()
        val stop = CountDownLatch(1)
        val thread = Thread.ofPlatform().name(name).start {
            try {
                scope {
                    val node = node(name, if (first) seed else free())
                    val cluster = cluster(
                        node,
                        Discovery.static("127.0.0.1:$seed"),
                        gossiping = Gossiping(probeEvery = 200.milliseconds, ackWithin = 100.milliseconds, formAfter = 1.seconds),
                        downing = Downing.keepMajority(stableAfter = 2.seconds),
                        leaveWithin = 3.seconds,
                        roles = roles,
                        ready = Ready.WhenTold,
                    )
                    val result = body(node, cluster)
                    cluster.admit()
                    made.complete(result)
                    stop.await()
                }
            } catch (thrown: Throwable) {
                made.completeExceptionally(thrown)
            }
        }
        stops += stop to thread
        return made.get(30, TimeUnit.SECONDS) to {
            stop.countDown()
            thread.join(20_000)
        }
    }

    override fun close() {
        stops.forEach { it.first.countDown() }
        stops.forEach { it.second.join(20_000) }
    }
}

@OptIn(ExperimentalPathApi::class)
class ClusterTest {
    private val g = Graph()
    private val rate = g.input("rate", 0.01)
    private val shock = g.input("shock", 0.0)
    private val worth: List<Node<Double>> = (0 until 8).map { n ->
        var value = g.define("v${n}_0", (rate + 1.0) * (100.0 + n))
        for (t in 1 until 5) value = g.define("v${n}_$t", value * (rate + 1.0) - shock)
        value
    }
    private val total = g.define("total", worth.drop(1).fold(worth[0] as Expr<Double>) { all, x -> all + x })
    private val plan: Plan = run {
        g.stabilize()
        g.plan(vary = listOf(rate, shock), outputs = listOf(total, worth[3]))
    }
    private val scenarios = DoubleArray(2_000) { if (it % 2 == 0) 0.0001 * it - 0.05 else 0.3 * (it % 7) }

    /** A job of 1,000 scenarios in lots of 100, in a folder of its own, and what one run of them all gives. */
    private fun withJob(test: (Job, DoubleArray) -> Unit) {
        val folder = Files.createTempDirectory("watershed-cluster")
        try {
            plan.save(folder.resolve("plan"))
            writeNumbers(folder.resolve("scenarios"), scenarios)
            test(Job("job", folder.resolve("plan"), folder.resolve("scenarios"), plan.inputs, 100, folder.resolve("out")), plan.runCpu(scenarios))
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun Nodes.coordinator(lotWithin: Duration = 5.minutes): Coordinator =
        start("coordinator", emptySet(), first = true) { node, cluster -> coordinator(node, cluster, lotWithin = lotWithin) }.first

    @Test
    fun `lots run by several workers give what one run gives`() = withJob { job, whole ->
        Nodes().use { nodes ->
            val coordinator = nodes.coordinator()
            repeat(3) { n -> nodes.start("worker-$n", setOf(WORKER)) { node, _ -> worker(node, gpu = false) } }
            assertTrue(coordinator.awaitWorkers(3, 30.seconds), "three workers joined")

            val report = coordinator.run(job, within = 2.minutes)
            assertEquals(10, report.lots.size)
            assertTrue(report.lots.map { it.worker }.distinct().size >= 2, "more than one worker ran lots: $report")
            assertContentEquals(whole, coordinator.answers(job, plan.outputs))
        }
    }

    @Test
    fun `a lot whose worker leaves is run by another`() = withJob { job, whole ->
        Nodes().use { nodes ->
            val coordinator = nodes.coordinator()
            repeat(2) { n -> nodes.start("worker-$n", setOf(WORKER)) { node, _ -> worker(node, gpu = false) } }
            // A worker that takes a lot and never finishes it.
            val held = CountDownLatch(1)
            val stuck = CountDownLatch(1)
            val (_, stop) = nodes.start("worker-stuck", setOf(WORKER)) { node, _ ->
                worker(node, gpu = false, beforeRun = {
                    held.countDown()
                    stuck.await()
                })
            }
            assertTrue(coordinator.awaitWorkers(3, 30.seconds), "three workers joined")

            val running = CompletableFuture.supplyAsync { coordinator.run(job, within = 2.minutes) }
            assertTrue(held.await(30, TimeUnit.SECONDS), "the stuck worker was given a lot")
            stop()
            val report = running.get(2, TimeUnit.MINUTES)
            stuck.countDown()

            assertTrue(report.givenAgain >= 1, "its lot was given out again: $report")
            assertTrue(report.lots.none { it.worker.startsWith("worker-stuck") })
            assertContentEquals(whole, coordinator.answers(job, plan.outputs))
        }
    }

    @Test
    fun `a lot that has been out too long is run by another`() = withJob { job, whole ->
        Nodes().use { nodes ->
            val coordinator = nodes.coordinator(lotWithin = 2.seconds)
            nodes.start("worker-0", setOf(WORKER)) { node, _ -> worker(node, gpu = false) }
            val stuck = CountDownLatch(1)
            nodes.start("worker-stuck", setOf(WORKER)) { node, _ -> worker(node, gpu = false, beforeRun = { stuck.await() }) }
            assertTrue(coordinator.awaitWorkers(2, 30.seconds), "two workers joined")

            val report = coordinator.run(job, within = 2.minutes)
            stuck.countDown()
            assertTrue(report.givenAgain >= 1, "its lot was given out again: $report")
            assertContentEquals(whole, coordinator.answers(job, plan.outputs))
        }
    }
}
