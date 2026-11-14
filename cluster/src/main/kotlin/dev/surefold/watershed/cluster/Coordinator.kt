package dev.surefold.watershed.cluster

import dev.surefold.Promise
import dev.surefold.Scope
import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.Ctx
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.RemoteNode
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.MemberEvent
import dev.surefold.fail.Either
import dev.surefold.logInfo
import dev.surefold.timeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A run of many scenarios to be cut into lots: the plan at [plan], the scenarios in the file at [inputs], [lot]
 * scenarios to a lot, and a folder, [out], that each lot's answers are written to. Every node must be able to
 * read [plan] and [inputs] and write to [out] under these same paths.
 */
data class Job(
    val id: String,
    val plan: Path,
    val inputs: Path,
    val inputsPerScenario: Int,
    val lot: Int,
    val out: Path,
    /** Which of the plan's outputs are written as a scenario's answers, in order; all of them if empty. */
    val columns: List<Int> = emptyList(),
    /**
     * Whether each worker holds on to the lots it runs, every output of every scenario, so that
     * [Coordinator.refresh] can bring them up to date. The plan is then a `Kept`'s, and [columns] its outputs.
     */
    val hold: Boolean = false,
) {
    val scenarios: Long = countNumbers(inputs) / inputsPerScenario
    val lots: Int = Math.toIntExact((scenarios + lot - 1) / lot)

    init {
        require(lot > 0) { "a lot needs at least one scenario, was $lot" }
        require(countNumbers(inputs) % inputsPerScenario == 0L) { "$inputs is not a whole number of scenarios" }
    }

    fun first(lot: Int): Long = lot.toLong() * this.lot

    fun count(lot: Int): Int = minOf(this.lot.toLong(), scenarios - first(lot)).toInt()

    fun answers(lot: Int): Path = out.resolve("$id-lot-$lot")
}

/** One lot as it was run, for a report. */
data class LotRun(val lot: Int, val worker: String, val on: String, val loadSeconds: Double, val runSeconds: Double)

/**
 * How a job or a refresh went: who ran each lot, how many lots were given out again, why any lot failed on a
 * worker, and how long it all took.
 */
data class Report(
    val lots: List<LotRun>,
    val givenAgain: Int,
    val failures: List<String>,
    val took: Duration,
    /**
     * After [Coordinator.refresh]: the lots that could not be brought up to date, because the worker that held
     * them has gone or no longer holds them. Their answers are as they were; run them again with
     * [Coordinator.run] and a plan made after the change.
     */
    val lost: List<Int> = emptyList(),
)

/** A job that could not be finished. */
class JobFailed(why: String) : RuntimeException(why)

/**
 * EXPERIMENTAL. Gives a job's lots to the cluster's workers and waits for their answers.
 *
 * It keeps every lot not yet answered. Each idle worker is given one, and a lot whose worker leaves the cluster,
 * cannot be reached, fails it or takes longer than `lotWithin` is given to another. A lot run twice gives the same
 * answers both times, so nothing is done to stop that. A lot that has failed `attempts` times fails the job.
 *
 * One job runs at a time. The coordinator is a member like any other and holds nothing a worker needs: if it
 * stops, the job stops with it and is started again from the beginning.
 *
 * All of it is one actor's state, changed a message at a time: a member coming or going, a worker's answer, a
 * timer running out. This class only asks that actor, so it can be called from any thread.
 */
class Coordinator internal constructor(
    private val actor: ActorRef<CoordinatorTurn>,
    private val count: Shared<Int>,
) {
    /** How many workers the cluster has now. */
    fun workers(): Int = count.peek()

    /** Waits until there are [count] workers, or [within] has passed; true if there are. */
    fun awaitWorkers(count: Int, within: Duration): Boolean =
        timeoutOrNull(within) { this.count.awaitUntil { it >= count } } != null

    /**
     * Runs [job] on the cluster's workers and returns once every lot's answers are written, or throws [JobFailed]
     * if a lot keeps failing or it is not done [within] that long. With [only], just those lots are run: the ones a
     * refresh lost, with a plan made after the change.
     */
    fun run(job: Job, within: Duration = 60.minutes, only: Collection<Int>? = null): Report {
        Files.createDirectories(job.out)
        return ended(within) { Run(job, only?.distinct(), within, it) }
    }

    /**
     * Brings [job], which was run to be held, up to date with the patch in the file at [patch]: each worker runs
     * the patch on the lots it holds and writes their answers again. A lot whose worker has gone, or no longer
     * holds it, is left as it was and listed in the report's `lost`.
     */
    fun refresh(job: Job, patch: Path, within: Duration = 60.minutes): Report =
        ended(within) { BringUpToDate(job, patch, within, it) }

    // The actor ends a round itself when its time is up; the wait here is a little longer, in case it has stopped.
    private fun ended(within: Duration, round: (Reply<Ended>) -> CoordinatorTurn): Report =
        when (val ended = actor.ask<CoordinatorTurn, Ended>(within + 30.seconds, round)) {
            is Either.Left -> throw JobFailed("the coordinator gave no answer: ${ended.value}")
            is Either.Right -> when (val how = ended.value) {
                is Ended.Done -> how.report
                is Ended.Failed -> throw JobFailed(how.why)
            }
        }

    /** Every scenario's answers, in order, once [job] has run: its lots' files put together. */
    fun answers(job: Job, outputsPerScenario: Int): DoubleArray {
        val all = DoubleArray(Math.toIntExact(job.scenarios * outputsPerScenario))
        var at = 0
        for (lot in 0 until job.lots) {
            val part = readNumbers(job.answers(lot), 0, job.count(lot) * outputsPerScenario)
            part.copyInto(all, at)
            at += part.size
        }
        return all
    }
}

/**
 * EXPERIMENTAL. Makes this node the coordinator of [cluster]: it hears of every worker that joins or goes, and
 * [Coordinator.run] gives a job's lots to them.
 */
fun Scope<*>.coordinator(
    node: RemoteNode,
    cluster: Cluster,
    lotWithin: Duration = 30.minutes,
    attempts: Int = 3,
): Coordinator {
    val count = Shared(0, "watershed-workers")
    val dealer = Dealer(node, cluster, lotWithin, attempts, count)
    val actor = spawn("coordinator", behaviour<CoordinatorTurn, Table>(Table()) { ctx, table, told -> become(dealer.turn(ctx, table, told)) })
    node.expose<FromWorker>(actor, FromWorkerCodec)
    // The cluster tells members to an actor of their own type; this one passes each on.
    cluster.subscribe(spawn("coordinator-members", behaviour<MemberEvent, Unit>(Unit) { _, _, event -> stay().also { actor.tell(Member(event)) } }))
    return Coordinator(actor, count)
}

// What this node tells the coordinator's actor, beside what workers tell it.
private data class Member(val event: MemberEvent) : CoordinatorTurn

private data class Run(val job: Job, val only: List<Int>?, val within: Duration, val reply: Reply<Ended>) : CoordinatorTurn

private data class BringUpToDate(val job: Job, val patch: Path, val within: Duration, val reply: Reply<Ended>) : CoordinatorTurn

// Its own timers: a worker that did not say whether it took a lot, a lot out too long, a worker left alone long
// enough, and a round out of time.
private data class Unanswered(val tag: String, val node: Node, val lot: Int) : CoordinatorTurn

private data class Late(val tag: String, val node: Node, val lot: Int) : CoordinatorTurn

private data class Rested(val node: Node) : CoordinatorTurn

private data class OutOfTime(val tag: String) : CoordinatorTurn

internal sealed interface Ended {
    data class Done(val report: Report) : Ended

    data class Failed(val why: String) : Ended
}

// A worker as the coordinator sees it: the lot it has been offered, and whether it has said it took it.
private data class Hand(
    val ref: ActorRef<ToWorker>,
    val usable: Boolean = true,
    val lot: Int? = null,
    val taken: Boolean = false,
    val resting: Boolean = false,
) {
    val idle: Boolean get() = usable && lot == null && !resting

    fun free(): Hand = copy(lot = null, taken = false)
}

// One run of a job, or one bringing of it up to date, while it is going on.
private data class Round(
    val job: Job,
    val tag: String,
    val reply: Reply<Ended>,
    val within: Duration,
    val lots: Int,
    val started: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
    // The patch, when lots are being brought up to date and not run.
    val patch: Path? = null,
    // A run's lots not out with any worker; any idle worker may take the first.
    val waiting: List<Int> = emptyList(),
    // When bringing up to date: the lots each holder is still to be asked for, since only it can do them.
    val asked: Map<Node, List<Int>> = emptyMap(),
    val done: Map<Int, LotRun> = emptyMap(),
    val tries: Map<Int, Int> = emptyMap(),
    val lost: Set<Int> = emptySet(),
    val givenAgain: Int = 0,
    val failures: List<String> = emptyList(),
) {
    val refreshing: Boolean get() = patch != null

    val over: Boolean get() = done.size + lost.size >= lots

    fun next(node: Node): Int? = if (refreshing) asked[node]?.firstOrNull() else waiting.firstOrNull()

    fun out(node: Node): Round =
        if (refreshing) copy(asked = asked + (node to asked.getValue(node).drop(1))) else copy(waiting = waiting.drop(1))

    /** [lot] was offered to [node], which did not take it: it is to be offered again. */
    fun unoffered(node: Node, lot: Int): Round = when {
        lot in done -> this
        refreshing -> copy(asked = asked + (node to listOf(lot) + asked[node].orEmpty()))
        lot in waiting -> this
        else -> copy(waiting = listOf(lot) + waiting)
    }

    /** [lot] will not be answered by the worker that had it: another is given it, or, if only that one held it, it is lost. */
    fun back(lot: Int): Round = when {
        lot in done -> this
        refreshing -> copy(lost = lost + lot)
        lot in waiting -> this
        else -> copy(waiting = listOf(lot) + waiting, givenAgain = givenAgain + 1)
    }

    fun answered(run: LotRun): Round =
        copy(done = if (run.lot in done) done else done + (run.lot to run), waiting = waiting - run.lot, lost = lost - run.lot)
}

// Everything the coordinator's actor knows.
private data class Table(
    val hands: Map<Node, Hand> = emptyMap(),
    val round: Round? = null,
    // For each job run to be held: the node that holds each lot.
    val holders: Map<String, Map<Int, Node>> = emptyMap(),
    val rounds: Int = 0,
) {
    fun with(node: Node, hand: Hand): Table = copy(hands = hands + (node to hand))

    fun named(worker: String): Pair<Node, Hand>? = hands.entries.firstOrNull { it.key.toString() == worker }?.toPair()

    fun round(tag: String): Round? = round?.takeIf { it.tag == tag }
}

// The coordinator's turns: what each message does to the table.
private class Dealer(
    private val node: RemoteNode,
    private val cluster: Cluster,
    private val lotWithin: Duration,
    private val attempts: Int,
    private val count: Shared<Int>,
) {
    private val answerWithin = 5.seconds

    fun turn(ctx: Ctx<CoordinatorTurn>, table: Table, told: CoordinatorTurn): Table = dealt(
        ctx,
        when (told) {
            is Member -> member(ctx, table, told.event)
            is Run -> run(ctx, table, told)
            is BringUpToDate -> bringUpToDate(ctx, table, told)
            is Answer -> answer(ctx, table, told)
            is Outcome -> outcome(ctx, table, told)
            is Unanswered -> table.hands[told.node]
                ?.takeIf { table.round(told.tag) != null && it.lot == told.lot && !it.taken }
                ?.let { refused(ctx, table, told.node, it, told.lot) }
                ?: table

            is Late -> table.hands[told.node]
                ?.takeIf { table.round(told.tag) != null && it.lot == told.lot && it.taken }
                ?.let {
                    // It may still be busy with it: leave it be for a while.
                    logInfo("coordinator: lot ${told.lot} has been with ${told.node} longer than $lotWithin")
                    ctx.timers.after("rest" to told.node, 10.seconds, Rested(told.node))
                    table.with(told.node, it.free().copy(resting = true)).copy(round = table.round?.back(told.lot))
                }
                ?: table

            is Rested -> table.hands[told.node]?.let { table.with(told.node, it.copy(resting = false)) } ?: table
            is OutOfTime -> table.round(told.tag)
                ?.let { failed(ctx, table, "${it.done.size} of ${it.lots} lots were answered after ${it.within}") }
                ?: table
        },
    )

    private fun member(ctx: Ctx<CoordinatorTurn>, table: Table, event: MemberEvent): Table {
        val member = event.member
        if (WORKER !in member.roles || cluster.isSelf(member)) return table
        val at = member.node
        val known = table.hands[at]
        logInfo("coordinator: ${event::class.simpleName} $at" + (known?.lot?.let { ", which had lot $it" } ?: ""))
        val next = when (event) {
            // A member being downed is told reachable an instant before it is told downed. It is asked nothing for
            // a moment, so that a lot is not offered to a worker that is about to be gone.
            is MemberEvent.Reachable if known != null -> {
                ctx.timers.after("rest" to at, 1.seconds, Rested(at))
                table.with(at, known.copy(usable = true, resting = true))
            }

            is MemberEvent.Up, is MemberEvent.Reachable ->
                gone(ctx, table, at).with(at, Hand(node.remote(Address(at.toString(), WORKER_PATH, 0), ToWorkerCodec)))

            is MemberEvent.Unreachable ->
                if (known == null) table else gone(ctx, table, at).with(at, known.free().copy(usable = false))

            is MemberEvent.Downed, is MemberEvent.Removed -> gone(ctx, table, at)
        }
        val usable = next.hands.values.count { it.usable }
        count.update { usable }
        return next
    }

    // A worker that has gone, or cannot be reached: the lot it had goes back, and its timers stop.
    private fun gone(ctx: Ctx<CoordinatorTurn>, table: Table, at: Node): Table {
        listOf("offer", "lot", "rest").forEach { ctx.timers.cancel(it to at) }
        val lot = table.hands[at]?.lot
        return table.copy(hands = table.hands - at, round = if (lot == null) table.round else table.round?.back(lot))
    }

    private fun run(ctx: Ctx<CoordinatorTurn>, table: Table, told: Run): Table {
        val job = told.job
        val lots = told.only ?: (0 until job.lots).toList()
        val why = when {
            table.round != null -> "one job at a time: ${table.round.job.id} is still running"
            !lots.all { it in 0 until job.lots } -> "the job has lots 0 to ${job.lots - 1}, not all of $lots"
            else -> null
        }
        if (why != null) return table.also { told.reply(Ended.Failed(why)) }
        return begun(ctx, table, Round(job, "${job.id}#${table.rounds + 1}", told.reply, told.within, lots.size, waiting = lots))
    }

    private fun bringUpToDate(ctx: Ctx<CoordinatorTurn>, table: Table, told: BringUpToDate): Table {
        val job = told.job
        val holding = table.holders[job.id]
        val why = when {
            table.round != null -> "one job at a time: ${table.round.job.id} is still running"
            holding == null -> "no run of ${job.id} is held"
            else -> null
        }
        if (why != null || holding == null) return table.also { told.reply(Ended.Failed(why ?: "no run of ${job.id} is held")) }
        val (held, lost) = (0 until job.lots).partition { it in holding }
        val round = Round(
            job, "${job.id}#${table.rounds + 1}", told.reply, told.within, job.lots,
            patch = told.patch, asked = held.groupBy { holding.getValue(it) }, lost = lost.toSet(),
        )
        return begun(ctx, table, round)
    }

    private fun begun(ctx: Ctx<CoordinatorTurn>, table: Table, round: Round): Table {
        ctx.timers.after("round", round.within, OutOfTime(round.tag))
        return table.copy(round = round, rounds = table.rounds + 1)
    }

    private fun answer(ctx: Ctx<CoordinatorTurn>, table: Table, told: Answer): Table {
        val round = table.round(told.tag) ?: return table
        val (at, hand) = table.named(told.worker) ?: return table
        if (hand.lot != told.lot || hand.taken) {
            // It answered after it was given up on. If it took the lot and nobody else has it yet, it is still its.
            if (told.answer != TAKEN || hand.lot != null || round.refreshing || told.lot !in round.waiting) return table
            ctx.timers.cancel("rest" to at)
            ctx.timers.after("lot" to at, lotWithin, Late(round.tag, at, told.lot))
            return table.with(at, hand.copy(lot = told.lot, taken = true, resting = false))
                .copy(round = round.copy(waiting = round.waiting - told.lot))
        }
        ctx.timers.cancel("offer" to at)
        return when (told.answer) {
            TAKEN -> {
                ctx.timers.after("lot" to at, lotWithin, Late(round.tag, at, told.lot))
                table.with(at, hand.copy(taken = true))
            }

            NOT_HELD -> table.with(at, hand.free()).copy(round = round.copy(lost = round.lost + told.lot))
            else -> refused(ctx, table, at, hand, told.lot)
        }
    }

    // Busy, or no answer: the lot is offered again, and this worker is asked nothing for a moment.
    private fun refused(ctx: Ctx<CoordinatorTurn>, table: Table, at: Node, hand: Hand, lot: Int): Table {
        logInfo("coordinator: $at did not take lot $lot")
        ctx.timers.after("rest" to at, 2.seconds, Rested(at))
        return table.with(at, hand.free().copy(resting = true)).copy(round = table.round?.unoffered(at, lot))
    }

    private fun outcome(ctx: Ctx<CoordinatorTurn>, table: Table, told: Outcome): Table {
        val round = table.round(told.tag) ?: return table
        val freed = table.named(told.worker)?.takeIf { it.second.lot == told.lot }?.let { (at, hand) ->
            listOf("offer", "lot").forEach { ctx.timers.cancel(it to at) }
            table.with(at, hand.free())
        } ?: table
        if (told.failed == null) {
            val run = LotRun(told.lot, told.worker, told.on, told.loadSeconds, told.runSeconds)
            return freed.copy(round = round.answered(run))
        }
        if (told.lot in round.done) return freed
        val tried = (round.tries[told.lot] ?: 0) + 1
        val noted = round.copy(
            tries = round.tries + (told.lot to tried),
            failures = round.failures + "lot ${told.lot} on ${told.worker}: ${told.failed}",
        )
        return when {
            // Brought up to date by nobody else: the lot is left as it was, and reported lost.
            round.refreshing -> freed.copy(round = noted.copy(lost = noted.lost + told.lot))
            tried >= attempts ->
                failed(ctx, freed, "lot ${told.lot} failed $tried times, last on ${told.worker}: ${told.failed}")

            else -> freed.copy(round = noted.back(told.lot))
        }
    }

    // After every turn: ends a round that is over, and otherwise offers a lot to each idle worker.
    private fun dealt(ctx: Ctx<CoordinatorTurn>, table: Table): Table {
        var round = table.round ?: return table
        if (round.refreshing) {
            // A holder that has gone has lost what it held.
            for ((at, lots) in round.asked) {
                if (lots.isNotEmpty() && table.hands[at]?.usable != true) {
                    round = round.copy(asked = round.asked + (at to emptyList()), lost = round.lost + lots.filter { it !in round.done })
                }
            }
        }
        if (round.over) return finished(ctx, table.copy(round = round))
        var hands = table.hands
        for ((at, hand) in table.hands) {
            val lot = round.next(at)?.takeIf { hand.idle } ?: continue
            round = round.out(at)
            hand.ref.tell(offer(ctx, round, lot))
            ctx.timers.after("offer" to at, answerWithin, Unanswered(round.tag, at, lot))
            hands = hands + (at to hand.copy(lot = lot))
        }
        return table.copy(hands = hands, round = round)
    }

    private fun offer(ctx: Ctx<CoordinatorTurn>, round: Round, lot: Int): ToWorker {
        val job = round.job
        val answers = job.answers(lot).toString()
        return if (round.patch != null) {
            Refresh(job.id, round.tag, lot, round.patch.toString(), answers, job.columns, ctx.self)
        } else {
            Assign(
                job.id, round.tag, lot, job.plan.toString(), job.inputs.toString(), job.first(lot), job.count(lot),
                answers, job.columns, job.hold, ctx.self,
            )
        }
    }

    private fun finished(ctx: Ctx<CoordinatorTurn>, table: Table): Table {
        val round = table.round ?: return table
        val before = table.holders[round.job.id]
        val nodes = table.hands.keys.associateBy { it.toString() }
        val holding = when {
            round.refreshing -> before.orEmpty() - round.lost
            // Whoever ran a lot now holds it; a lot run by a worker that has since gone is held by nobody.
            round.job.hold -> round.done.values.fold(before.orEmpty()) { held, run ->
                nodes[run.worker]?.let { held + (run.lot to it) } ?: (held - run.lot)
            }

            else -> before
        }
        val report = Report(
            round.done.values.sortedBy { it.lot }, round.givenAgain, round.failures, round.started.elapsedNow(),
            round.lost.sorted(),
        )
        round.reply(Ended.Done(report))
        val cleared = cleared(ctx, table)
        return if (holding == null) cleared else cleared.copy(holders = cleared.holders + (round.job.id to holding))
    }

    private fun failed(ctx: Ctx<CoordinatorTurn>, table: Table, why: String): Table {
        table.round?.reply?.invoke(Ended.Failed(why))
        return cleared(ctx, table)
    }

    // The round is over: no worker has a lot of it, and none of its timers is left.
    private fun cleared(ctx: Ctx<CoordinatorTurn>, table: Table): Table {
        ctx.timers.cancel("round")
        table.hands.keys.forEach { at -> listOf("offer", "lot").forEach { ctx.timers.cancel(it to at) } }
        return table.copy(hands = table.hands.mapValues { it.value.free() }, round = null)
    }
}
