package dev.surefold.watershed.cluster

import dev.surefold.Scope
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.RemoteNode
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.MemberEvent
import dev.surefold.fail.Either
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
 * cannot be reached, refuses it, fails it or takes longer than [lotWithin] is given to another. A lot run twice
 * gives the same answers both times, so nothing is done to stop that. A lot that has failed [attempts] times
 * fails the job.
 *
 * One job runs at a time. The coordinator is a member like any other and holds nothing a worker needs: if it
 * stops, the job stops with it and is started again from the beginning.
 */
class Coordinator internal constructor(
    private val node: RemoteNode,
    private val cluster: Cluster,
    private val outcomes: ActorRef<Outcome>,
    private val lotWithin: Duration,
    private val attempts: Int,
) {
    private class Worker(val ref: ActorRef<ToWorker>, var lot: Int? = null, var since: TimeSource.Monotonic.ValueTimeMark? = null, var usable: Boolean = true, var restAfter: TimeSource.Monotonic.ValueTimeMark? = null)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val workers = HashMap<Node, Worker>()

    // The job in hand, if any.
    private var job: Job? = null
    private val waiting = ArrayDeque<Int>()
    private val done = HashMap<Int, LotRun>()
    private val tries = HashMap<Int, Int>()
    private var givenAgain = 0
    private var failure: String? = null

    // What outcomes must carry to be counted: the job's id while it runs, and another for each refresh.
    private var tag: String? = null
    private var refreshes = 0

    // For each job run to be held: the node that holds each lot.
    private val holders = HashMap<String, HashMap<Int, Node>>()
    private val failures = ArrayList<String>()

    /** How many workers the cluster has now. */
    fun workers(): Int = lock.withLock { workers.values.count { it.usable } }

    /** Waits until there are [count] workers, or [within] has passed; true if there are. */
    fun awaitWorkers(count: Int, within: Duration): Boolean = lock.withLock {
        var left = within.inWholeNanoseconds
        while (workers.values.count { it.usable } < count && left > 0) left = changed.awaitNanos(left)
        workers.values.count { it.usable } >= count
    }

    internal fun heard(event: MemberEvent) = lock.withLock {
        val member = event.member
        if (WORKER !in member.roles || cluster.isSelf(member)) return@withLock
        when (event) {
            is MemberEvent.Up, is MemberEvent.Reachable -> {
                val known = workers[member.node]
                if (known == null || event is MemberEvent.Up) {
                    known?.lot?.let(::again)
                    workers[member.node] = Worker(node.remote(Address(member.node.toString(), WORKER_PATH, 0), ToWorkerCodec))
                } else {
                    known.usable = true
                }
            }

            is MemberEvent.Unreachable -> workers[member.node]?.let { worker ->
                worker.usable = false
                worker.lot?.let(::again)
                worker.lot = null
            }

            is MemberEvent.Downed, is MemberEvent.Removed -> workers.remove(member.node)?.lot?.let(::again)
        }
        changed.signalAll()
    }

    internal fun heard(outcome: Outcome) = lock.withLock {
        if (job == null || outcome.job != tag) return@withLock
        workers.values.firstOrNull { it.lot == outcome.lot && it.ref.address.node == outcome.worker }?.let {
            it.lot = null
            it.since = null
        }
        if (outcome.failed == null) {
            done.getOrPut(outcome.lot) {
                LotRun(outcome.lot, outcome.worker, outcome.on, outcome.loadSeconds, outcome.runSeconds)
            }
            waiting.remove(outcome.lot)
        } else if (outcome.lot !in done) {
            val tried = (tries[outcome.lot] ?: 0) + 1
            failures += "lot ${outcome.lot} on ${outcome.worker}: ${outcome.failed}"
            tries[outcome.lot] = tried
            if (tag != job?.id) {
                // A refresh: the lot is left as it was, and reported lost.
            } else if (tried >= attempts) {
                failure = "lot ${outcome.lot} failed $tried times, last on ${outcome.worker}: ${outcome.failed}"
            } else {
                again(outcome.lot)
            }
        }
        changed.signalAll()
    }

    // Under the lock: a lot that must be given out once more.
    private fun again(lot: Int) {
        if (job != null && lot !in done && lot !in waiting) {
            waiting.addFirst(lot)
            givenAgain++
        }
    }

    // Under the lock: gives a waiting lot to each idle worker, and takes back any lot that has been out too long.
    private fun giveOut(job: Job) {
        val now = TimeSource.Monotonic.markNow()
        for (worker in workers.values) {
            val since = worker.since
            if (worker.lot != null && since != null && since.elapsedNow() > lotWithin) {
                again(worker.lot!!)
                worker.lot = null
                worker.since = null
                // It may still be busy with it: leave it be for a while.
                worker.restAfter = now + 10.seconds
            }
        }
        for (worker in workers.values) {
            if (waiting.isEmpty()) break
            if (!worker.usable || worker.lot != null || worker.restAfter?.hasPassedNow() == false) continue
            val lot = waiting.removeFirst()
            worker.lot = lot
            worker.since = now
            Thread.startVirtualThread { offer(job, worker, lot) }
        }
    }

    // Off the lock: asks a worker to take a lot, and puts the lot back if it does not say yes.
    private fun offer(job: Job, worker: Worker, lot: Int) {
        val taken = worker.ref.ask<ToWorker, Int>(5.seconds) { accepted ->
            Assign(
                job.id, lot, job.plan.toString(), job.inputs.toString(), job.first(lot), job.count(lot),
                job.answers(lot).toString(), job.columns, job.hold, accepted, outcomes,
            )
        }
        if (taken is Either.Right && taken.value == TAKEN) return
        lock.withLock {
            if (worker.lot == lot) {
                worker.lot = null
                worker.since = null
                worker.restAfter = TimeSource.Monotonic.markNow() + 2.seconds
                if (this.job === job && lot !in done && lot !in waiting) waiting.addFirst(lot)
            }
            changed.signalAll()
        }
    }

    /**
     * Runs [job] on the cluster's workers and returns once every lot's answers are written, or throws [JobFailed]
     * if a lot keeps failing or it is not done [within] that long. With [only], just those lots are run: the ones a
     * refresh lost, with a plan made after the change.
     */
    fun run(job: Job, within: Duration = 60.minutes, only: Collection<Int>? = null): Report {
        Files.createDirectories(job.out)
        val started = TimeSource.Monotonic.markNow()
        lock.withLock {
            check(this.job == null) { "one job at a time: ${this.job?.id} is still running" }
            this.job = job
            tag = job.id
            val lots = only?.distinct() ?: (0 until job.lots).toList()
            require(lots.all { it in 0 until job.lots }) { "the job has lots 0 to ${job.lots - 1}, not all of $lots" }
            waiting.clear()
            waiting.addAll(lots)
            done.clear()
            tries.clear()
            givenAgain = 0
            failures.clear()
            failure = null
            try {
                while (done.size < lots.size) {
                    failure?.let { throw JobFailed(it) }
                    if (started.elapsedNow() > within) {
                        throw JobFailed("${done.size} of ${lots.size} lots were done after $within")
                    }
                    giveOut(job)
                    // Woken by every outcome and change of member; the wait is for lots that have been out too long.
                    changed.awaitNanos(500_000_000)
                }
                if (job.hold) {
                    val holding = holders.getOrPut(job.id) { HashMap() }
                    val nodes = workers.keys.associateBy { it.toString() }
                    done.values.forEach { run -> nodes[run.worker]?.let { holding[run.lot] = it } ?: holding.remove(run.lot) }
                }
                return Report(done.values.sortedBy { it.lot }, givenAgain, failures.toList(), started.elapsedNow())
            } finally {
                this.job = null
                tag = null
                workers.values.forEach {
                    it.lot = null
                    it.since = null
                }
            }
        }
    }

    /**
     * Brings [job], which was run to be held, up to date with the patch in the file at [patch]: each worker runs
     * the patch on the lots it holds and writes their answers again. A lot whose worker has gone, or no longer
     * holds it, is left as it was and listed in the report's `lost`.
     */
    fun refresh(job: Job, patch: Path, within: Duration = 60.minutes): Report {
        val started = TimeSource.Monotonic.markNow()
        lock.withLock {
            check(this.job == null) { "one job at a time: ${this.job?.id} is still running" }
            val holding = holders[job.id] ?: throw JobFailed("no run of ${job.id} is held")
            val id = "${job.id}#refresh-${++refreshes}"
            this.job = job
            tag = id
            waiting.clear()
            done.clear()
            tries.clear()
            givenAgain = 0
            failures.clear()
            failure = null
            val lost = sortedSetOf<Int>()
            val queues = HashMap<Node, ArrayDeque<Int>>()
            for (lot in 0 until job.lots) {
                val node = holding[lot]
                if (node == null) lost += lot else queues.getOrPut(node) { ArrayDeque() }.addLast(lot)
            }
            try {
                while (done.size + lost.size < job.lots) {
                    if (started.elapsedNow() > within) {
                        throw JobFailed("${done.size} of ${job.lots} lots were brought up to date after $within")
                    }
                    // A lot that failed is not tried again: its values are as they were, and it is lost.
                    for (lot in tries.keys) if (lot !in done) lost += lot
                    for ((node, queue) in queues) {
                        val worker = workers[node]
                        if (worker == null || !worker.usable) {
                            // Its holder has gone: what it held is lost, and so is any lot it was working on.
                            lost += queue
                            queue.clear()
                            holding.entries.filter { it.value == node && it.key !in done }.forEach { lost += it.key }
                            continue
                        }
                        if (worker.lot != null || queue.isEmpty() || worker.restAfter?.hasPassedNow() == false) continue
                        val lot = queue.removeFirst()
                        worker.lot = lot
                        worker.since = TimeSource.Monotonic.markNow()
                        Thread.startVirtualThread {
                            val taken = worker.ref.ask<ToWorker, Int>(5.seconds) { accepted ->
                                Refresh(job.id, id, lot, patch.toString(), job.answers(lot).toString(), job.columns, accepted, outcomes)
                            }
                            lock.withLock {
                                if (tag == id && !(taken is Either.Right && taken.value == TAKEN)) {
                                    worker.lot = null
                                    worker.since = null
                                    if (taken is Either.Right && taken.value == NOT_HELD) {
                                        lost += lot
                                    } else {
                                        // Busy, or no answer: ask again shortly.
                                        worker.restAfter = TimeSource.Monotonic.markNow() + 1.seconds
                                        queue.addFirst(lot)
                                    }
                                    changed.signalAll()
                                }
                            }
                        }
                    }
                    changed.awaitNanos(200_000_000)
                }
                lost.forEach(holding::remove)
                return Report(
                    done.values.sortedBy { it.lot }, 0, failures.toList(), started.elapsedNow(), lost.filter { it !in done },
                )
            } finally {
                this.job = null
                tag = null
                waiting.clear()
                workers.values.forEach {
                    it.lot = null
                    it.since = null
                }
            }
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
    lateinit var coordinator: Coordinator
    val outcomes = spawn("outcomes", behaviour<Outcome, Unit>(Unit) { _, _, outcome -> stay().also { coordinator.heard(outcome) } })
    node.expose(outcomes, OutcomeCodec)
    coordinator = Coordinator(node, cluster, outcomes, lotWithin, attempts)
    val members = spawn("members", behaviour<MemberEvent, Unit>(Unit) { _, _, event -> stay().also { coordinator.heard(event) } })
    cluster.subscribe(members)
    return coordinator
}
