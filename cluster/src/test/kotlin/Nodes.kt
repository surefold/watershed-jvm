import dev.surefold.Deferred
import dev.surefold.Promise
import dev.surefold.Scope
import dev.surefold.Shared
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
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.junit.expect
import dev.surefold.resourceScope
import dev.surefold.scope
import dev.surefold.files.Files
import dev.surefold.files.InMemoryFiles
import dev.surefold.junit.withTempDirectory
import dev.surefold.watershed.cluster.COORDINATOR
import dev.surefold.watershed.cluster.Jobs
import dev.surefold.watershed.cluster.Patience
import dev.surefold.watershed.cluster.Store
import dev.surefold.watershed.cluster.Working
import dev.surefold.watershed.cluster.jobs
import dev.surefold.watershed.cluster.WORKER
import dev.surefold.watershed.Refused
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What [block] answers from the engine; the test fails if the engine refuses any of it. */
internal fun <A> engine(block: Fail<Refused>.() -> A): A = attempt(block).shouldBeRight()

/** Runs [test] with nodes it can start, in a scope of its own, and stops them all after it. */
internal fun cluster(lotWithin: Duration = 5.minutes, test: (Nodes) -> Unit) = withTempDirectory("watershed-cluster") { folder ->
    scope {
        resourceScope {
            val nodes = Nodes(this@scope, InMemoryFiles(), folder, lotWithin)
            onRelease { nodes.stopAll() }
            test(nodes)
        }
    }
}

/**
 * Several nodes of one cluster in this JVM. Each is a fork of the test's scope with a scope of its own inside, so
 * that one can be stopped, and leave the cluster, while the others go on.
 */
internal class Nodes(private val test: Scope<Nothing>, private val files: Files, private val folder: Path, lotWithin: Duration) {
    // Quick to tell a lot again and to ask how a round stands, so that a test is not spent waiting.
    private val patience = Patience(lotWithin = lotWithin, tellAgainEvery = 300.milliseconds, askEvery = 50.milliseconds, settled = 600.milliseconds)

    /** The test's own way into the bucket the nodes share, for putting a job's files there. */
    val store: Store = store("test")

    // Every node shares the bucket, and has a folder of its own for the copies it fetches.
    private fun store(name: String) = Store(files, folder.resolve(name))

    // A node is given its port when it is made, so a free one is found first; Surefold has no way to ask for one.
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val seed = freePort()
    private val stops = Shared<Map<String, () -> Unit>>(emptyMap(), "test.nodes")

    /** The node the others join, which runs the coordinator and no lots; a job is submitted through what it answers. */
    fun coordinator(): Jobs =
        start("coordinator", setOf(COORDINATOR), seed) { cluster -> jobs(cluster, store("coordinator"), patience = patience).shouldBeRight() }

    /** Another node that may run the coordinator, and does once the first has gone. */
    fun standby(): Jobs =
        start("standby", setOf(COORDINATOR), freePort()) { cluster -> jobs(cluster, store("standby"), patience = patience).shouldBeRight() }

    /** A worker on the CPU, which calls [beforeRun] before each lot it runs. */
    fun worker(name: String, beforeRun: (Int) -> Unit = {}): Jobs =
        start(name, setOf(WORKER), freePort()) { cluster ->
            jobs(cluster, store(name), Working(gpu = false, beforeRun = beforeRun), patience).shouldBeRight()
        }

    /** Workers with these names, once [jobs] counts every worker there is. */
    fun workers(jobs: Jobs, vararg names: String) {
        names.forEach { worker(it) }
        val count = stops.peek().keys.count { it.startsWith("worker") }
        expect(jobs.awaitWorkers(count, 30.seconds)) { "$count workers joined and settled" }
    }

    /** Runs [block] beside the test, as a fork of its scope. */
    fun <T> beside(block: () -> T): Deferred<T> = test.async { block() }

    /** Stops the node called [name]: it leaves the cluster, and its actors stop. */
    fun stop(name: String) = stops.modify { it - name to it[name] }?.invoke()

    fun stopAll() = stops.modify { emptyMap<String, () -> Unit>() to it.values }.forEach { it() }

    private fun <T> start(name: String, roles: Set<String>, port: Int, body: Scope<Nothing>.(Cluster) -> T): T {
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
                val result = body(cluster)
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
