import dev.surefold.Deferred
import dev.surefold.Promise
import dev.surefold.Scope
import dev.surefold.Shared
import dev.surefold.actor.remote.RemoteNode
import dev.surefold.actor.remote.node
import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.awaitValue
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Downing
import dev.surefold.cluster.Gossiping
import dev.surefold.cluster.Ready
import dev.surefold.cluster.cluster
import dev.surefold.junit.shouldBe
import dev.surefold.resourceScope
import dev.surefold.scope
import dev.surefold.test.eventually
import dev.surefold.watershed.cluster.Coordinator
import dev.surefold.watershed.cluster.WORKER
import dev.surefold.watershed.cluster.coordinator
import dev.surefold.watershed.cluster.worker
import java.net.ServerSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Runs [test] with nodes it can start, in a scope of its own, and stops them all after it. */
internal fun cluster(test: (Nodes) -> Unit) = scope {
    resourceScope {
        val nodes = Nodes(this@scope)
        onRelease { nodes.stopAll() }
        test(nodes)
    }
}

/**
 * Several nodes of one cluster in this JVM. Each is a fork of the test's scope with a scope of its own inside, so
 * that one can be stopped, and leave the cluster, while the others go on.
 */
internal class Nodes(private val test: Scope<Nothing>) {
    // A node is given its port when it is made, so a free one is found first; Surefold has no way to ask for one.
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val seed = freePort()
    private val stops = Shared<Map<String, () -> Unit>>(emptyMap(), "test.nodes")

    /** The coordinator, which is also the node the others join. */
    fun coordinator(lotWithin: Duration = 5.minutes): Coordinator =
        start("coordinator", emptySet(), seed) { node, cluster -> coordinator(node, cluster, lotWithin = lotWithin) }

    /** A worker on the CPU, which calls [beforeRun] before each lot it runs. */
    fun worker(name: String, beforeRun: (Int) -> Unit = {}) =
        start(name, setOf(WORKER), freePort()) { node, _ -> worker(node, gpu = false, beforeRun = beforeRun).shouldBeRight() }

    /** Workers with these names, once [coordinator] counts every worker there is. */
    fun workers(coordinator: Coordinator, vararg names: String) {
        names.forEach { worker(it) }
        eventually(30.seconds) { coordinator.workers() shouldBe stops.peek().size - 1 }
    }

    /** Runs [block] beside the test, as a fork of its scope. */
    fun <T> beside(block: () -> T): Deferred<T> = test.async { block() }

    /** Stops the node called [name]: it leaves the cluster, and its actors stop. */
    fun stop(name: String) = stops.modify { it - name to it[name] }?.invoke()

    fun stopAll() = stops.modify { emptyMap<String, () -> Unit>() to it.values }.forEach { it() }

    private fun <T> start(name: String, roles: Set<String>, port: Int, body: Scope<Nothing>.(RemoteNode, Cluster) -> T): T {
        val made = Promise<Nothing, T>("test.node-made")
        val stop = Promise<Nothing, Unit>("test.node-stop")
        val running = test.async {
            scope {
                val node = node(name, port)
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
                made.succeed(result)
                stop.awaitValue()
            }
        }
        stops.update {
            it + (name to {
                stop.succeed(Unit)
                running.await()
            })
        }
        return made.awaitValue(30.seconds).shouldNotBeNull()
    }
}
