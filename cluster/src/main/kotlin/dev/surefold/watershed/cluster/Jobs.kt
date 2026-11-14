package dev.surefold.watershed.cluster

import dev.surefold.Schedule
import dev.surefold.Scope
import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.actor.ask
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Node
import dev.surefold.actor.spawn
import dev.surefold.clock
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.MemberEvent
import dev.surefold.cluster.sharding
import dev.surefold.cluster.singleton
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.fail
import dev.surefold.fail.flatMap
import dev.surefold.fail.fold
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.fail.right
import dev.surefold.fail.traverse
import dev.surefold.retrying
import dev.surefold.timeoutOrNull
import dev.surefold.watershed.Gpu
import dev.surefold.watershed.Refused
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A run of many scenarios to be cut into lots: the plan at [plan], the [scenarios] in the file at [inputs], [lot]
 * of them to a lot, and a prefix, [out], that each lot's answers are written under. Each names a file in the
 * bucket every node reaches. [Job.read] makes one from the scenarios' file itself.
 */
data class Job(
    val id: String,
    val plan: String,
    val inputs: String,
    val scenarios: Long,
    val lot: Int,
    val out: String,
    /** Which of the plan's outputs are written as a scenario's answers, in order; all of them if empty. */
    val columns: List<Int> = emptyList(),
    /**
     * Whether each lot holds on to what it ran, every output of every scenario, so that [Jobs.refresh] can bring
     * it up to date. The plan is then a `Kept`'s, and [columns] its outputs.
     */
    val hold: Boolean = false,
) {
    val lots: Int get() = ((scenarios + lot - 1) / lot).toInt()

    fun first(lot: Int): Long = lot.toLong() * this.lot

    fun count(lot: Int): Int = minOf(this.lot.toLong(), scenarios - first(lot)).toInt()

    /** Where a lot's answers are written. */
    fun answers(lot: Int): String = "$out/$id-lot-$lot"

    companion object {
        /** A job over every scenario in the file at [inputs], each of them [inputsPerScenario] numbers. */
        fun read(
            store: Store,
            id: String,
            plan: String,
            inputs: String,
            inputsPerScenario: Int,
            lot: Int,
            out: String,
            columns: List<Int> = emptyList(),
            hold: Boolean = false,
        ): Either<JobFailure, Job> = attempt {
            ensure(lot > 0) { JobFailure.NotAJob("a lot needs at least one scenario, was $lot") }
            ensure(inputsPerScenario > 0) { JobFailure.NotAJob("a scenario needs at least one input") }
            val numbers = store.count(inputs).bind()
            ensure(numbers % inputsPerScenario == 0L) { JobFailure.NotAJob("$inputs is not a whole number of scenarios") }
            ensure((numbers / inputsPerScenario + lot - 1) / lot <= Int.MAX_VALUE) { JobFailure.NotAJob("too many lots: make them bigger") }
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
     * After [Jobs.refresh]: the lots that could not be brought up to date, because the worker that held them has
     * gone. Their answers are as they were; run them again with [Jobs.run] and a plan made after the change.
     */
    val lost: List<Int> = emptyList(),
)

/** What this node does as a worker: which GPU it uses, and how much of it. */
class Working(
    /** True to need a GPU, false to use the CPU, and null to use a GPU if there is one. */
    val gpu: Boolean? = null,
    /** How many bytes of the GPU's memory it may use, for a worker that shares its GPU with others. */
    val gpuBytes: Long? = null,
    /** Called with each lot's number just before it is run; tests use it to hold a worker up. */
    val beforeRun: (Int) -> Unit = {},
)

/** How long the coordinator waits before it does something about a lot. */
class Patience(
    /** How long a lot may run, from when its worker starts on it, before it is given to another. */
    val lotWithin: Duration = 30.minutes,
    /** How many workers a lot may fail on before the job fails. */
    val attempts: Int = 3,
    /** How often lots not yet answered are told again, which is how a lot whose worker went reaches its next one. */
    val tellAgainEvery: Duration = 5.seconds,
    /** How often whoever submitted a job asks how it stands. */
    val askEvery: Duration = 1.seconds,
    /**
     * How long the workers must have been the same before a job is started. A lot is on the worker the cluster's
     * sharding gives it, and a worker that joins is given some of the others' lots; until every node has heard of
     * every other, where a lot is has not settled.
     */
    val settled: Duration = 5.seconds,
)

/**
 * EXPERIMENTAL. Joins this node to the cluster's jobs, and answers the handle a job is submitted through. Every
 * node calls it, with the same [patience]; the roles a node joined the cluster with say what it does:
 *
 * - a [WORKER] runs lots. Each lot is an entity of the cluster's sharding, so the cluster itself decides which
 *   worker has it, spreads the lots over the workers, and moves a lot to another when its worker goes. This node's
 *   lots run one at a time, on its GPU or its CPU as [working] says;
 * - a [COORDINATOR] may run the coordinator, the one actor in the cluster that knows how a job stands. The oldest
 *   such node runs it, and when that node goes the next one does. A job is submitted from any node.
 *
 * Join the cluster with `ready = Ready.WhenTold` and call `admit()` after this, so that no lot is sent to a node
 * before it can take one.
 */
fun Scope<*>.jobs(cluster: Cluster, store: Store, working: Working? = null, patience: Patience = Patience()): Either<NoGpu, Jobs> =
    (if (working == null) card(false) else card(working.gpu)).map { card ->
        val self = cluster.self.toString()
        // Every node has a bench, since every node makes the same sharding; only a worker's is ever given a lot.
        val bench = spawn("bench", bench(Rig(self, store, card, working?.gpuBytes, working?.beforeRun ?: {})))
        // A lot holds what it ran in memory, so it is never put to sleep for being idle.
        val lots = cluster.sharding("watershed-lot", LotCodec, passivateAfter = 3650.days, role = WORKER) { _ -> lot(self, bench) }
        val workers = Shared(Workers(0, now()), "watershed-workers")
        cluster.subscribe(spawn("workers", counting(cluster, workers)))
        val coordinator = cluster.singleton("watershed-coordinator", CoordinatorCodec, role = COORDINATOR) {
            coordinating({ id -> lots.entity(id) }, { workers.peek().count }, patience)
        }
        Jobs(coordinator, store, workers, self, patience)
    }

// The GPU to run on, or none: asked for one outright, not finding it is a failure; left to choose, it is the CPU.
private fun card(gpu: Boolean?): Either<NoGpu, Gpu?> = when (gpu) {
    false -> null.right()
    true -> attempt<Refused, Gpu?> { Gpu.open() }.mapLeft { NoGpu(it.why) }
    null -> attempt<Refused, Gpu?> { Gpu.open() }.fold({ null }, { it }).right()
}

private fun now(): Instant = clock.get().now().toKotlinInstant()

// How many workers this node can reach, and since when it has been that many.
internal data class Workers(val count: Int, val since: Instant)

// Counts the workers this node can reach, for whoever waits for enough of them before submitting a job.
private fun counting(cluster: Cluster, workers: Shared<Workers>) = behaviour<MemberEvent, Set<Node>>(emptySet()) { _, known, event ->
    val member = event.member
    val next = when {
        WORKER !in member.roles -> known
        event is MemberEvent.Up || event is MemberEvent.Reachable -> known + member.node
        else -> known - member.node
    }
    if (next != known) workers.update { Workers(next.size, now()) }
    become(next)
}

/**
 * EXPERIMENTAL. Where a job is submitted and waited for. The job itself is the coordinator's, wherever in the
 * cluster that is: this tells it to start, and asks how it stands until it has ended. If the coordinator moves to
 * another node meanwhile, the next one is told to start the same round again, and the lots that had already run
 * answer it at once without running again.
 */
class Jobs internal constructor(
    private val coordinator: ActorRef<CoordinatorTurn>,
    private val store: Store,
    private val workers: Shared<Workers>,
    private val self: String,
    private val patience: Patience,
) {
    private val rounds = Shared(0, "watershed-rounds")

    /** How many workers this node can reach now. */
    fun workers(): Int = workers.peek().count

    /**
     * Waits until there are [count] workers and they have been the same for as long as [Patience.settled] asks, or
     * [within] has passed; true if there are.
     */
    fun awaitWorkers(count: Int, within: Duration): Boolean = timeoutOrNull(within) { settled(count) } != null

    private tailrec fun settled(count: Int) {
        val seen = workers.awaitUntil { it.count >= count }
        val wait = seen.since + patience.settled - now()
        if (!wait.isPositive()) return
        clock.get().sleep(wait)
        settled(count)
    }

    /**
     * Runs [job] on the cluster's workers and answers once every lot's answers are written, or with why it could
     * not be: a lot kept failing, or it was not done [within] that long. With [only], just those lots are run: the
     * ones a refresh lost, with a plan made after the change.
     */
    fun run(job: Job, within: Duration = 60.minutes, only: Collection<Int> = emptyList()): Either<JobFailure, Report> =
        submitted(Start(round(job), job, only.distinct(), null, within))

    /**
     * Brings [job], which was run to be held, up to date with the patch at [patch] in the bucket: each lot runs the
     * patch on what it holds and writes its answers again. A lot whose worker has gone since it ran holds nothing:
     * it is left as it was and listed in the report's `lost`.
     */
    fun refresh(job: Job, patch: String, within: Duration = 60.minutes): Either<JobFailure, Report> =
        submitted(Start(round(job), job, emptyList(), patch, within))

    /** Every scenario's answers, in order, once [job] has run: its lots' files put together. */
    fun answers(job: Job): Either<JobFailure, DoubleArray> =
        (0 until job.lots).traverse<JobFailure, Int, DoubleArray> { store.read(job.answers(it)).bind() }
            .map { parts -> parts.flatMap(DoubleArray::asList).toDoubleArray() }

    // A name for one round that no other has: it tags every message of the round, here and on every lot.
    private fun round(job: Job): String = "${job.id}@$self#${rounds.modify { it + 1 to it + 1 }}"

    private fun submitted(start: Start): Either<JobFailure, Report> {
        coordinator.tell(start)
        // Asked every so often until it has ended. The coordinator ends a round itself when its time is up; this
        // gives up a little after that, in case it cannot say so.
        val asking = Schedule.spaced<Waiting>(patience.askEvery) zipLeft Schedule.upTo(start.within + 30.seconds)
        return asking.retrying { ended(start) }
            .mapLeft { JobFailure.NoAnswer("the coordinator did not say how ${start.request} ended") }
            .flatMap { it }
    }

    // How the round ended, or a failure of this ask if it has not yet.
    context(_: Fail<Waiting>)
    private fun ended(start: Start): Either<JobFailure, Report> {
        val standing = coordinator.ask<CoordinatorTurn, Standing>(10.seconds) { Progress(start.request, it) }.fold({ null }, { it })
        // A coordinator that does not know the round has only just taken over from the one that did.
        if (standing == Standing.Unknown) coordinator.tell(start)
        return if (standing is Standing.Ended) standing.how else fail(Waiting)
    }
}

// The round has not ended yet: what an ask fails with, for the schedule to ask again.
private data object Waiting
