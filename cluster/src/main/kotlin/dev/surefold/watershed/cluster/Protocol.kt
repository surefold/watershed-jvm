package dev.surefold.watershed.cluster

import dev.surefold.actor.Reply
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.kotlinx.Kotlinx
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.time.Duration

/** The role of a node that runs lots. */
const val WORKER = "worker"

/** The role of a node that may run the coordinator: the oldest of them does. */
const val COORDINATOR = "coordinator"

// Everything that crosses between nodes is a class of plain data, written by kotlinx.serialization through
// Surefold's `Kotlinx` codecs: the classes are the only definition of the protocol. No message carries who to
// answer. A lot answers the coordinator, which every node can reach by name, wherever it is now.

/** What a lot's entity handles: what the coordinator tells it, and what its own node tells it. */
sealed interface LotTurn

/** What a lot is told. It carries a [tag], which its answers carry back. */
sealed interface ToLot : LotTurn {
    /** The lot's own id among the cluster's entities, which its answer carries back: whoever ran it holds it. */
    val id: String
    val tag: String
    val lot: Int
}

/** Something for a lot to do, which its node's bench does. */
sealed interface Task : ToLot

/**
 * Which worker are you on? Asked of a lot's id before it is given anything to do, so that the coordinator can
 * choose ids that spread a job's lots evenly: where an id lands is the cluster's choice, by a hash of the id.
 */
@Serializable
data class Where(override val id: String, override val tag: String, override val lot: Int) : ToLot

/**
 * Run this lot: scenarios [first] to [first] + [count] of the file at [inputs], with the plan at [plan], their
 * answers written at [answers]. Each names a file in the bucket: a plan is too big to cross as a message.
 */
@Serializable
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
    val columns: List<Int> = emptyList(),
    /** Whether the lot is to hold on to its inputs and every output, to be brought up to date later. */
    val hold: Boolean = false,
) : Task

/** Bring what this lot holds up to date with the patch at [patch], and write its [columns] at [answers] again. */
@Serializable
data class BringUpToDate(
    override val id: String,
    override val tag: String,
    override val lot: Int,
    val patch: String,
    val answers: String,
    val columns: List<Int> = emptyList(),
) : Task

/** What the coordinator handles: what crosses to it from other nodes, and what its own node tells it. */
sealed interface CoordinatorTurn

/** What is told to the coordinator from another node, as plain data. */
sealed interface Told : CoordinatorTurn

/** What a lot tells the coordinator, about [lot] of the round tagged [tag], from [worker]. */
sealed interface FromLot : Told {
    val tag: String
    val lot: Int
    val worker: String
}

/** The lot with the id [id] is on [worker]: the answer to [Where]. */
@Serializable
data class At(override val tag: String, override val lot: Int, override val worker: String, val id: String) : FromLot

/** The lot is in [worker]'s queue. */
@Serializable
data class Queued(override val tag: String, override val lot: Int, override val worker: String) : FromLot

/** [worker] has started on the lot; the time a lot is allowed runs from here. */
@Serializable
data class Begun(override val tag: String, override val lot: Int, override val worker: String) : FromLot

/** The lot's answers are written. It ran [on] the worker's `gpu` or `cpu`. */
@Serializable
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
@Serializable
data class Failed(override val tag: String, override val lot: Int, override val worker: String, val why: String) : FromLot

/** The lot was to be brought up to date, and holds nothing: it has moved to another worker since it ran, or never ran. */
@Serializable
data class NotHeld(override val tag: String, override val lot: Int, override val worker: String) : FromLot

/** Start the round [request] of [job]: run [only] those lots (all, if empty), or bring them up to date with [patch]. */
@Serializable
data class Start(
    val request: String,
    val job: Job,
    val only: List<Int> = emptyList(),
    val patch: String? = null,
    val within: Duration,
) : Told

/** How does the round [request] stand? The one message with an answer to wait for. */
data class Progress(val request: String, val reply: Reply<Standing>) : CoordinatorTurn

/** How a round stands. */
@Serializable
sealed interface Standing {
    /** This coordinator knows no such round: it has taken over from one that did, or the round was never started. */
    @Serializable
    data object Unknown : Standing

    @Serializable
    data class Running(val answered: Int, val lots: Int) : Standing

    @Serializable
    data class Done(val report: Report) : Standing

    @Serializable
    data class Stopped(val why: JobFailure) : Standing
}

// What Progress asks, as the data that crosses beside its reply.
@Serializable
private data class Asking(val request: String)

// A tag is part of what is sent, so one is never given to another class.
private val toLot = Kotlinx.oneOf<ToLot> {
    message<Where>(1)
    message<Run>(2)
    message<BringUpToDate>(3)
}.messages()

private val told = Kotlinx.oneOf<Told> {
    message<Start>(1)
    message<At>(2)
    message<Queued>(3)
    message<Begun>(4)
    message<Ran>(5)
    message<Failed>(6)
    message<NotHeld>(7)
}.messages()

private val asked: MessageCodec<Progress> = Kotlinx.asked(
    request = serializer<Asking>(),
    answers = Kotlinx.codec(serializer<Standing>()),
    make = { asking, reply -> Progress(asking.request, reply) },
    requestOf = { Asking(it.request) },
    replyOf = { it.reply },
)

/** How a lot's messages cross. What a lot's own node tells it never crosses. */
object LotCodec : MessageCodec<LotTurn> {
    override fun write(message: LotTurn, out: WireOut) {
        if (message is ToLot) toLot.write(message, out)
    }

    override fun read(input: WireIn): LotTurn = toLot.read(input)
}

/** How what is told to the coordinator crosses: plain data, or the one ask. Its own timers never cross. */
object CoordinatorCodec : MessageCodec<CoordinatorTurn> {
    override fun write(message: CoordinatorTurn, out: WireOut) {
        out.boolean(message is Progress)
        if (message is Progress) asked.write(message, out)
        if (message is Told) told.write(message, out)
    }

    override fun read(input: WireIn): CoordinatorTurn = if (input.boolean()) asked.read(input) else told.read(input)
}
