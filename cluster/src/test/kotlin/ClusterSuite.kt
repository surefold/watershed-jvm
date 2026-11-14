import dev.surefold.Promise
import dev.surefold.assert.shouldBeRight
import dev.surefold.awaitValue
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.shouldBeGreaterThanOrEqual
import dev.surefold.junit.shouldHaveSize
import dev.surefold.junit.story
import dev.surefold.junit.withTempDirectory
import dev.surefold.watershed.Expr
import dev.surefold.watershed.Graph
import dev.surefold.watershed.Node
import dev.surefold.watershed.Plan
import dev.surefold.watershed.cluster.Job
import dev.surefold.watershed.cluster.writeNumbers
import dev.surefold.watershed.minus
import dev.surefold.watershed.plus
import dev.surefold.watershed.times
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object ClusterSuite : Suite() {
    init {
        test("Lots run by several workers give what one run gives") {
            withBook { book ->
                cluster { nodes ->
                    story {
                        val coordinator = Given("a coordinator and three workers") {
                            nodes.coordinator().also { nodes.workers(it, "worker-0", "worker-1", "worker-2") }
                        }
                        val report = When("it runs a job of ten lots") {
                            coordinator.run(book.job, within = 2.minutes).shouldBeRight()
                        }
                        Then("every lot is answered, by more than one worker, with what one run gives") {
                            report.lots shouldHaveSize 10
                            report.lots.map { it.worker }.distinct().size shouldBeGreaterThan 1
                            coordinator.answers(book.job, book.plan.outputs).shouldBeRight().asList() shouldBe book.whole
                        }
                    }
                }
            }
        }

        test("A lot whose worker leaves is run by another") {
            withBook { book ->
                cluster { nodes ->
                    story {
                        val taken = Promise<Nothing, Unit>("test.lot-taken")
                        val release = Promise<Nothing, Unit>("test.lot-release")
                        val coordinator = Given("two workers, and a third that takes a lot and never finishes it") {
                            nodes.coordinator().also {
                                nodes.worker("worker-stuck") {
                                    taken.succeed(Unit)
                                    release.awaitValue()
                                }
                                nodes.workers(it, "worker-0", "worker-1")
                            }
                        }
                        val report = When("the third leaves the cluster while the job runs") {
                            val running = nodes.beside { coordinator.run(book.job, within = 2.minutes) }
                            taken.awaitValue(30.seconds) shouldBe Unit
                            nodes.stop("worker-stuck")
                            running.await().shouldBeRight()
                        }
                        Then("its lot was given to another, and the answers are what one run gives") {
                            report.givenAgain shouldBeGreaterThanOrEqual 1
                            report.lots.filter { it.worker.startsWith("worker-stuck") } shouldBe emptyList()
                            coordinator.answers(book.job, book.plan.outputs).shouldBeRight().asList() shouldBe book.whole
                        }
                    }
                }
            }
        }

        test("A lot that has been out too long is run by another") {
            withBook { book ->
                cluster { nodes ->
                    story {
                        val coordinator = Given("a coordinator that waits two seconds for a lot, and a worker that hangs") {
                            nodes.coordinator(lotWithin = 2.seconds).also {
                                nodes.worker("worker-stuck") { Promise<Nothing, Unit>("test.never").awaitValue() }
                                nodes.workers(it, "worker-0")
                            }
                        }
                        val report = When("it runs the job") { coordinator.run(book.job, within = 2.minutes).shouldBeRight() }
                        Then("the hung worker's lot was given to the other, and the answers are what one run gives") {
                            report.givenAgain shouldBeGreaterThanOrEqual 1
                            coordinator.answers(book.job, book.plan.outputs).shouldBeRight().asList() shouldBe book.whole
                        }
                    }
                }
            }
        }
    }
}

/** A job of 1,000 scenarios in lots of 100, its files in a folder of its own, and what one run of them all gives. */
internal class Book(val plan: Plan, val job: Job, val whole: List<Double>)

/** Runs [test] with a book whose plan and scenarios are saved in a folder that is deleted after it. */
internal fun withBook(test: (Book) -> Unit) = withTempDirectory("watershed-cluster") { folder: Path ->
    engine { Accounts.plan.save(folder.resolve("plan")) }
    writeNumbers(folder.resolve("scenarios"), Accounts.scenarios).shouldBeRight()
    val job = Job.read("job", folder.resolve("plan"), folder.resolve("scenarios"), Accounts.plan.inputs, 100, folder.resolve("out"))
    test(Book(Accounts.plan, job.shouldBeRight(), engine { Accounts.plan.runCpu(Accounts.scenarios) }.asList()))
}

// Eight accounts that grow at a rate and take a shock each period, and their total: built once for the suite.
private object Accounts {
    val plan: Plan = engine {
        val g = Graph()
        val rate = g.input("rate", 0.01)
        val shock = g.input("shock", 0.0)
        val worth: List<Node<Double>> = (0 until 8).map { n ->
            (1 until 5).fold(g.define("v${n}_0", (rate + 1.0) * (100.0 + n))) { value, t ->
                g.define("v${n}_$t", value * (rate + 1.0) - shock)
            }
        }
        val total = g.define("total", worth.drop(1).fold(worth[0] as Expr<Double>) { all, x -> all + x })
        g.stabilize()
        g.plan(vary = listOf(rate, shock), outputs = listOf(total, worth[3]))
    }
    val scenarios = DoubleArray(2_000) { if (it % 2 == 0) 0.0001 * it - 0.05 else 0.3 * (it % 7) }
}
