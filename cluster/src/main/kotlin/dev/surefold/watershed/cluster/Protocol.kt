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

/** What a worker's actor handles: what it is told from elsewhere, and what it tells itself. */
sealed interface WorkerTurn

/** What a worker is told. Each carries a [tag], which the worker's answers carry back, and who to answer. */
sealed interface ToWorker : WorkerTurn {
    val tag: String
    val lot: Int
    val from: ActorRef<FromWorker>
}

/**
 * One lot of a job's scenarios, for a worker to run: scenarios [first] to [first] + [count] of the file at
 * [inputs], with the plan at [plan], their answers to be written to [answers]. The files are named, not sent: a
 * plan is too big to cross as a message, so every node must be able to read and write the same folder.
 */
data class Assign(
    val job: String,
    override val tag: String,
    override val lot: Int,
    val plan: String,
    val inputs: String,
    val first: Long,
    val count: Int,
    val answers: String,
    /** Which of the plan's outputs to write to [answers], in order; all of them if empty. */
    val columns: List<Int>,
    /** Whether the worker is to hold on to the lot's inputs and every output, to be brought up to date later. */
    val hold: Boolean,
    override val from: ActorRef<FromWorker>,
) : ToWorker

/** A lot the worker holds, to be brought up to date with the patch at [patch], and its [columns] written again. */
data class Refresh(
    val job: String,
    override val tag: String,
    override val lot: Int,
    val patch: String,
    val answers: String,
    val columns: List<Int>,
    override val from: ActorRef<FromWorker>,
) : ToWorker

/** What the coordinator's actor handles: what workers tell it, and what this node tells it. */
sealed interface CoordinatorTurn

/** What a worker tells whoever gave it something to do. */
sealed interface FromWorker : CoordinatorTurn {
    val tag: String
    val lot: Int
    val worker: String
}

/** What a worker says, at once, to being given a lot. */
enum class Taking { Taken, Busy, NotHeld }

data class Answer(override val tag: String, override val lot: Int, override val worker: String, val taking: Taking) : FromWorker

/** A lot's answers are written. It ran [on] the worker's `gpu` or `cpu`. */
data class Ran(
    override val tag: String,
    override val lot: Int,
    override val worker: String,
    val on: String,
    val loadSeconds: Double,
    val runSeconds: Double,
) : FromWorker

/** A lot could not be run, and [why]. */
data class Failed(override val tag: String, override val lot: Int, override val worker: String, val why: String) : FromWorker

object FromWorkerCodec : MessageCodec<FromWorker> {
    override fun write(message: FromWorker, out: WireOut) {
        out.int(
            when (message) {
                is Answer -> 1
                is Ran -> 2
                is Failed -> 3
            },
        )
        out.string(message.tag)
        out.int(message.lot)
        out.string(message.worker)
        when (message) {
            is Answer -> out.int(message.taking.ordinal)
            is Ran -> {
                out.string(message.on)
                out.double(message.loadSeconds)
                out.double(message.runSeconds)
            }

            is Failed -> out.string(message.why)
        }
    }

    override fun read(input: WireIn): FromWorker {
        val kind = input.int()
        val tag = input.string()
        val lot = input.int()
        val worker = input.string()
        return when (kind) {
            1 -> Answer(tag, lot, worker, Taking.entries[input.int()])
            2 -> Ran(tag, lot, worker, input.string(), input.double(), input.double())
            3 -> Failed(tag, lot, worker, input.string())
            // A codec's read can only answer a message or throw, as Surefold's own do for a tag they do not know.
            else -> error("no message from a worker has the tag $kind")
        }
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

    override fun read(input: WireIn): ToWorker = when (val kind = input.int()) {
        1 -> Assign(
            input.string(), input.string(), input.int(), input.string(), input.string(), input.long(), input.int(),
            input.string(), columns.read(input), input.boolean(), input.ref(FromWorkerCodec),
        )

        2 -> Refresh(
            input.string(), input.string(), input.int(), input.string(), input.string(), columns.read(input),
            input.ref(FromWorkerCodec),
        )

        // As above: a codec's read answers a message or throws.
        else -> error("no message to a worker has the tag $kind")
    }
}
