package dev.surefold.watershed.cluster

import dev.surefold.LogLevel
import dev.surefold.Logger
import dev.surefold.Promise
import dev.surefold.Scope
import dev.surefold.StderrLogger
import dev.surefold.actor.remote.node
import dev.surefold.awaitValue
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Downing
import dev.surefold.cluster.Ready
import dev.surefold.cluster.cluster
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.ensureNotNull
import dev.surefold.fail.fail
import dev.surefold.fail.fold
import dev.surefold.fail.mapLeft
import dev.surefold.files.FilesConfig
import dev.surefold.files.S3Files
import dev.surefold.logError
import dev.surefold.logInfo
import dev.surefold.logger
import dev.surefold.scope
import java.net.URI
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val USAGE = """EXPERIMENTAL. One node of a cluster that runs a plan's scenarios in lots.

  put          from=FILE key=KEY [make-bucket=true]
  worker       name=w1 port=25521 seed=HOST:PORT [host=127.0.0.1] [gpu=true|false] [gpu-bytes=N]
  coordinator  port=25520 plan=KEY inputs=KEY inputs-per=N outputs-per=N lot=N out=PREFIX
               [host=127.0.0.1] [workers=1] [wait=120] [lot-within=1800] [check=KEY] [job=job]
               [columns=3,7] [hold=true] [patch=KEY] [check-after=KEY]

and, for every one of them, where the files are:

               bucket=NAME [region=auto] [endpoint=https://...] [path-style=true]

A job's files are in an S3-compatible bucket that every node reaches: its plan, its scenarios, each lot's answers.
A KEY names a file there. `put` copies a file of this machine into the bucket, which is how a plan or a patch the
engine saved gets there. The bucket's access key and secret are read from the environment, WATERSHED_S3_ACCESS_KEY
and WATERSHED_S3_SECRET_KEY, not from the command line. A node keeps nothing on its own disk.

columns= names the plan's outputs to write as answers. With hold=true each lot holds on to what it ran, and patch=
then brings them up to date with a patch from the engine's Kept. check= and check-after= name files of the answers
expected, to compare with, to the last bit.

The coordinator's node is the seed: start it first, and give its host and port to each worker. A worker runs lots
on its GPU if it has one (pick which with CUDA_VISIBLE_DEVICES) unless gpu=false, and until it is stopped. Workers
that share one GPU are each told how much of its memory to use, with gpu-bytes."""

// Why the node did not do what it was started for: said on the way out, with the exit code that goes with it.
private data class Stopped(val why: String, val code: Int)

// What the command line asks for, once every setting has been read: nothing below this asks for one again.
private sealed interface Command {
    val store: Store

    data class Put(override val store: Store, val from: String, val key: String, val bucket: S3Files?) : Command

    data class Work(
        override val store: Store,
        val host: String,
        val port: Int,
        val name: String,
        val seed: String,
        val working: Working,
    ) : Command

    data class Coordinate(
        override val store: Store,
        val host: String,
        val port: Int,
        val job: Job,
        val outputsPer: Int,
        val workers: Int,
        val wait: Duration,
        val lotWithin: Duration,
        val check: String?,
        val patch: String?,
        val checkAfter: String?,
    ) : Command
}

private class Settings(args: List<String>) {
    private val set: Map<String, String> = args.mapNotNull { arg ->
        arg.indexOf('=').takeIf { it > 0 }?.let { arg.substring(0, it) to arg.substring(it + 1) }
    }.toMap()

    /** The arguments that are not name=value. */
    val stray: List<String> = args.filter { it.indexOf('=') <= 0 }

    operator fun get(name: String): String? = set[name]

    context(_: Fail<Stopped>)
    fun need(name: String): String = ensureNotNull(set[name]) { Stopped("$name= is needed\n\n$USAGE", 2) }

    context(_: Fail<Stopped>)
    fun <A : Any> read(name: String, what: String, parse: (String) -> A?): A? =
        set[name]?.let { text -> ensureNotNull(parse(text)) { Stopped("$name=$text is not $what", 2) } }

    context(_: Fail<Stopped>)
    fun int(name: String): Int? = read(name, "a whole number", String::toIntOrNull)

    context(_: Fail<Stopped>)
    fun needInt(name: String): Int = ensureNotNull(need(name).toIntOrNull()) { Stopped("$name= is not a whole number", 2) }

    context(_: Fail<Stopped>)
    fun flag(name: String): Boolean? = read(name, "true or false", String::toBooleanStrictOrNull)
}

context(_: Fail<Stopped>)
private fun store(set: Settings, environment: Map<String, String>): Store = bucket(set, environment).second

// The bucket itself, for the one thing a store has no word for, making it; and the store on it.
context(_: Fail<Stopped>)
private fun bucket(set: Settings, environment: Map<String, String>): Pair<S3Files, Store> {
    val config = FilesConfig(
        bucket = set.need("bucket"),
        region = set["region"] ?: "auto",
        accessKey = ensureNotNull(environment["WATERSHED_S3_ACCESS_KEY"]) { Stopped("WATERSHED_S3_ACCESS_KEY is not set", 2) },
        secretKey = ensureNotNull(environment["WATERSHED_S3_SECRET_KEY"]) { Stopped("WATERSHED_S3_SECRET_KEY is not set", 2) },
        endpoint = set.read("endpoint", "an address") { text -> Either.catch { URI(text) }.fold({ null }, { it }) },
        pathStyle = set.flag("path-style") ?: false,
    )
    return S3Files.of(config).let { it to Store(it) }
}

context(_: Fail<Stopped>)
private fun command(args: Array<String>, environment: Map<String, String>): Command {
    val set = Settings(args.drop(1))
    ensure(args.isNotEmpty() && set.stray.isEmpty()) { Stopped(if (args.isEmpty()) USAGE else "${set.stray} is not name=value\n\n$USAGE", 2) }
    val host = set["host"] ?: "127.0.0.1"
    return when (args[0]) {
        "put" -> bucket(set, environment).let { (bucket, store) ->
            Command.Put(store, set.need("from"), set.need("key"), bucket.takeIf { set.flag("make-bucket") == true })
        }
        "worker" -> Command.Work(
            store(set, environment), host, set.needInt("port"), set.need("name"), set.need("seed"),
            Working(set.flag("gpu"), set.read("gpu-bytes", "a whole number", String::toLongOrNull)),
        )

        "coordinator" -> {
            val store = store(set, environment)
            val columns = set["columns"]?.split(",")?.map { ensureNotNull(it.trim().toIntOrNull()) { Stopped("columns=: $it is not a column", 2) } }
            val job = Job.read(
                store,
                set["job"] ?: "job",
                set.need("plan"),
                set.need("inputs"),
                set.needInt("inputs-per"),
                set.needInt("lot"),
                set.need("out"),
                columns.orEmpty(),
                set.flag("hold") ?: false,
            ).mapLeft { Stopped(it.toString(), 2) }.bind()
            Command.Coordinate(
                store, host, set.needInt("port"), job, set.needInt("outputs-per"), set.int("workers") ?: 1,
                (set.int("wait") ?: 120).seconds, (set.int("lot-within") ?: 1800).seconds,
                set["check"], set["patch"], set["check-after"],
            )
        }

        else -> fail(Stopped(USAGE, 2))
    }
}

// Nodes of a batch job come and go by being stopped; one gone quiet is given up on after this long.
private val downing = Downing.keepMajority(stableAfter = 10.seconds)

private fun Scope<Stopped>.work(command: Command.Work) {
    val node = node(command.name, command.port, host = command.host)
    // It joins, but is not counted a member that can be given lots until it is ready to take them.
    val cluster = cluster(
        node,
        Discovery.static(command.seed),
        downing = downing,
        leaveWithin = 5.seconds,
        roles = setOf(WORKER),
        ready = Ready.WhenTold,
    )
    jobs(cluster, command.store, command.working).mapLeft { Stopped("no GPU: ${it.why}", 1) }.bind()
    cluster.admit()
    logInfo("worker ${node.self} is up")
    // A latch nobody opens: the worker goes on until its process is stopped.
    Promise<Nothing, Unit>("watershed-worker").awaitValue()
}

private fun Scope<Stopped>.coordinate(command: Command.Coordinate) {
    val job = command.job
    val node = node("coordinator", command.port, host = command.host)
    val cluster = cluster(
        node,
        Discovery.static("${command.host}:${command.port}"),
        downing = downing,
        leaveWithin = 5.seconds,
        roles = setOf(COORDINATOR),
        ready = Ready.WhenTold,
    )
    val jobs = jobs(cluster, command.store, patience = Patience(lotWithin = command.lotWithin)).mapLeft { Stopped(it.why, 1) }.bind()
    cluster.admit()
    logInfo("coordinator ${node.self}: ${job.scenarios} scenarios in ${job.lots} lots of ${job.lot}; waiting for ${command.workers} workers")
    ensure(jobs.awaitWorkers(command.workers, command.wait)) { Stopped("only ${jobs.workers()} of ${command.workers} workers joined", 1) }

    val report = jobs.run(job, within = 600.minutes).orStop()
    logInfo("%5s  %-34s %4s %10s %10s".format("lot", "worker", "on", "loaded in", "ran in"))
    report.lots.forEach { logInfo("%5d  %-34s %4s %9.1fs %9.1fs".format(it.lot, it.worker, it.on, it.loadSeconds, it.runSeconds)) }
    report.failures.forEach { logInfo("failed and given to another: $it") }
    logInfo("all ${job.lots} lots answered in ${report.took}; ${report.givenAgain} given out again")
    command.check?.let { same(command.store, job, command.outputsPer, it) }

    command.patch?.let { patch ->
        val refreshed = jobs.refresh(job, patch).orStop()
        logInfo("brought up to date with $patch:")
        refreshed.lots.forEach { logInfo("%5d  %-34s %4s %9.3fs %9.3fs".format(it.lot, it.worker, it.on, it.loadSeconds, it.runSeconds)) }
        refreshed.failures.forEach { logInfo("failed: $it") }
        logInfo("${refreshed.lots.size} lots brought up to date in ${refreshed.took}; lost: ${refreshed.lost}")
        ensure(refreshed.lost.isEmpty()) { Stopped("lots ${refreshed.lost} were lost", 1) }
        command.checkAfter?.let { same(command.store, job, command.outputsPer, it) }
    }
}

context(_: Fail<Stopped>)
private fun <A> Either<JobFailure, A>.orStop(): A = mapLeft { Stopped(it.toString(), 1) }.bind()

// Stops unless the job's answers are, to the last bit, the numbers in the file at [check].
context(_: Fail<Stopped>)
private fun same(store: Store, job: Job, outputsPer: Int, check: String) {
    val theirs = store.read(check).orStop()
    val same = theirs.size.toLong() == job.scenarios * outputsPer && (0 until job.lots).all { lot ->
        val ours = store.read(job.answers(lot)).orStop()
        val from = (job.first(lot) * outputsPer).toInt()
        ours.size == job.count(lot) * outputsPer && ours.indices.all { ours[it].toRawBits() == theirs[from + it].toRawBits() }
    }
    ensure(same) { Stopped("THE ANSWERS DIFFER from $check", 1) }
    logInfo("the answers are the same to the last bit as $check")
}

// What a node says goes to the log, as everything of Surefold's does; its debug lines, a dead letter for every
// message to a node that has just left, are left out.
private val quietly = Logger { line -> if (line.level != LogLevel.Debug) StderrLogger.log(line) }

fun main(args: Array<String>) {
    logger.locally(quietly) { exitProcess(node(args)) }
}

// Does what the command line asks, and answers the exit code.
private fun node(args: Array<String>): Int {
    val ended = attempt<Stopped, Unit> {
        when (val command = command(args, System.getenv())) {
            is Command.Put -> {
                command.bucket?.createBucket()?.mapLeft { Stopped("the bucket could not be made: $it", 1) }?.bind()
                command.store.upload(command.from, command.key).orStop()
                logInfo("${command.from} is at ${command.key}")
            }
            is Command.Work -> scope { work(command) }
            is Command.Coordinate -> scope { coordinate(command) }
        }
    }
    return ended.fold({ stopped -> logError(stopped.why).let { stopped.code } }, { 0 })
}
