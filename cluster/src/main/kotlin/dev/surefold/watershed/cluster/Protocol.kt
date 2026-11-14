package dev.surefold.watershed.cluster

import dev.surefold.actor.ActorRef
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut

/** The role a node takes to be given lots. */
const val WORKER = "worker"

/** Where every worker's actor is, on its node. */
const val WORKER_PATH = "/user/worker"

/**
 * One lot of a job's scenarios, for a worker to run: scenarios [first] to [first] + [count] of the file at
 * [inputs], with the plan at [plan], their answers to be written to [answers]. The files are named, not sent: a
 * plan is too big to cross as a message, so every node must be able to read and write the same folder.
 *
 * The worker tells [from] an [Answer] at once, that it took the lot or is busy, and an [Outcome] when the lot has
 * been run or has failed.
 */
data class Assign(
    val job: String,
    /** What its answer and outcome are to carry, to tell one run of a job from another. */
    val tag: String,
    val lot: Int,
    val plan: String,
    val inputs: String,
    val first: Long,
    val count: Int,
    val answers: String,
    /** Which of the plan's outputs to write to [answers], in order; all of them if empty. */
    val columns: List<Int>,
    /** Whether the worker is to hold on to the lot's inputs and every output, to be brought up to date later. */
    val hold: Boolean,
    val from: ActorRef<FromWorker>,
) : ToWorker

/**
 * A lot the worker holds, to be brought up to date with the patch in the file at [patch], and its [columns]
 * written to [answers] again. It is answered as a lot to run is, or with [NOT_HELD].
 */
data class Refresh(
    val job: String,
    /** What its answer and outcome are to carry, to tell one refresh from another. */
    val tag: String,
    val lot: Int,
    val patch: String,
    val answers: String,
    val columns: List<Int>,
    val from: ActorRef<FromWorker>,
) : ToWorker

/** What a worker's actor handles: what it is told from elsewhere, and what it tells itself. */
sealed interface WorkerTurn

/** What a worker is told. */
sealed interface ToWorker : WorkerTurn

/** What the coordinator's actor handles: what workers tell it, and what this node tells it. */
sealed interface CoordinatorTurn

/** What a worker tells whoever gave it something to do. */
sealed interface FromWorker : CoordinatorTurn

/** A worker's answer, at once, to being given [lot]: [BUSY], [TAKEN] or [NOT_HELD]. */
data class Answer(val tag: String, val lot: Int, val worker: String, val answer: Int) : FromWorker

/** A worker's answers to being given something to do: it is busy, it has taken it, or it does not hold the lot. */
const val BUSY = 0
const val TAKEN = 1
const val NOT_HELD = 2

/** What became of a lot: [failed] is why it could not be run, or null when its answers are written. */
data class Outcome(
    val tag: String,
    val lot: Int,
    val worker: String,
    val failed: String?,
    val loadSeconds: Double,
    val runSeconds: Double,
    val on: String,
) : FromWorker

object FromWorkerCodec : MessageCodec<FromWorker> {
    override fun write(message: FromWorker, out: WireOut) = when (message) {
        is Answer -> {
            out.int(1)
            out.string(message.tag)
            out.int(message.lot)
            out.string(message.worker)
            out.int(message.answer)
        }

        is Outcome -> {
            out.int(2)
            out.string(message.tag)
            out.int(message.lot)
            out.string(message.worker)
            out.nullable(message.failed, out::string)
            out.double(message.loadSeconds)
            out.double(message.runSeconds)
            out.string(message.on)
        }
    }

    override fun read(input: WireIn): FromWorker = when (val tag = input.int()) {
        1 -> Answer(input.string(), input.int(), input.string(), input.int())

        2 -> Outcome(
            input.string(),
            input.int(),
            input.string(),
            input.nullable(input::string),
            input.double(),
            input.double(),
            input.string(),
        )

        else -> error("no message from a worker has the tag $tag")
    }
}

object ToWorkerCodec : MessageCodec<ToWorker> {
    private val columns = Codecs.list(Codecs.int)

    override fun write(message: ToWorker, out: WireOut) = when (message) {
        is Assign -> {
            out.int(1)
            out.string(message.job)
            out.string(message.tag)
            out.int(message.lot)
            out.string(message.plan)
            out.string(message.inputs)
            out.long(message.first)
            out.int(message.count)
            out.string(message.answers)
            columns.write(message.columns, out)
            out.boolean(message.hold)
            out.ref(message.from, FromWorkerCodec)
        }

        is Refresh -> {
            out.int(2)
            out.string(message.job)
            out.string(message.tag)
            out.int(message.lot)
            out.string(message.patch)
            out.string(message.answers)
            columns.write(message.columns, out)
            out.ref(message.from, FromWorkerCodec)
        }
    }

    override fun read(input: WireIn): ToWorker = when (val tag = input.int()) {
        1 -> Assign(
            input.string(), input.string(), input.int(), input.string(), input.string(), input.long(), input.int(),
            input.string(), columns.read(input), input.boolean(), input.ref(FromWorkerCodec),
        )

        2 -> Refresh(
            input.string(), input.string(), input.int(), input.string(), input.string(), columns.read(input),
            input.ref(FromWorkerCodec),
        )

        else -> error("no message to a worker has the tag $tag")
    }
}
