package dev.surefold.watershed.cluster

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Behaviour
import dev.surefold.actor.Ctx
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.logInfo
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import kotlin.time.Duration

// The coordinator is one actor in the whole cluster, a singleton, on the oldest node that may run it. It tells
// each lot of a job what to do and hears how it went. Which worker a lot is on is the cluster's business: a lot is
// reached by its id, wherever it is.
//
// It keeps nothing that cannot be had again. If its node goes, the next coordinator knows no round; whoever
// submitted the round tells it to start again, under the same tag, and every lot that had already run answers
// that it has, without running again.

// Its own timers: tell the lots not yet answered again, a lot has run too long, and the round is out of time.
private data class TellAgain(val request: String) : CoordinatorTurn

private data class Late(val request: String, val lot: Int, val id: String) : CoordinatorTurn

private data class OutOfTime(val request: String) : CoordinatorTurn

// A lot not yet answered: the id it is being tried or was told under, and what has been heard of it since.
internal data class Out(
    val id: String,
    val attempt: Int = 0,
    // Whether it has been told to run under this id, or is still being asked where the id would put it.
    val placed: Boolean = false,
    // How many ids have been tried for this placing.
    val asked: Int = 0,
    val worker: String? = null,
    val begun: Boolean = false,
    // The workers it has failed on, which it is not given to again.
    val avoid: Set<String> = emptySet(),
)

// One run of a job, or one bringing of it up to date, while it is going on.
internal data class Round(
    val start: Start,
    val started: Instant,
    val lots: Int,
    val out: Map<Int, Out>,
    val done: Map<Int, LotRun> = emptyMap(),
    // Which lot ran each one, and so holds it.
    val ranBy: Map<Int, String> = emptyMap(),
    val tries: Map<Int, Int> = emptyMap(),
    val lost: Set<Int> = emptySet(),
    val givenAgain: Int = 0,
    val failures: List<String> = emptyList(),
    // Workers a lot has run too long on, and has not been heard from since: nothing is left waiting on one.
    val stuck: Set<String> = emptySet(),
) {
    val job: Job get() = start.job

    val refreshing: Boolean get() = start.patch != null

    fun standing(): Standing = Standing.Running(done.size + lost.size, lots)
}

// Everything the coordinator knows.
internal data class Table(
    val round: Round? = null,
    // How the last few rounds ended, for whoever submitted them to ask.
    val ended: Map<String, Either<JobFailure, Report>> = emptyMap(),
    // For each job run to be held: the id of the lot that holds each of its lots.
    val holders: Map<String, Map<Int, String>> = emptyMap(),
)

private const val REMEMBERED = 16

// How many ids are tried for a lot before it is given to whichever worker the last one lands on: with every
// worker stuck, or one worker only, asking again would go on for ever.
private const val MOST_ASKED = 24

// A lot's id among the cluster's entities. A lot given out again has another, and so, most likely, another worker.
private fun id(job: Job, lot: Int, attempt: Int): String = "${job.id}/$lot/$attempt"

/**
 * The coordinator, as the cluster's singleton runs it: it reaches a lot by its id through [lots], and counts the
 * workers there are with [workers]. It is given both, so that a test can run it alone, with Surefold's test actors
 * standing in for the lots and the test's own clock for its timers.
 */
fun coordinating(
    lots: (String) -> ActorRef<LotTurn>,
    workers: () -> Int,
    patience: Patience,
): Behaviour<CoordinatorTurn, *, Nothing> {
    val dealer = Dealer(lots, workers, patience)
    return behaviour(Table()) { ctx, table, told -> become(dealer.turn(ctx, table, told)) }
}

// The coordinator's turns: what each message does to the table.
private class Dealer(private val lots: (String) -> ActorRef<LotTurn>, private val workers: () -> Int, private val patience: Patience) {
    fun turn(ctx: Ctx<CoordinatorTurn>, table: Table, told: CoordinatorTurn): Table {
        val round = table.round
        return when (told) {
            is Start -> start(ctx, table, told)
            is Progress -> table.also { told.reply(standing(table, told.request)) }
            // Everything else is about one round, and means nothing once that round is over.
            is FromLot -> if (round?.start?.request == told.tag) settled(ctx, table, fromLot(ctx, round, told)) else table
            is TellAgain -> table.also { if (round?.start?.request == told.request) round.out.forEach { (lot, out) -> tell(round, lot, out) } }
            is Late -> if (round?.start?.request == told.request) settled(ctx, table, late(ctx, round, told)) else table
            is OutOfTime ->
                if (round?.start?.request == told.request) {
                    ended(ctx, table, round, JobFailure.OutOfTime(round.done.size, round.lots, round.start.within).left())
                } else {
                    table
                }

            else -> table
        }
    }

    private fun standing(table: Table, request: String): Standing = when {
        table.round?.start?.request == request -> table.round.standing()
        else -> table.ended[request]?.fold({ Standing.Stopped(it) }, { Standing.Done(it) }) ?: Standing.Unknown
    }

    private fun start(ctx: Ctx<CoordinatorTurn>, table: Table, told: Start): Table {
        val job = told.job
        val wanted = told.only.ifEmpty { (0 until job.lots).toList() }
        val holding = table.holders[job.id]
        return when {
            // Told again to start what is running, or has ended: whoever submitted it heard nothing for a while.
            table.round?.start?.request == told.request || told.request in table.ended -> table
            table.round != null -> remembered(table, told.request, JobFailure.Busy(table.round.job.id).left())
            !wanted.all { it in 0 until job.lots } -> remembered(table, told.request, JobFailure.NoSuchLots(wanted, job.lots).left())
            else -> {
                // Brought up to date, a lot is told under the id that ran it: only that one holds it. Run, it is
                // first asked where its id would put it.
                val out = wanted.associateWith { lot ->
                    if (told.patch != null) Out(holding?.get(lot) ?: id(job, lot, 0), placed = true) else Out(id(job, lot, 0))
                }
                val round = Round(told, clock.get().now().toKotlinInstant(), wanted.size, out)
                ctx.timers.every("again", patience.tellAgainEvery, TellAgain(told.request))
                ctx.timers.after("round", told.within, OutOfTime(told.request))
                out.forEach { (lot, to) -> tell(round, lot, to) }
                table.copy(round = round)
            }
        }
    }

    // Tells a lot what is wanted of it now: where its id would put it, or, once it is placed, to get on with it.
    private fun tell(round: Round, lot: Int, out: Out) {
        val job = round.job
        val patch = round.start.patch
        val id = out.id
        lots(id).tell(
            if (!out.placed) {
                Where(id, round.start.request, lot)
            } else if (patch != null) {
                BringUpToDate(id, round.start.request, lot, patch, job.answers(lot), job.columns)
            } else {
                Run(
                    id, round.start.request, lot, job.plan, job.inputs, job.first(lot), job.count(lot), job.answers(lot),
                    job.columns, job.hold,
                )
            },
        )
    }

    private fun fromLot(ctx: Ctx<CoordinatorTurn>, round: Round, told: FromLot): Either<JobFailure, Round> {
        val out = round.out[told.lot]
        return when (told) {
            // Heard from where it is now: any clock on where it was no longer counts.
            is At -> (if (out == null || out.placed || out.id != told.id) round else placing(ctx, round, told.lot, out, told.worker)).right()
            is Queued -> round.heard(ctx, told.lot) { it.copy(worker = told.worker, begun = false) }.right()
            is Begun -> {
                if (out != null) ctx.timers.after("lot" to told.lot, patience.lotWithin, Late(round.start.request, told.lot, out.id))
                round.copy(out = round.out.changed(told.lot) { it.copy(worker = told.worker, begun = true) }).right()
            }

            is Ran -> {
                ctx.timers.cancel("lot" to told.lot)
                val run = LotRun(told.lot, told.worker, told.on, told.loadSeconds, told.runSeconds)
                round.copy(
                    // A worker that finishes a lot is not stuck.
                    stuck = round.stuck - told.worker,
                    out = round.out - told.lot,
                    done = if (told.lot in round.done) round.done else round.done + (told.lot to run),
                    ranBy = round.ranBy + (told.lot to told.id),
                    lost = round.lost - told.lot,
                ).right()
            }

            is NotHeld -> if (out == null) round.right() else round.copy(out = round.out - told.lot, lost = round.lost + told.lot).right()
            is Failed -> if (out == null) round.right() else failed(ctx, round, out, told)
        }
    }

    private fun Round.heard(ctx: Ctx<CoordinatorTurn>, lot: Int, change: (Out) -> Out): Round {
        ctx.timers.cancel("lot" to lot)
        return copy(out = out.changed(lot, change))
    }

    private fun Map<Int, Out>.changed(lot: Int, change: (Out) -> Out): Map<Int, Out> = this[lot]?.let { this + (lot to change(it)) } ?: this

    private fun failed(ctx: Ctx<CoordinatorTurn>, round: Round, out: Out, told: Failed): Either<JobFailure, Round> {
        ctx.timers.cancel("lot" to told.lot)
        val tried = (round.tries[told.lot] ?: 0) + 1
        val noted = round.copy(
            tries = round.tries + (told.lot to tried),
            failures = round.failures + "lot ${told.lot} on ${told.worker}: ${told.why}",
        )
        return when {
            // Only the lot that held it could bring it up to date: it is left as it was, and reported lost.
            round.refreshing -> noted.copy(out = noted.out - told.lot, lost = noted.lost + told.lot).right()
            tried >= patience.attempts -> JobFailure.LotKeptFailing(told.lot, tried, told.worker, told.why).left()
            else -> again(ctx, noted, told.lot, out.copy(avoid = out.avoid + told.worker)).right()
        }
    }

    // A lot that has run too long, and with it every lot waiting behind it on the same worker.
    private fun late(ctx: Ctx<CoordinatorTurn>, round: Round, told: Late): Either<JobFailure, Round> {
        val out = round.out[told.lot]
        if (out == null || out.id != told.id || !out.begun) return round.right()
        logInfo("coordinator: lot ${told.lot} has been with ${out.worker} longer than ${patience.lotWithin}")
        val stuck = round.out.filter { (lot, other) -> lot == told.lot || (other.worker == out.worker && !other.begun) }
        return if (round.refreshing) {
            round.copy(out = round.out - stuck.keys, lost = round.lost + stuck.keys).right()
        } else {
            stuck.entries.fold(round.copy(stuck = round.stuck + listOfNotNull(out.worker))) { next, (lot, other) -> again(ctx, next, lot, other) }.right()
        }
    }

    // Gives a lot out again: it is placed afresh, under another id, on a worker it has not failed on.
    private fun again(ctx: Ctx<CoordinatorTurn>, round: Round, lot: Int, out: Out): Round {
        val next = Out(id(round.job, lot, out.attempt + 1), out.attempt + 1, avoid = out.avoid)
        tell(round, lot, next)
        return round.copy(out = round.out + (lot to next), givenAgain = round.givenAgain + 1)
    }

    // An id would put the lot on [worker]. It is told to run there if that keeps the lots even over the workers
    // and the worker is neither stuck nor one it failed on; otherwise the next id is asked about.
    private fun placing(ctx: Ctx<CoordinatorTurn>, round: Round, lot: Int, out: Out, worker: String): Round {
        val unusable = round.stuck + out.avoid
        // The lots each usable worker has or has had, and the share that one more would bring a worker to.
        val placed = (round.out.values.filter { it.placed }.map { it.worker } + round.done.values.map { it.worker }).filter { it !in unusable }
        val usable = (workers() - unusable.size).coerceAtLeast(1)
        val share = (placed.size + usable) / usable
        val fits = worker !in unusable && placed.count { it == worker } < share
        val next = if (fits || out.asked >= MOST_ASKED) {
            out.copy(placed = true, worker = worker)
        } else {
            out.copy(id = id(round.job, lot, out.attempt + 1), attempt = out.attempt + 1, asked = out.asked + 1)
        }
        tell(round, lot, next)
        return round.copy(out = round.out + (lot to next))
    }

    // After anything a lot said: the round goes on, has failed, or is over.
    private fun settled(ctx: Ctx<CoordinatorTurn>, table: Table, how: Either<JobFailure, Round>): Table = when (how) {
        is Either.Left -> table.round?.let { ended(ctx, table, it, how) } ?: table
        is Either.Right -> {
            val round = how.value
            if (round.out.isNotEmpty()) {
                table.copy(round = round)
            } else {
                val took = clock.get().now().toKotlinInstant() - round.started
                val report = Report(round.done.values.sortedBy { it.lot }, round.givenAgain, round.failures, took, round.lost.sorted())
                val before = table.holders[round.job.id].orEmpty()
                val holding = when {
                    round.refreshing -> before - round.lost
                    round.job.hold -> before + round.ranBy
                    else -> before
                }
                ended(ctx, table.copy(holders = table.holders + (round.job.id to holding)), round, report.right())
            }
        }
    }

    // The round is over, one way or the other: how is kept for whoever submitted it, and its timers stop.
    private fun ended(ctx: Ctx<CoordinatorTurn>, table: Table, round: Round, how: Either<JobFailure, Report>): Table {
        ctx.timers.cancel("again")
        ctx.timers.cancel("round")
        round.out.keys.forEach { ctx.timers.cancel("lot" to it) }
        return remembered(table.copy(round = null), round.start.request, how)
    }

    private fun remembered(table: Table, request: String, how: Either<JobFailure, Report>): Table =
        table.copy(ended = (table.ended + (request to how)).entries.toList().takeLast(REMEMBERED).associate { it.key to it.value })
}
