import dev.surefold.Shared
import dev.surefold.actor.TestActor
import dev.surefold.actor.TestActors
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.testActors
import dev.surefold.assert.shouldBeRight
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.watershed.cluster.At
import dev.surefold.watershed.cluster.Begun
import dev.surefold.watershed.cluster.BringUpToDate
import dev.surefold.watershed.cluster.CoordinatorTurn
import dev.surefold.watershed.cluster.Failed
import dev.surefold.watershed.cluster.Job
import dev.surefold.watershed.cluster.JobFailure
import dev.surefold.watershed.cluster.LotTurn
import dev.surefold.watershed.cluster.NotHeld
import dev.surefold.watershed.cluster.Patience
import dev.surefold.watershed.cluster.Progress
import dev.surefold.watershed.cluster.Queued
import dev.surefold.watershed.cluster.Ran
import dev.surefold.watershed.cluster.Run
import dev.surefold.watershed.cluster.Standing
import dev.surefold.watershed.cluster.Start
import dev.surefold.watershed.cluster.ToLot
import dev.surefold.watershed.cluster.Where
import dev.surefold.watershed.cluster.coordinating
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// The coordinator alone, on Surefold's test actors: no nodes, no threads and no waiting. One actor stands in for
// every lot and records what it is told; the test answers as the lots would, and moves the clock itself.
object CoordinatorSuite : Suite() {
    init {
        test("A lot is asked where its id would land, and runs where that keeps the lots even") {
            floor(workers = 2) {
                coordinator.send(Start("r", job, within = 1.hours))
                told() shouldBe (0..3).map { Where("book/$it/0", "r", it) }

                // Every id lands on the first worker. One lot of four is its share so far; the rest are asked again.
                (0..3).forEach { coordinator.send(At("r", it, "w1", "book/$it/0")) }
                told() shouldBe listOf(run(0, "book/0/0")) + (1..3).map { Where("book/$it/1", "r", it) }

                // The second worker takes its share, and then each takes one more.
                coordinator.send(At("r", 1, "w2", "book/1/1"))
                coordinator.send(At("r", 2, "w2", "book/2/1"))
                coordinator.send(At("r", 3, "w2", "book/3/1"))
                told() shouldBe listOf(run(1, "book/1/1"), run(2, "book/2/1"), Where("book/3/2", "r", 3))
                coordinator.send(At("r", 3, "w1", "book/3/2"))
                told() shouldBe listOf(run(3, "book/3/2"))
            }
        }

        test("A round ends when every lot has run, and says who ran each") {
            floor(workers = 1) {
                val ids = started()
                standing() shouldBe Standing.Running(0, 4)
                ids.forEach { (lot, id) -> coordinator.send(Ran("r", lot, "w1", id, "gpu", 1.0, 2.0)) }
                standing().shouldBeInstanceOf<Standing.Done> { done ->
                    done.report.lots.map { it.lot to it.worker } shouldBe (0..3).map { it to "w1" }
                    done.report.givenAgain shouldBe 0
                }
            }
        }

        test("What has not been answered is told again, and what has is not") {
            floor(workers = 1) {
                val ids = started()
                coordinator.send(Ran("r", 0, "w1", ids.getValue(0), "gpu", 1.0, 2.0))
                advance(5.seconds)
                told() shouldBe (1..3).map { run(it, ids.getValue(it)) }
            }
        }

        test("A lot that has run too long is given out again, with every lot waiting behind it") {
            floor(workers = 2) {
                coordinator.send(Start("r", job.copy(scenarios = 200), within = 1.hours))
                (0..1).forEach { coordinator.send(At("r", it, "w1", "book/$it/0")) }
                coordinator.send(At("r", 1, "w1", "book/1/1"))
                told()
                // Both are on the first worker after all: the second landed there twice. One starts, and hangs.
                coordinator.send(At("r", 1, "w2", "book/1/2"))
                told() shouldBe listOf(run(1, "book/1/2", count = 100))
                coordinator.send(Queued("r", 0, "w1"))
                coordinator.send(Begun("r", 0, "w1"))
                advance(10.minutes)
                // Asked where a new id would land; and asked again since, as anything unanswered is.
                told().filterIsInstance<Where>().distinct() shouldBe listOf(Where("book/0/1", "r", 0))

                // It is not given back to the worker it hung on.
                coordinator.send(At("r", 0, "w1", "book/0/1"))
                told() shouldBe listOf(Where("book/0/2", "r", 0))
                coordinator.send(At("r", 0, "w2", "book/0/2"))
                told() shouldBe listOf(run(0, "book/0/2", count = 100))
            }
        }

        test("A lot that fails on three workers fails the job, with the last reason") {
            floor(workers = 4) {
                coordinator.send(Start("r", job.copy(scenarios = 100), within = 1.hours))
                listOf("w1", "w2", "w3").forEachIndexed { attempt, worker ->
                    coordinator.send(At("r", 0, worker, "book/0/$attempt"))
                    coordinator.send(Failed("r", 0, worker, "out of memory on $worker"))
                }
                standing() shouldBe Standing.Stopped(JobFailure.LotKeptFailing(0, 3, "w3", "out of memory on w3"))
            }
        }

        test("A round that runs out of time stops, and says how far it got") {
            floor(workers = 1) {
                val ids = started()
                coordinator.send(Ran("r", 2, "w1", ids.getValue(2), "gpu", 1.0, 2.0))
                advance(1.hours)
                standing() shouldBe Standing.Stopped(JobFailure.OutOfTime(1, 4, 1.hours))
            }
        }

        test("One job runs at a time, and a round nobody started is not known") {
            floor(workers = 1) {
                started()
                coordinator.send(Start("other", job.copy(id = "second"), within = 1.hours))
                standing("other") shouldBe Standing.Stopped(JobFailure.Busy("book"))
                standing("never") shouldBe Standing.Unknown
                // Told to start what is already running, it does nothing: whoever submitted it heard nothing for a while.
                told()
                coordinator.send(Start("r", job, within = 1.hours))
                told() shouldBe emptyList()
            }
        }

        test("Brought up to date, a lot is told under the id that ran it, and one that holds nothing is lost") {
            floor(workers = 1) {
                val held = job.copy(hold = true)
                coordinator.send(Start("r", held, within = 1.hours))
                (0..3).forEach { coordinator.send(At("r", it, "w1", "book/$it/0")) }
                // The third ran under another id, on a worker it was moved to.
                (0..3).forEach { coordinator.send(Ran("r", it, "w1", if (it == 2) "book/2/5" else "book/$it/0", "gpu", 1.0, 2.0)) }
                told()

                coordinator.send(Start("again", held, patch = "book/patch", within = 1.hours))
                told() shouldBe (0..3).map { BringUpToDate(if (it == 2) "book/2/5" else "book/$it/0", "again", it, "book/patch", "out/book-lot-$it") }
                (0..2).forEach { coordinator.send(Ran("again", it, "w1", "x", "gpu", 0.0, 0.1)) }
                coordinator.send(NotHeld("again", 3, "w1"))
                standing("again").shouldBeInstanceOf<Standing.Done> { done ->
                    done.report.lots.map { it.lot } shouldBe listOf(0, 1, 2)
                    done.report.lost shouldBe listOf(3)
                }
            }
        }
    }
}

// Four lots of a hundred scenarios.
private val job = Job("book", "plan", "inputs", 400, 100, "out")

private val patience = Patience(lotWithin = 10.minutes, attempts = 3, tellAgainEvery = 5.seconds)

private fun run(lot: Int, id: String, count: Int = 100) = Run(id, "r", lot, "plan", "inputs", lot * 100L, count, "out/book-lot-$lot")

// The coordinator, and the one actor that stands in for every lot.
private class Floor(
    private val actors: TestActors,
    val coordinator: TestActor<CoordinatorTurn, *, Nothing>,
    private val lots: TestActor<LotTurn, List<LotTurn>, Nothing>,
) {
    // How many of the messages to the lots the test has already looked at.
    private val seen = Shared(0, "test.seen")

    /** What the lots have been told since this was last asked. */
    fun told(): List<ToLot> = lots.state.drop(seen.modify { lots.state.size to it }).filterIsInstance<ToLot>()

    fun advance(by: Duration) = actors.advance(by)

    fun standing(request: String = "r"): Standing = coordinator.ask<Standing> { Progress(request, it) }.shouldBeRight()

    /** Starts the job's four lots on one worker, and answers the id each was told to run under. */
    fun started(): Map<Int, String> {
        coordinator.send(Start("r", job, within = 1.hours))
        // One worker has every lot, so each id is taken as it comes.
        (0..3).forEach { coordinator.send(At("r", it, "w1", "book/$it/0")) }
        return told().filterIsInstance<Run>().associate { it.lot to it.id }
    }
}

private fun floor(workers: Int, test: Floor.() -> Unit) = testActors {
    val lots = spawn("lots", behaviour<LotTurn, List<LotTurn>>(emptyList()) { _, heard, told -> become(heard + told) })
    val coordinator = spawn("coordinator", coordinating({ lots }, { workers }, patience))
    Floor(this, coordinator, lots).test()
}
