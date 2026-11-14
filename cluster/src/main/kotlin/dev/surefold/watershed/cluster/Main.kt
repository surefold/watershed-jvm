package dev.surefold.watershed.cluster

import dev.surefold.Promise
import dev.surefold.Scope
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
import dev.surefold.scope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val USAGE = """EXPERIMENTAL. One node of a cluster that runs a plan's scenarios in lots.

  worker       name=w1 port=25521 seed=HOST:PORT [host=127.0.0.1] [gpu=true|false] [gpu-bytes=N]
  coordinator  port=25520 plan=FILE inputs=FILE inputs-per=N outputs-per=N lot=N out=FOLDER
               [host=127.0.0.1] [workers=1] [wait=120] [lot-within=1800] [check=FILE] [job=job]
               [columns=FILE] [hold=true] [patch=FILE] [check-after=FILE]

columns= names a file of the plan's outputs to write as answers, as numbers with commas between. With hold=true
each worker holds on to its lots, and patch= then brings them up to date with a patch from the engine's Kept.

Every node must be able to read the plan and the inputs, and write to the folder, under the same paths.
The coordinator is the seed: start it first, and give its host and port to each worker. A worker runs lots on
its GPU if it has one (pick which with CUDA_VISIBLE_DEVICES) unless gpu=false, and until it is stopped.
Workers that share one GPU are each told how much of its memory to use, with gpu-bytes."""

// What the command line asks for, once every setting has been read: nothing below this asks for one again.
private sealed interface Command {
    val host: String
    val port: Int

    data class Work(
        override val host: String,
        override val port: Int,
        val name: String,
        val seed: String,
        val gpu: Boolean?,
        val gpuBytes: Long?,
    ) : Command

    data class Coordinate(
        override val host: String,
        override val port: Int,
        val job: Job,
        val outputsPer: Int,
        val workers: Int,
        val wait: Duration,
        val lotWithin: Duration,
        val check: Path?,
        val patch: Path?,
        val checkAfter: Path?,
    ) : Command
}

// Why the node did not do what it was started for: said on the way out, with the exit code that goes with it.
private data class Stopped(val why: String, val code: Int)

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
private fun command(args: Array<String>): Command {
    val set = Settings(args.drop(1))
    ensure(set.stray.isEmpty()) { Stopped("${set.stray} is not name=value\n\n$USAGE", 2) }
    val host = set["host"] ?: "127.0.0.1"
    return when (args.firstOrNull()) {
        "worker" -> Command.Work(
            host, set.needInt("port"), set.need("name"), set.need("seed"), set.flag("gpu"),
            set.read("gpu-bytes", "a whole number", String::toLongOrNull),
        )

        "coordinator" -> {
            val columns = set["columns"]?.let { file ->
                val text = Either.catch { Files.readString(Path.of(file)) }.mapLeft { Stopped("columns=$file: $it", 2) }.bind()
                text.trim().split(",").map { ensureNotNull(it.trim().toIntOrNull()) { Stopped("columns=$file: $it is not a column", 2) } }
            }
            val job = Job.read(
                set["job"] ?: "job",
                Path.of(set.need("plan")),
                Path.of(set.need("inputs")),
                set.needInt("inputs-per"),
                set.needInt("lot"),
                Path.of(set.need("out")),
                columns.orEmpty(),
                set.flag("hold") ?: false,
            ).mapLeft { Stopped(it.toString(), 2) }.bind()
            Command.Coordinate(
                host, set.needInt("port"), job, set.needInt("outputs-per"), set.int("workers") ?: 1,
                (set.int("wait") ?: 120).seconds, (set.int("lot-within") ?: 1800).seconds,
                set["check"]?.let(Path::of), set["patch"]?.let(Path::of), set["check-after"]?.let(Path::of),
            )
        }

        else -> fail(Stopped(USAGE, 2))
    }
}

// Nodes of a batch job come and go by being stopped; one gone quiet is given up on after this long.
private val downing = Downing.keepMajority(stableAfter = 10.seconds)

private fun Scope<Stopped>.work(command: Command.Work) {
    val node = node(command.name, command.port, host = command.host)
    // It joins, but is not counted a member that can be given lots until its worker is there to take them.
    val cluster = cluster(
        node,
        Discovery.static(command.seed),
        downing = downing,
        leaveWithin = 5.seconds,
        roles = setOf(WORKER),
        ready = Ready.WhenTold,
    )
    worker(node, gpu = command.gpu, gpuBytes = command.gpuBytes).mapLeft { Stopped("no GPU: ${it.why}", 1) }.bind()
    cluster.admit()
    println("worker ${node.self} is up")
    // A latch nobody opens: the worker goes on until its process is stopped.
    Promise<Nothing, Unit>("watershed-worker").awaitValue()
}

private fun Scope<Stopped>.coordinate(command: Command.Coordinate) {
    val job = command.job
    val node = node("coordinator", command.port, host = command.host)
    val cluster = cluster(node, Discovery.static("${command.host}:${command.port}"), downing = downing, leaveWithin = 5.seconds)
    val coordinator = coordinator(node, cluster, lotWithin = command.lotWithin)
    println("coordinator ${node.self}: ${job.scenarios} scenarios in ${job.lots} lots of ${job.lot}; waiting for ${command.workers} workers")
    ensure(coordinator.awaitWorkers(command.workers, command.wait)) {
        Stopped("only ${coordinator.workers()} of ${command.workers} workers joined", 1)
    }

    val report = coordinator.run(job, within = 600.minutes).orStop()
    println("%5s  %-34s %4s %10s %10s".format("lot", "worker", "on", "loaded in", "ran in"))
    report.lots.forEach { println("%5d  %-34s %4s %9.1fs %9.1fs".format(it.lot, it.worker, it.on, it.loadSeconds, it.runSeconds)) }
    report.failures.forEach { println("failed and given to another: $it") }
    println("all ${job.lots} lots answered in ${report.took}; ${report.givenAgain} given out again")
    command.check?.let { same(job, command.outputsPer, it) }

    command.patch?.let { patch ->
        val refreshed = coordinator.refresh(job, patch).orStop()
        println()
        println("brought up to date with $patch:")
        refreshed.lots.forEach { println("%5d  %-34s %4s %9.3fs %9.3fs".format(it.lot, it.worker, it.on, it.loadSeconds, it.runSeconds)) }
        refreshed.failures.forEach { println("failed: $it") }
        println("${refreshed.lots.size} lots brought up to date in ${refreshed.took}; lost: ${refreshed.lost}")
        ensure(refreshed.lost.isEmpty()) { Stopped("lots ${refreshed.lost} were lost", 1) }
        command.checkAfter?.let { same(job, command.outputsPer, it) }
    }
}

context(_: Fail<Stopped>)
private fun <A> Either<JobFailure, A>.orStop(): A = mapLeft { Stopped(it.toString(), 1) }.bind()

// Stops unless the job's answers are, to the last bit, the numbers in the file at [check].
context(_: Fail<Stopped>)
private fun same(job: Job, outputsPer: Int, check: Path) {
    // Lot by lot, so that a big job's answers are never all in memory twice.
    val same = countNumbers(check).orStop() == job.scenarios * outputsPer && (0 until job.lots).all { lot ->
        val count = job.count(lot) * outputsPer
        val ours = readNumbers(job.answers(lot), 0, count).orStop()
        val theirs = readNumbers(check, job.first(lot) * outputsPer, count).orStop()
        ours.indices.all { ours[it].toRawBits() == theirs[it].toRawBits() }
    }
    ensure(same) { Stopped("THE ANSWERS DIFFER from $check", 1) }
    println("the answers are the same to the last bit as $check")
}

fun main(args: Array<String>) {
    val ended = attempt<Stopped, Unit> {
        when (val command = command(args)) {
            is Command.Work -> scope { work(command) }
            is Command.Coordinate -> scope { coordinate(command) }
        }
    }
    exitProcess(ended.fold({ stopped -> println(stopped.why).let { stopped.code } }, { 0 }))
}
