import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldHaveSize
import dev.surefold.junit.shouldNotBeEmpty
import dev.surefold.junit.story
import dev.surefold.junit.withTempDirectory
import dev.surefold.watershed.Expr
import dev.surefold.watershed.Graph
import dev.surefold.watershed.Input
import dev.surefold.watershed.Kept
import dev.surefold.watershed.Node
import dev.surefold.watershed.cluster.Coordinator
import dev.surefold.watershed.cluster.Job
import dev.surefold.watershed.cluster.writeNumbers
import dev.surefold.watershed.minus
import dev.surefold.watershed.plus
import dev.surefold.watershed.times
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

object HeldClusterSuite : Suite() {
    init {
        test("A run held by several workers is brought up to date by a patch") {
            withLedger { ledger ->
                cluster { nodes ->
                    story {
                        val coordinator = Given("three workers holding a run of ten lots between them") {
                            nodes.coordinator().also {
                                nodes.workers(it, "worker-0", "worker-1", "worker-2")
                                it.run(ledger.job, within = 2.minutes).shouldBeRight()
                                ledger.totals(it) shouldBe ledger.totalsNow()
                            }
                        }
                        val reports = When("one account's floor changes, and then another's") {
                            listOf(6 to 150.0, 19 to 20.0).map { (account, floor) ->
                                coordinator.refresh(ledger.job, ledger.change(account, floor), within = 2.minutes)
                                    .shouldBeRight()
                                    .also { ledger.totals(coordinator) shouldBe ledger.totalsNow() }
                            }
                        }
                        Then("each change reached every lot, and none was lost") {
                            reports.forEach { report ->
                                report.lots shouldHaveSize 10
                                report.lost shouldBe emptyList()
                            }
                        }
                    }
                }
            }
        }

        test("Lots whose worker has gone are reported lost and can be run again") {
            withLedger { ledger ->
                cluster { nodes ->
                    story {
                        val coordinator = Given("three workers holding a run, one of which then leaves") {
                            nodes.coordinator().also { nodes.workers(it, "worker-0", "worker-1", "worker-going") }
                        }
                        val gone = coordinator.run(ledger.job, within = 2.minutes).shouldBeRight()
                            .lots.filter { it.worker.startsWith("worker-going") }.map { it.lot }
                        gone.shouldNotBeEmpty()
                        nodes.stop("worker-going")

                        val report = When("an account's floor changes") {
                            coordinator.refresh(ledger.job, ledger.change(6, 150.0), within = 2.minutes).shouldBeRight()
                        }
                        Then("the lots the leaver held are lost, and the rest are brought up to date") {
                            report.lost shouldBe gone.sorted()
                            report.lots shouldHaveSize 10 - gone.size
                        }

                        val after = When("the lost lots are run again with a plan made after the change") {
                            ledger.jobNow("plan-after").also {
                                coordinator.run(it, within = 2.minutes, only = report.lost).shouldBeRight()
                            }
                        }
                        Then("the answers are what running everything now gives") {
                            ledger.totals(coordinator, after) shouldBe ledger.totalsNow()
                        }
                        And("every lot is held again, for the next change") {
                            val next = coordinator.refresh(after, ledger.change(19, 20.0), within = 2.minutes).shouldBeRight()
                            next.lost shouldBe emptyList()
                            ledger.totals(coordinator, after) shouldBe ledger.totalsNow()
                        }
                    }
                }
            }
        }
    }
}

/** Runs [test] with a ledger of its own, its files in a folder that is deleted after it. */
private fun withLedger(test: (Ledger) -> Unit) = withTempDirectory("watershed-held") { folder: Path -> test(Ledger(folder)) }

/**
 * Twenty accounts in five groups, a sum for each group and a sum of those, with the groups' sums kept, and a job
 * that runs 1,000 scenarios of it to be held. Each test has its own, since changing a floor changes the graph.
 */
private class Ledger(private val folder: Path) {
    private val g = engine { Graph() }
    private val rate = engine { g.input("rate", 0.01) }
    private val shock = engine { g.input("shock", 0.0) }
    private val floors: List<Input<Double>> = engine { (0 until 20).map { n -> g.input("floor$n", 90.0 + n) } }
    private val groups: List<Node<Double>> = engine {
        (0 until 5).map { a ->
            val each = (0 until 4).map { b ->
                val n = a * 4 + b
                val value = (1 until 6).fold(g.define("v${n}_0", (rate + 1.0) * (100.0 + n))) { value, t ->
                    g.define("v${n}_$t", value * (rate + 1.0) - shock)
                }
                g.define("worth$n", value + floors[n] * shock)
            }
            g.define("group$a", each.drop(1).fold(each[0] as Expr<Double>) { all, x -> all + x })
        }
    }
    private val total = engine { g.define("total", groups.drop(1).fold(groups[0] as Expr<Double>) { all, x -> all + x }) }
    private val scenarios = DoubleArray(2_000) { if (it % 2 == 0) 0.00002 * it - 0.03 else 0.01 * (it % 7) }

    private fun kept(): Kept = engine {
        g.stabilize()
        g.kept(vary = listOf(rate, shock), outputs = listOf(total), keep = groups)
    }

    private val kept: Kept = kept()

    /** The job as first run: the kept plan as it was before any change. */
    val job: Job = job(kept, "plan")

    /** A job like the first, with a plan made now. */
    fun jobNow(plan: String): Job = job(kept(), plan)

    private fun job(kept: Kept, plan: String): Job {
        engine { kept.plan.save(folder.resolve(plan)) }
        writeNumbers(folder.resolve("scenarios"), scenarios).shouldBeRight()
        return Job.read(
            "book", folder.resolve(plan), folder.resolve("scenarios"), 2, 100, folder.resolve("out"),
            columns = kept.outputColumns.toList(), hold = true,
        ).shouldBeRight()
    }

    /** Changes [account]'s floor, and answers the file of the patch that brings what was kept up to date with it. */
    fun change(account: Int, floor: Double): Path {
        engine { g[floors[account]] = floor }
        val made = kept()
        return folder.resolve("patch-$account").also { file -> engine { made.patch(listOf(floors[account])).shouldNotBeNull().save(file) } }
    }

    /** The total of every scenario, as the cluster's answers have it. */
    fun totals(coordinator: Coordinator, of: Job = job): List<Double> = coordinator.answers(of, 1).shouldBeRight().asList()

    /** What running every scenario again, with a plan made now, gives for the total. */
    fun totalsNow(): List<Double> = engine { g.plan(vary = listOf(rate, shock), outputs = listOf(total)).runCpu(scenarios) }.asList()
}
