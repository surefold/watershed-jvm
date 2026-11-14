import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.watershed.cluster.Job
import dev.surefold.watershed.cluster.JobFailure
import dev.surefold.watershed.cluster.LotRun
import dev.surefold.watershed.cluster.Report
import dev.surefold.watershed.cluster.Standing
import dev.surefold.watershed.cluster.Start
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class)
object ProtocolSuite : Suite() {
    init {
        // What the cluster tests do not send: every way a round can stand, and every reason it can stop for.
        testEach("How a round stands crosses as it was", standings, named = { it::class.simpleName.orEmpty() + " " + it.hashCode() }) { standing ->
            val bytes = ProtoBuf.encodeToByteArray(serializer<Standing>(), standing)
            ProtoBuf.decodeFromByteArray(serializer<Standing>(), bytes) shouldBe standing
        }

        test("A job to start crosses as it was, with its lists empty or not") {
            listOf(Start("r", job, within = 5.minutes), Start("r", job.copy(columns = listOf(3, 7), hold = true), listOf(1, 4), "patch", 2.seconds)).forEach {
                ProtoBuf.decodeFromByteArray(serializer<Start>(), ProtoBuf.encodeToByteArray(serializer<Start>(), it)) shouldBe it
            }
        }
    }
}

private val job = Job("book", "book/plan", "book/paths", 10_000, 1_250, "book/out")

private val standings: List<Standing> = listOf(
    Standing.Unknown,
    Standing.Running(3, 8),
    Standing.Done(Report()),
    Standing.Done(Report(listOf(LotRun(0, "w", "gpu", 1.5, 2.5)), 2, listOf("lot 1 on w: no"), 3.seconds, listOf(4, 5))),
) + listOf(
    JobFailure.NotAJob("no"),
    JobFailure.File("book/plan", "gone"),
    JobFailure.Busy("other"),
    JobFailure.NoSuchLots(listOf(9), 8),
    JobFailure.NoSuchLots(emptyList(), 8),
    JobFailure.NotHeld("book"),
    JobFailure.LotKeptFailing(2, 3, "w", "out of memory"),
    JobFailure.OutOfTime(7, 8, 10.minutes),
    JobFailure.NoAnswer("quiet"),
).map { Standing.Stopped(it) }
