import dev.surefold.watershed.Expr
import dev.surefold.watershed.Graph
import dev.surefold.watershed.Input
import dev.surefold.watershed.Kept
import dev.surefold.watershed.Node
import dev.surefold.watershed.cluster.Coordinator
import dev.surefold.watershed.cluster.Job
import dev.surefold.watershed.cluster.WORKER
import dev.surefold.watershed.cluster.coordinator
import dev.surefold.watershed.cluster.worker
import dev.surefold.watershed.cluster.writeNumbers
import dev.surefold.watershed.plus
import dev.surefold.watershed.minus
import dev.surefold.watershed.times
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalPathApi::class)
class HeldClusterTest {
    // Accounts in groups, a sum for each group and a sum of those: the groups' sums are kept.
    private val g = Graph()
    private val rate = g.input("rate", 0.01)
    private val shock = g.input("shock", 0.0)
    private val floors = ArrayList<Input<Double>>()
    private val groups: List<Node<Double>> = (0 until 5).map { a ->
        val each = (0 until 4).map { b ->
            val n = a * 4 + b
            val floor = g.input("floor$n", 90.0 + n).also(floors::add)
            var value = g.define("v${n}_0", (rate + 1.0) * (100.0 + n))
            for (t in 1 until 6) value = g.define("v${n}_$t", value * (rate + 1.0) - shock)
            g.define("worth$n", value + floor * shock)
        }
        g.define("group$a", each.drop(1).fold(each[0] as Expr<Double>) { all, x -> all + x })
    }
    private val total = g.define("total", groups.drop(1).fold(groups[0] as Expr<Double>) { all, x -> all + x })
    private val scenarios = DoubleArray(2_000) { if (it % 2 == 0) 0.00002 * it - 0.03 else 0.01 * (it % 7) }

    private fun kept(): Kept = g.kept(vary = listOf(rate, shock), outputs = listOf(total), keep = groups)

    /** What running every scenario again, with a plan made now, gives for the total. */
    private fun totalsNow(): DoubleArray = g.plan(vary = listOf(rate, shock), outputs = listOf(total)).runCpu(scenarios)

    private fun withFolder(test: (Path) -> Unit) {
        val folder = Files.createTempDirectory("watershed-held")
        try {
            writeNumbers(folder.resolve("scenarios"), scenarios)
            test(folder)
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun job(folder: Path, kept: Kept, plan: String = "plan"): Job {
        kept.plan.save(folder.resolve(plan))
        return Job(
            "book", folder.resolve(plan), folder.resolve("scenarios"), 2, 100, folder.resolve("out"),
            columns = kept.outputColumns.toList(), hold = true,
        )
    }

    private fun Nodes.coordinator(): Coordinator =
        start("coordinator", emptySet(), first = true) { node, cluster -> coordinator(node, cluster) }.first

    @Test
    fun `a run held by several workers is brought up to date by a patch`() = withFolder { folder ->
        g.stabilize()
        Nodes().use { nodes ->
            val coordinator = nodes.coordinator()
            repeat(3) { n -> nodes.start("worker-$n", setOf(WORKER)) { node, _ -> worker(node, gpu = false) } }
            assertTrue(coordinator.awaitWorkers(3, 30.seconds), "three workers joined")

            val kept = kept()
            val job = job(folder, kept)
            coordinator.run(job, within = 2.minutes)
            assertContentEquals(totalsNow(), coordinator.answers(job, 1))

            for ((n, to) in listOf(6 to 150.0, 19 to 20.0)) {
                g[floors[n]] = to
                g.stabilize()
                val patch = folder.resolve("patch-$n")
                kept.patch(listOf(floors[n]))!!.save(patch)
                val report = coordinator.refresh(job, patch, within = 2.minutes)
                assertEquals(10, report.lots.size, "$report")
                assertEquals(emptyList(), report.lost)
                assertContentEquals(totalsNow(), coordinator.answers(job, 1))
            }
        }
    }

    @Test
    fun `lots whose worker has gone are reported lost and can be run again`() = withFolder { folder ->
        g.stabilize()
        Nodes().use { nodes ->
            val coordinator = nodes.coordinator()
            repeat(2) { n -> nodes.start("worker-$n", setOf(WORKER)) { node, _ -> worker(node, gpu = false) } }
            val (_, stop) = nodes.start("worker-going", setOf(WORKER)) { node, _ -> worker(node, gpu = false) }
            assertTrue(coordinator.awaitWorkers(3, 30.seconds), "three workers joined")

            val kept = kept()
            val job = job(folder, kept)
            val first = coordinator.run(job, within = 2.minutes)
            val gone = first.lots.filter { it.worker.startsWith("worker-going") }.map { it.lot }
            assertTrue(gone.isNotEmpty(), "the worker that will go ran some lots: $first")
            stop()

            g[floors[6]] = 150.0
            g.stabilize()
            val patch = folder.resolve("patch")
            kept.patch(listOf(floors[6]))!!.save(patch)
            val report = coordinator.refresh(job, patch, within = 2.minutes)
            assertEquals(gone.sorted(), report.lost, "$report")
            assertEquals(10 - gone.size, report.lots.size)

            // The lost lots, run again with a plan made after the change, by the workers that are left.
            val after = job(folder, kept(), plan = "plan-after")
            coordinator.run(after, within = 2.minutes, only = report.lost)
            assertContentEquals(totalsNow(), coordinator.answers(after, 1))

            // And every lot is held again, for the next change.
            g[floors[19]] = 20.0
            g.stabilize()
            val next = folder.resolve("patch-next")
            kept().patch(listOf(floors[19]))!!.save(next)
            assertEquals(emptyList(), coordinator.refresh(after, next, within = 2.minutes).lost)
            assertContentEquals(totalsNow(), coordinator.answers(after, 1))
        }
    }
}
