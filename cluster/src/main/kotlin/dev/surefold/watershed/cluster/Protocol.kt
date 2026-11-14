package dev.surefold.watershed.cluster

import dev.surefold.actor.ActorRef
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut

/** The role of a node that runs lots. */
const val WORKER = "worker"

/** The role of a node that may run the coordinator: the oldest of them does. */
const val COORDINATOR = "coordinator"

/** What a lot's entity handles: what the coordinator tells it, and what its own node tells it. */
sealed interface LotTurn

/** What a lot is told. It carries a [tag], which its answers carry back, and who to answer. */
sealed interface ToLot : LotTurn {
    /** The lot's own id among the cluster's entities, which its answer carries back: whoever ran it holds it. */
    val id: String
    val tag: String
    val lot: Int
    val from: ActorRef<FromLot>
}

/** Something for a lot to do, which its node's bench does. */
sealed interface Task : ToLot

/**
 * Which worker are you on? Asked of a lot's id before it is given anything to do, so that the coordinator can
 * choose ids that spread a job's lots evenly: where an id lands is the cluster's choice, by a hash of the id.
 */
data class Where(
    override val id: String,
    override val tag: String,
    override val lot: Int,
    override val from: ActorRef<FromLot>,
) : ToLot

/**
 * Run this lot: scenarios [first] to [first] + [count] of the file at [inputs], with the plan at [plan], their
 * answers written at [answers]. Each names a file in the bucket: a plan is too big to cross as a message.
 */
data class Run(
    override val id: String,
    override val tag: String,
    override val lot: Int,
    val plan: String,
    val inputs: String,
    val first: Long,
    val count: Int,
    val answers: String,
    /** Which of the plan's outputs to write to [answers], in order; all of them if empty. */
    val columns: List<Int>,
    /** Whether the lot is to hold on to its inputs and every output, to be brought up to date later. */
    val hold: Boolean,
    override val from: ActorRef<FromLot>,
) : Task

/** Bring what this lot holds up to date with the patch at [patch], and write its [columns] at [answers] again. */
data class BringUpToDate(
    override val id: String,
    override val tag: String,
    override val lot: Int,
    val patch: String,
    val answers: String,
    val columns: List<Int>,
    override val from: ActorRef<FromLot>,
) : Task

/** What the coordinator handles: what lots and whoever submits a job tell it, and what its own node tells it. */
sealed interface CoordinatorTurn

/** What a lot tells whoever gave it something to do, about [lot] of the round tagged [tag], from [worker]. */
sealed interface FromLot : CoordinatorTurn {
    val tag: String
    val lot: Int
    val worker: String
}

/** The lot with the id [id] is on [worker]: the answer to [Where]. */
data class At(override val tag: String, override val lot: Int, override val worker: String, val id: String) : FromLot

/** The lot is in [worker]'s queue. */
data class Queued(override val tag: String, override val lot: Int, override val worker: String) : FromLot

/** [worker] has started on the lot; the time a lot is allowed runs from here. */
data class Begun(override val tag: String, override val lot: Int, override val worker: String) : FromLot

/** The lot's answers are written. It ran [on] the worker's `gpu` or `cpu`. */
data class Ran(
    override val tag: String,
    override val lot: Int,
    override val worker: String,
    /** The id of the lot that ran it, and now holds it if it was asked to. */
    val id: String,
    val on: String,
    val loadSeconds: Double,
    val runSeconds: Double,
) : FromLot

/** The lot could not be done, and [why]. */
data class Failed(override val tag: String, override val lot: Int, override val worker: String, val why: String) : FromLot

/** The lot was to be brought up to date, and holds nothing: it has moved to another worker since it ran, or never ran. */
data class NotHeld(override val tag: String, override val lot: Int, override val worker: String) : FromLot

/** A message of a [kind] this node does not know: one from a newer version. It is noted, and nothing is done. */
data class NotUnderstood(val kind: Int) : LotTurn, FromLot {
    override val tag: String get() = ""
    override val lot: Int get() = -1
    override val worker: String get() = ""
}

object FromLotCodec : MessageCodec<FromLot> {
    override fun write(message: FromLot, out: WireOut) {
        out.int(
            when (message) {
                is Queued -> 1
                is Begun -> 2
                is Ran -> 3
                is Failed -> 4
                is NotHeld -> 5
                is At -> 6
                is NotUnderstood -> message.kind
            },
        )
        out.string(message.tag)
        out.int(message.lot)
        out.string(message.worker)
        when (message) {
            is Ran -> {
                out.string(message.id)
                out.string(message.on)
                out.double(message.loadSeconds)
                out.double(message.runSeconds)
            }

            is Failed -> out.string(message.why)
            is At -> out.string(message.id)
            is Queued, is Begun, is NotHeld, is NotUnderstood -> Unit
        }
    }

    override fun read(input: WireIn): FromLot {
        val kind = input.int()
        val tag = input.string()
        val lot = input.int()
        val worker = input.string()
        return when (kind) {
            1 -> Queued(tag, lot, worker)
            2 -> Begun(tag, lot, worker)
            3 -> Ran(tag, lot, worker, input.string(), input.string(), input.double(), input.double())
            4 -> Failed(tag, lot, worker, input.string())
            5 -> NotHeld(tag, lot, worker)
            6 -> At(tag, lot, worker, input.string())
            else -> NotUnderstood(kind)
        }
    }
}

/** How a lot's messages cross between nodes. What a lot's own node tells it never crosses, and has no encoding. */
object LotCodec : MessageCodec<LotTurn> {
    private val columns = Codecs.list(Codecs.int)

    override fun write(message: LotTurn, out: WireOut) = when (message) {
        is Run -> {
            out.int(1)
            out.string(message.id)
            out.string(message.tag)
            out.int(message.lot)
            out.string(message.plan)
            out.string(message.inputs)
            out.long(message.first)
            out.int(message.count)
            out.string(message.answers)
            columns.write(message.columns, out)
            out.boolean(message.hold)
            out.ref(message.from, FromLotCodec)
        }

        is BringUpToDate -> {
            out.int(2)
            out.string(message.id)
            out.string(message.tag)
            out.int(message.lot)
            out.string(message.patch)
            out.string(message.answers)
            columns.write(message.columns, out)
            out.ref(message.from, FromLotCodec)
        }

        is Where -> {
            out.int(3)
            out.string(message.id)
            out.string(message.tag)
            out.int(message.lot)
            out.ref(message.from, FromLotCodec)
        }

        is NotUnderstood -> out.int(message.kind)
        is Finished -> out.int(0)
    }

    override fun read(input: WireIn): LotTurn = when (val kind = input.int()) {
        1 -> Run(
            input.string(), input.string(), input.int(), input.string(), input.string(), input.long(), input.int(),
            input.string(), columns.read(input), input.boolean(), input.ref(FromLotCodec),
        )

        2 -> BringUpToDate(input.string(), input.string(), input.int(), input.string(), input.string(), columns.read(input), input.ref(FromLotCodec))
        3 -> Where(input.string(), input.string(), input.int(), input.ref(FromLotCodec))
        else -> NotUnderstood(kind)
    }
}
