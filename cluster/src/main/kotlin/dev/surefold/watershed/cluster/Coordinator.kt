package dev.surefold.watershed.cluster

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
import dev.surefold.clock
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.MemberEvent
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.flatMap
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import dev.surefold.fail.traverse
import dev.surefold.logInfo
import dev.surefold.timeoutOrNull
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * A run of many scenarios to be cut into lots: the plan at [plan], the [scenarios] in the file at [inputs], [lot]
 * of them to a lot, and a folder, [out], that each lot's answers are written to. Every node must be able to read
 * [plan] and [inputs] and write to [out] under these same paths. [Job.read] makes one from the file itself.
 */
data class Job(
    val id: String,
    val plan: Path,
    val inputs: Path,
    val scenarios: Long,
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
    val lots: Int get() = Math.toIntExact((scenarios + lot - 1) / lot)

    fun first(lot: Int): Long = lot.toLong() * this.lot

    fun count(lot: Int): Int = minOf(this.lot.toLong(), scenarios - first(lot)).toInt()

    fun answers(lot: Int): Path = out.resolve("$id-lot-$lot")

    companion object {
        /** A job over every scenario in the file at [inputs], each of them [inputsPerScenario] numbers. */
        fun read(
            id: String,
            plan: Path,
            inputs: Path,
            inputsPerScenario: Int,
            lot: Int,
            out: Path,
            columns: List<Int> = emptyList(),
            hold: Boolean = false,
        ): Either<JobFailure, Job> = attempt {
            ensure(lot > 0) { JobFailure.NotAJob("a lot needs at least one scenario, was $lot") }
            ensure(inputsPerScenario > 0) { JobFailure.NotAJob("a scenario needs at least one input") }
            val numbers = countNumbers(inputs).bind()
            ensure(numbers % inputsPerScenario == 0L) { JobFailure.NotAJob("$inputs is not a whole number of scenarios") }
            Job(id, plan, inputs, numbers / inputsPerScenario, lot, out, columns, hold)
        }
    }
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
     * Runs [job] on the cluster's workers and answers once every lot's answers are written, or with why it could
     * not be: a lot kept failing, or it was not done [within] that long. With [only], just those lots are run: the
     * ones a refresh lost, with a plan made after the change.
     */
    fun run(job: Job, within: Duration = 60.minutes, only: Collection<Int>? = null): Either<JobFailure, Report> =
        makeFolder(job.out).flatMap { ended(within) { Run(job, only?.distinct(), within, it) } }

    /**
     * Brings [job], which was run to be held, up to date with the patch in the file at [patch]: each worker runs
     * the patch on the lots it holds and writes their answers again. A lot whose worker has gone, or no longer
     * holds it, is left as it was and listed in the report's `lost`.
     */
    fun refresh(job: Job, patch: Path, within: Duration = 60.minutes): Either<JobFailure, Report> =
        ended(within) { BringUpToDate(job, patch, within, it) }

    // The actor ends a round itself when its time is up; the wait here is a little longer, in case it has stopped.
    private fun ended(within: Duration, round: (Reply<Either<JobFailure, Report>>) -> CoordinatorTurn): Either<JobFailure, Report> =
        actor.ask(within + 30.seconds, round).mapLeft { JobFailure.NoAnswer(it.toString()) }.flatMap { it }

    /** Every scenario's answers, in order, once [job] has run: its lots' files put together. */
    fun answers(job: Job, outputsPerScenario: Int): Either<JobFailure, DoubleArray> =
        (0 until job.lots)
            .traverse<JobFailure, Int, DoubleArray> { readNumbers(job.answers(it), 0, job.count(it) * outputsPerScenario).bind() }
            .map { parts -> parts.flatMap(DoubleArray::asList).toDoubleArray() }
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

private data class Run(val job: Job, val only: List<Int>?, val within: Duration, val reply: Reply<Either<JobFailure, Report>>) : CoordinatorTurn

private data class BringUpToDate(val job: Job, val patch: Path, val within: Duration, val reply: Reply<Either<JobFailure, Report>>) : CoordinatorTurn

// Its own timers: a worker that did not say whether it took a lot, a lot out too long, a worker left alone long
// enough, and a round out of time.
private data class Unanswered(val tag: String, val node: Node, val lot: Int) : CoordinatorTurn

private data class Late(val tag: String, val node: Node, val lot: Int) : CoordinatorTurn

private data class Rested(val node: Node) : CoordinatorTurn

private data class OutOfTime(val tag: String) : CoordinatorTurn

// What a worker is doing, as far as the coordinator knows.
private sealed interface Doing {
    data object Idle : Doing

    /** Offered [lot], and yet to say whether it takes it. */
    data class Offered(val lot: Int) : Doing

    data class Running(val lot: Int) : Doing

    /** Asked nothing for a moment: it was busy, or gave no answer, or may be about to go. */
    data object Resting : Doing
}

// A worker as the coordinator sees it. One that cannot be reached is kept, not usable, until it is back or gone.
private data class Hand(val ref: ActorRef<ToWorker>, val doing: Doing = Doing.Idle, val usable: Boolean = true) {
    val idle: Boolean get() = usable && doing == Doing.Idle

    val lot: Int?
        get() = when (doing) {
            is Doing.Offered -> doing.lot
            is Doing.Running -> doing.lot
            Doing.Idle, Doing.Resting -> null
        }
}

// One run of a job, or one bringing of it up to date, while it is going on.
private data class Round(
    val job: Job,
    val tag: String,
    val reply: Reply<Either<JobFailure, Report>>,
    val within: Duration,
    val lots: Int,
    val started: Instant,
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
        if (refreshing) copy(asked = asked + (node to asked[node].orEmpty().drop(1))) else copy(waiting = waiting.drop(1))

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

    /** When bringing up to date: a holder that is not among [usable] has lost what it held. */
    fun withoutHolders(usable: Set<Node>): Round = asked.entries.fold(this) { round, (at, lots) ->
        if (at in usable || lots.isEmpty()) {
            round
        } else {
            round.copy(asked = round.asked + (at to emptyList()), lost = round.lost + lots.filter { it !in round.done })
        }
    }
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

    fun with(round: Round): Table = copy(round = round)

    fun named(worker: String): Pair<Node, Hand>? = hands.entries.firstOrNull { it.key.toString() == worker }?.toPair()
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

    fun turn(ctx: Ctx<CoordinatorTurn>, table: Table, told: CoordinatorTurn): Table {
        val round = table.round
        val next = when (told) {
            is Member -> member(ctx, table, told.event)
            is Run -> begin(ctx, table, told.reply) { run(table, told) }
            is BringUpToDate -> begin(ctx, table, told.reply) { bringUpToDate(table, told) }
            is Rested -> rested(table, told.node)
            // Everything else is about one round, and means nothing once that round is over.
            is FromWorker -> if (round?.tag == told.tag) fromWorker(ctx, table, round, told) else table
            is Unanswered -> if (round?.tag == told.tag) unanswered(ctx, table, round, told) else table
            is Late -> if (round?.tag == told.tag) late(ctx, table, round, told) else table
            is OutOfTime ->
                if (round?.tag == told.tag) {
                    ended(ctx, table, round, JobFailure.OutOfTime(round.done.size, round.lots, round.within).left())
                } else {
                    table
                }
        }
        return dealt(ctx, next)
    }

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
                table.with(at, known.copy(usable = true, doing = Doing.Resting))
            }

            is MemberEvent.Up, is MemberEvent.Reachable ->
                gone(ctx, table, at).with(at, Hand(node.remote(Address(at.toString(), WORKER_PATH, 0), ToWorkerCodec)))

            is MemberEvent.Unreachable ->
                if (known == null) table else gone(ctx, table, at).with(at, known.copy(doing = Doing.Idle, usable = false))

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

    private fun rested(table: Table, at: Node): Table {
        val hand = table.hands[at]
        return if (hand?.doing == Doing.Resting) table.with(at, hand.copy(doing = Doing.Idle)) else table
    }

    // Starts the round [make] answers, or tells whoever asked for it why there is none.
    private fun begin(
        ctx: Ctx<CoordinatorTurn>,
        table: Table,
        reply: Reply<Either<JobFailure, Report>>,
        make: () -> Either<JobFailure, Round>,
    ): Table = when (val made = make()) {
        is Either.Left -> table.also { reply(made) }
        is Either.Right -> {
            ctx.timers.after("round", made.value.within, OutOfTime(made.value.tag))
            table.copy(round = made.value, rounds = table.rounds + 1)
        }
    }

    private fun run(table: Table, told: Run): Either<JobFailure, Round> = attempt {
        val job = told.job
        val lots = told.only ?: (0 until job.lots).toList()
        ensure(table.round == null) { JobFailure.Busy(table.round?.job?.id.orEmpty()) }
        ensure(lots.all { it in 0 until job.lots }) { JobFailure.NoSuchLots(lots, job.lots) }
        Round(job, tag(table, job), told.reply, told.within, lots.size, clock.get().now(), waiting = lots)
    }

    private fun bringUpToDate(table: Table, told: BringUpToDate): Either<JobFailure, Round> = attempt {
        val job = told.job
        ensure(table.round == null) { JobFailure.Busy(table.round?.job?.id.orEmpty()) }
        val holding = table.holders[job.id]
        ensure(holding != null) { JobFailure.NotHeld(job.id) }
        val (held, lost) = (0 until job.lots).partition { it in holding }
        Round(
            job, tag(table, job), told.reply, told.within, job.lots, clock.get().now(),
            patch = told.patch, asked = held.groupBy { holding.getValue(it) }, lost = lost.toSet(),
        )
    }

    // Each round has a tag of its own, so that a late answer from an earlier run of the same job is not counted.
    private fun tag(table: Table, job: Job): String = "${job.id}#${table.rounds + 1}"

    private fun fromWorker(ctx: Ctx<CoordinatorTurn>, table: Table, round: Round, told: FromWorker): Table {
        val (at, hand) = table.named(told.worker) ?: return table
        return when (told) {
            is Answer -> answer(ctx, table, round, at, hand, told)
            is Ran -> freed(ctx, table, at, hand, told.lot)
                .with(round.answered(LotRun(told.lot, told.worker, told.on, told.loadSeconds, told.runSeconds)))

            is Failed -> failed(ctx, freed(ctx, table, at, hand, told.lot), round, told)
        }
    }

    private fun answer(ctx: Ctx<CoordinatorTurn>, table: Table, round: Round, at: Node, hand: Hand, told: Answer): Table {
        val offered = hand.doing == Doing.Offered(told.lot)
        // An answer after it was given up on: if it took the lot and nobody else has it yet, it is still its.
        val late = !offered && told.taking == Taking.Taken && hand.lot == null && !round.refreshing && told.lot in round.waiting
        if (offered) ctx.timers.cancel("offer" to at)
        return when {
            late || (offered && told.taking == Taking.Taken) -> {
                ctx.timers.cancel("rest" to at)
                ctx.timers.after("lot" to at, lotWithin, Late(round.tag, at, told.lot))
                table.with(at, hand.copy(doing = Doing.Running(told.lot))).with(round.copy(waiting = round.waiting - told.lot))
            }

            !offered -> table
            told.taking == Taking.NotHeld ->
                table.with(at, hand.copy(doing = Doing.Idle)).with(round.copy(lost = round.lost + told.lot))

            else -> refused(ctx, table, round, at, hand, told.lot)
        }
    }

    private fun unanswered(ctx: Ctx<CoordinatorTurn>, table: Table, round: Round, told: Unanswered): Table {
        val hand = table.hands[told.node]
        return if (hand?.doing == Doing.Offered(told.lot)) refused(ctx, table, round, told.node, hand, told.lot) else table
    }

    // Busy, or no answer: the lot is offered again, and this worker is asked nothing for a moment.
    private fun refused(ctx: Ctx<CoordinatorTurn>, table: Table, round: Round, at: Node, hand: Hand, lot: Int): Table {
        logInfo("coordinator: $at did not take lot $lot")
        ctx.timers.after("rest" to at, 2.seconds, Rested(at))
        return table.with(at, hand.copy(doing = Doing.Resting)).with(round.unoffered(at, lot))
    }

    private fun late(ctx: Ctx<CoordinatorTurn>, table: Table, round: Round, told: Late): Table {
        val hand = table.hands[told.node]
        if (hand?.doing != Doing.Running(told.lot)) return table
        logInfo("coordinator: lot ${told.lot} has been with ${told.node} longer than $lotWithin")
        // It may still be busy with it: leave it be for a while.
        ctx.timers.after("rest" to told.node, 10.seconds, Rested(told.node))
        return table.with(told.node, hand.copy(doing = Doing.Resting)).with(round.back(told.lot))
    }

    // The worker has said how [lot] went, so it no longer has it.
    private fun freed(ctx: Ctx<CoordinatorTurn>, table: Table, at: Node, hand: Hand, lot: Int): Table {
        if (hand.lot != lot) return table
        listOf("offer", "lot").forEach { ctx.timers.cancel(it to at) }
        return table.with(at, hand.copy(doing = Doing.Idle))
    }

    private fun failed(ctx: Ctx<CoordinatorTurn>, table: Table, round: Round, told: Failed): Table {
        if (told.lot in round.done) return table
        val tried = (round.tries[told.lot] ?: 0) + 1
        val noted = round.copy(
            tries = round.tries + (told.lot to tried),
            failures = round.failures + "lot ${told.lot} on ${told.worker}: ${told.why}",
        )
        return when {
            // Brought up to date by nobody else: the lot is left as it was, and reported lost.
            round.refreshing -> table.with(noted.copy(lost = noted.lost + told.lot))
            tried >= attempts ->
                ended(ctx, table, noted, JobFailure.LotKeptFailing(told.lot, tried, told.worker, told.why).left())

            else -> table.with(noted.back(told.lot))
        }
    }

    // After every turn: ends a round that is over, and otherwise offers a lot to each idle worker.
    private fun dealt(ctx: Ctx<CoordinatorTurn>, table: Table): Table {
        val usable = table.hands.filterValues { it.usable }.keys
        val round = table.round?.withoutHolders(usable) ?: return table
        if (round.over) return finished(ctx, table, round)
        return table.hands.entries.fold(table.with(round)) { dealing, (at, hand) -> offered(ctx, dealing, at, hand) }
    }

    private fun offered(ctx: Ctx<CoordinatorTurn>, table: Table, at: Node, hand: Hand): Table {
        val round = table.round
        val lot = round?.next(at)
        if (round == null || lot == null || !hand.idle) return table
        hand.ref.tell(offer(ctx, round, lot))
        ctx.timers.after("offer" to at, answerWithin, Unanswered(round.tag, at, lot))
        return table.with(at, hand.copy(doing = Doing.Offered(lot))).with(round.out(at))
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

    private fun finished(ctx: Ctx<CoordinatorTurn>, table: Table, round: Round): Table {
        val before = table.holders[round.job.id].orEmpty()
        val nodes = table.hands.keys.associateBy { it.toString() }
        val holding = when {
            round.refreshing -> before - round.lost
            // Whoever ran a lot now holds it; a lot run by a worker that has since gone is held by nobody.
            round.job.hold -> round.done.values.fold(before) { held, run ->
                nodes[run.worker]?.let { held + (run.lot to it) } ?: (held - run.lot)
            }

            else -> before
        }
        val took = java.time.Duration.between(round.started, clock.get().now()).toKotlinDuration()
        val report = Report(round.done.values.sortedBy { it.lot }, round.givenAgain, round.failures, took, round.lost.sorted())
        val ended = ended(ctx, table, round, report.right())
        return if (holding.isEmpty()) ended else ended.copy(holders = ended.holders + (round.job.id to holding))
    }

    // The round is over, one way or the other: whoever asked for it is told, no worker has a lot of it, and none
    // of its timers is left.
    private fun ended(ctx: Ctx<CoordinatorTurn>, table: Table, round: Round, how: Either<JobFailure, Report>): Table {
        round.reply(how)
        ctx.timers.cancel("round")
        table.hands.keys.forEach { at -> listOf("offer", "lot").forEach { ctx.timers.cancel(it to at) } }
        val idle = table.hands.mapValues { (_, hand) -> if (hand.lot == null) hand else hand.copy(doing = Doing.Idle) }
        return table.copy(hands = idle, round = null)
    }
}
