package dev.surefold.watershed.cluster

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Reply
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
 * The worker answers [accepted] at once, true if it took the lot and false if it is busy, and tells [done] when
 * the lot has been run or has failed.
 */
data class Assign(
    val job: String,
    val lot: Int,
    val plan: String,
    val inputs: String,
    val first: Long,
    val count: Int,
    val answers: String,
    val accepted: Reply<Boolean>,
    val done: ActorRef<Outcome>,
)

/** What became of a lot: [failed] is why it could not be run, or null when its answers are written. */
data class Outcome(
    val job: String,
    val lot: Int,
    val worker: String,
    val failed: String?,
    val loadSeconds: Double,
    val runSeconds: Double,
    val on: String,
)

object OutcomeCodec : MessageCodec<Outcome> {
    override fun write(message: Outcome, out: WireOut) {
        out.string(message.job)
        out.int(message.lot)
        out.string(message.worker)
        out.nullable(message.failed, out::string)
        out.double(message.loadSeconds)
        out.double(message.runSeconds)
        out.string(message.on)
    }

    override fun read(input: WireIn): Outcome = Outcome(
        input.string(),
        input.int(),
        input.string(),
        input.nullable(input::string),
        input.double(),
        input.double(),
        input.string(),
    )
}

object AssignCodec : MessageCodec<Assign> {
    override fun write(message: Assign, out: WireOut) {
        out.string(message.job)
        out.int(message.lot)
        out.string(message.plan)
        out.string(message.inputs)
        out.long(message.first)
        out.int(message.count)
        out.string(message.answers)
        out.reply(message.accepted, Codecs.boolean)
        out.ref(message.done, OutcomeCodec)
    }

    override fun read(input: WireIn): Assign = Assign(
        input.string(),
        input.int(),
        input.string(),
        input.string(),
        input.long(),
        input.int(),
        input.string(),
        input.reply(Codecs.boolean),
        input.ref(OutcomeCodec),
    )
}
