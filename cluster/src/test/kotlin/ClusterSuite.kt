import dev.surefold.Promise
import dev.surefold.assert.shouldBeRight
import dev.surefold.awaitValue
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThanOrEqual
import dev.surefold.junit.shouldHaveSize
import dev.surefold.junit.story
import dev.surefold.watershed.Expr
import dev.surefold.watershed.Graph
import dev.surefold.watershed.Node
import dev.surefold.watershed.Plan
import dev.surefold.watershed.cluster.Job
import dev.surefold.watershed.cluster.Store
import dev.surefold.watershed.minus
import dev.surefold.watershed.plus
import dev.surefold.watershed.times
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object ClusterSuite : Suite() {
    init {
        test("Lots run by several workers give what one run gives") {
            cluster { nodes ->
                story {
                    val book = book(nodes.store)
                    val jobs = Given("a coordinator and three workers") {
                        nodes.coordinator().also { nodes.workers(it, "worker-0", "worker-1", "worker-2") }
                    }
                    val report = When("it runs a job of ten lots") { jobs.run(book.job, within = 2.minutes).shouldBeRight() }
                    Then("every lot is answered with what one run gives") {
                        report.lots shouldHaveSize 10
                        jobs.answers(book.job).shouldBeRight().asList() shouldBe book.whole
                    }
                    And("the lots are spread evenly: ten over three workers is three or four each") {
                        report.lots.groupingBy { it.worker }.eachCount().values.sorted() shouldBe listOf(3, 3, 4)
                    }
                }
            }
        }

        test("A job is submitted from any node, and the coordinator runs it") {
            cluster { nodes ->
                story {
                    val book = book(nodes.store)
                    val worker = Given("a coordinator and two workers") {
                        nodes.coordinator().let { nodes.workers(it, "worker-0") }
                        nodes.worker("worker-1").also { nodes.workers(it) }
                    }
                    val report = When("a worker submits the job") { worker.run(book.job, within = 2.minutes).shouldBeRight() }
                    Then("every lot is answered with what one run gives") {
                        report.lots shouldHaveSize 10
                        worker.answers(book.job).shouldBeRight().asList() shouldBe book.whole
                    }
                }
            }
        }

        test("A job goes on when the coordinator's node stops") {
            cluster { nodes ->
                story {
                    val book = book(nodes.store)
                    val taken = Promise<Nothing, Unit>("test.lot-taken")
                    val release = Promise<Nothing, Unit>("test.lot-release")
                    val worker = Given("a coordinator, a standby for it, and two workers, one held up on its first lot") {
                        val first = nodes.coordinator()
                        nodes.standby()
                        nodes.worker("worker-held") {
                            taken.succeed(Unit)
                            release.awaitValue()
                        }
                        nodes.workers(first, "worker-0")
                        nodes.worker("worker-1").also { nodes.workers(it) }
                    }
                    val report = When("the coordinator's node stops while a job a worker submitted is running") {
                        val running = nodes.beside { worker.run(book.job, within = 2.minutes) }
                        taken.awaitValue(30.seconds) shouldBe Unit
                        nodes.stop("coordinator")
                        release.succeed(Unit)
                        running.await().shouldBeRight()
                    }
                    Then("the standby finished it, and the answers are what one run gives") {
                        report.lots shouldHaveSize 10
                        worker.answers(book.job).shouldBeRight().asList() shouldBe book.whole
                    }
                }
            }
        }

        test("A lot whose worker leaves is run by another") {
            cluster { nodes ->
                story {
                    val book = book(nodes.store)
                    val taken = Promise<Nothing, Unit>("test.lot-taken")
                    val release = Promise<Nothing, Unit>("test.lot-release")
                    val jobs = Given("two workers, and a third that takes a lot and never finishes it") {
                        nodes.coordinator().also {
                            nodes.worker("worker-stuck") {
                                taken.succeed(Unit)
                                release.awaitValue()
                            }
                            nodes.workers(it, "worker-0", "worker-1")
                        }
                    }
                    val report = When("the third leaves the cluster while the job runs") {
                        val running = nodes.beside { jobs.run(book.job, within = 2.minutes) }
                        taken.awaitValue(30.seconds) shouldBe Unit
                        nodes.stop("worker-stuck")
                        running.await().shouldBeRight()
                    }
                    Then("its lots were run by the others, and the answers are what one run gives") {
                        report.lots shouldHaveSize 10
                        report.lots.filter { it.worker.startsWith("worker-stuck") } shouldBe emptyList()
                        jobs.answers(book.job).shouldBeRight().asList() shouldBe book.whole
                    }
                }
            }
        }

        test("A lot that has run too long is given out again") {
            cluster(lotWithin = 2.seconds) { nodes ->
                story {
                    val book = book(nodes.store)
                    val jobs = Given("a coordinator that allows a lot two seconds, and a worker that hangs") {
                        nodes.coordinator().also {
                            nodes.worker("worker-stuck") { Promise<Nothing, Unit>("test.never").awaitValue() }
                            nodes.workers(it, "worker-0")
                        }
                    }
                    val report = When("it runs the job") { jobs.run(book.job, within = 2.minutes).shouldBeRight() }
                    Then("the hung worker's lots were given out again, and the answers are what one run gives") {
                        report.givenAgain shouldBeGreaterThanOrEqual 1
                        report.lots.filter { it.worker.startsWith("worker-stuck") } shouldBe emptyList()
                        jobs.answers(book.job).shouldBeRight().asList() shouldBe book.whole
                    }
                }
            }
        }
    }
}

/** A job of 1,000 scenarios in lots of 100, its files in the bucket, and what one run of them all gives. */
internal class Book(val job: Job, val whole: List<Double>)

/** Puts the book's plan and scenarios in the bucket [store] is on, and answers the job over them. */
internal fun book(store: Store): Book {
    store.put("book/plan", engine { Accounts.plan.saved() }).shouldBeRight()
    store.write("book/scenarios", Accounts.scenarios).shouldBeRight()
    val job = Job.read(store, "job", "book/plan", "book/scenarios", Accounts.plan.inputs, 100, "book/out").shouldBeRight()
    return Book(job, engine { Accounts.plan.runCpu(Accounts.scenarios) }.asList())
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
