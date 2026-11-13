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
data class Job(val id: String, val plan: Path, val inputs: Path, val inputsPerScenario: Int, val lot: Int, val out: Path) {
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

/** How a job went: who ran each lot, how many lots were given out again, and how long it all took. */
data class Report(val lots: List<LotRun>, val givenAgain: Int, val took: Duration)

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
    private class Worker(val ref: ActorRef<Assign>, var lot: Int? = null, var since: TimeSource.Monotonic.ValueTimeMark? = null, var usable: Boolean = true, var restAfter: TimeSource.Monotonic.ValueTimeMark? = null)

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
                    workers[member.node] = Worker(node.remote(Address(member.node.toString(), WORKER_PATH, 0), AssignCodec))
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
        val job = job ?: return@withLock
        if (outcome.job != job.id) return@withLock
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
            tries[outcome.lot] = tried
            if (tried >= attempts) {
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
        val taken = worker.ref.ask<Assign, Boolean>(5.seconds) { accepted ->
            Assign(
                job.id, lot, job.plan.toString(), job.inputs.toString(), job.first(lot), job.count(lot),
                job.answers(lot).toString(), accepted, outcomes,
            )
        }
        if (taken is Either.Right && taken.value) return
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
     * if a lot keeps failing or it is not done [within] that long.
     */
    fun run(job: Job, within: Duration = 60.minutes): Report {
        Files.createDirectories(job.out)
        val started = TimeSource.Monotonic.markNow()
        lock.withLock {
            check(this.job == null) { "one job at a time: ${this.job?.id} is still running" }
            this.job = job
            waiting.clear()
            waiting.addAll(0 until job.lots)
            done.clear()
            tries.clear()
            givenAgain = 0
            failure = null
            try {
                while (done.size < job.lots) {
                    failure?.let { throw JobFailed(it) }
                    if (started.elapsedNow() > within) {
                        throw JobFailed("${done.size} of ${job.lots} lots were done after $within")
                    }
                    giveOut(job)
                    // Woken by every outcome and change of member; the wait is for lots that have been out too long.
                    changed.awaitNanos(500_000_000)
                }
                return Report(done.values.sortedBy { it.lot }, givenAgain, started.elapsedNow())
            } finally {
                this.job = null
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
