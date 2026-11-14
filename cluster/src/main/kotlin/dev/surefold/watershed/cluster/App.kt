package dev.surefold.watershed.cluster

import dev.surefold.Shared
import dev.surefold.app.AppScope
import dev.surefold.app.Module
import dev.surefold.app.SurefoldApp
import dev.surefold.app.actor.Actors
import dev.surefold.app.actor.actors
import dev.surefold.app.cluster.ClusterSettings
import dev.surefold.app.cluster.cluster
import dev.surefold.app.runApp
import dev.surefold.app.single
import dev.surefold.app.typesafe.Secret
import dev.surefold.app.typesafe.config
import dev.surefold.app.typesafe.loadedConfig
import dev.surefold.cluster.Cluster
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.fold
import dev.surefold.fail.mapLeft
import dev.surefold.files.Files
import dev.surefold.files.FilesConfig
import dev.surefold.files.S3Files
import dev.surefold.logError
import dev.surefold.logInfo
import java.net.URI
import kotlin.system.exitProcess
import kotlin.time.Duration

// A node of the cluster is a Surefold application: a graph of what it is made of, built from configuration, started
// and released by `runApp`. See reference.conf for every setting.
//
//     worker   joins the cluster and runs lots until it is stopped
//     submit   joins the cluster, runs the job in `watershed.job`, reports it, and leaves
//     put      copies the file `watershed.put.from` of this machine into the bucket at `watershed.put.key`

private class Bucket(
    val name: String,
    val region: String,
    val endpoint: String,
    val pathStyle: Boolean,
    val accessKey: Secret,
    val secretKey: Secret,
    val create: Boolean,
)

private class Card(val gpu: String, val gpuBytes: Long)

class Asked internal constructor(
    val id: String,
    val plan: String,
    val inputs: String,
    val inputsPer: Int,
    val outputsPer: Int,
    val lot: Int,
    val out: String,
    val columns: List<Int>,
    val hold: Boolean,
    val workers: Int,
    val wait: Duration,
    val within: Duration,
    val check: String,
    val patch: String,
    val checkAfter: String,
)

class Putting internal constructor(val from: String, val key: String)

// The bucket, and the store on it.
private val bucket: Module =
    config<Bucket>("watershed.bucket") {
        Bucket(
            string("name"), string("region"), string("endpoint"), boolean("path-style"),
            secret("access-key"), secret("secret-key"), boolean("create"),
        )
    } +
        // The client is the graph's to close, once nothing built on it is left.
        single { settings: Bucket ->
            val endpoint = settings.endpoint.takeIf { it.isNotEmpty() }?.let { text ->
                Either.catch { URI(text) }.fold({ refuse("watershed.bucket.endpoint is not an address: $text") }, { it })
            }
            val config = FilesConfig(
                settings.name, settings.region, settings.accessKey.reveal(), settings.secretKey.reveal(), endpoint, settings.pathStyle,
            )
            install({ S3Files.of(config) }) { files, _ -> files.close() }.also { files ->
                if (settings.create) files.createBucket().fold({ refuse("the bucket could not be made: $it") }, {})
            }
        } +
        single { files: S3Files -> Store(files) }

// A node of the cluster: it joins as its configuration says, and is admitted once everything here is built, so no
// lot is sent to it before it can take one. Its roles say whether it runs lots.
private val node: Module =
    loadedConfig() +
        // A message to a node that has just left is expected here, and not worth a line.
        actors(onDeadLetter = {}) +
        cluster("watershed.cluster") +
        bucket +
        config<Card>("watershed.worker") { Card(string("gpu"), long("gpu-bytes")) } +
        config<Patience>("watershed.patience") {
            Patience(duration("lot-within"), int("attempts"), duration("tell-again-every"), duration("ask-every"), duration("settled"))
        } +
        single { actors: Actors, cluster: Cluster, store: Store, joined: ClusterSettings, card: Card, patience: Patience ->
            val working = Working(gpu = card.gpu.toBooleanStrictOrNull(), gpuBytes = card.gpuBytes.takeIf { it > 0 })
            actors.within { jobs(cluster, store, working.takeIf { WORKER in joined.roles }, patience) }
                .fold({ refuse("this worker was told to use a GPU, and has none: ${it.why}") }, { it })
        }

/** A worker: it runs lots until its process is stopped. */
object Worker : SurefoldApp<Jobs>() {
    override val module: Module = node
}

// What `submit` is made of: the way to submit a job, the bucket, and the job asked for.
class Submission internal constructor(val jobs: Jobs, val store: Store, val asked: Asked)

/** Runs the job in `watershed.job`, says how it went, and leaves. */
object Submit : SurefoldApp<Submission>() {
    // Whether the job did what was asked: the process's exit code, once the graph has been released.
    internal val failed = Shared(false, "watershed-submit")

    override val module: Module =
        node +
            config<Asked>("watershed.job") {
                Asked(
                    string("id"), string("plan"), string("inputs"), int("inputs-per"), int("outputs-per"), int("lot"),
                    string("out"), of("columns", emptyList()) { getIntList("columns").map { it.toInt() } }, boolean("hold"),
                    int("workers"), duration("wait"), duration("within"), string("check"), string("patch"), string("check-after"),
                )
            } +
            single { jobs: Jobs, store: Store, asked: Asked -> Submission(jobs, store, asked) }

    override fun AppScope.run(root: Submission) {
        attempt<String, Unit> { submit(root) }.fold({ why -> logError(why).also { failed.update { true } } }, {})
    }
}

context(_: Fail<String>)
private fun submit(root: Submission) {
    val (jobs, store, asked) = Triple(root.jobs, root.store, root.asked)
    val job = Job.read(store, asked.id, asked.plan, asked.inputs, asked.inputsPer, asked.lot, asked.out, asked.columns, asked.hold).said()
    logInfo("${job.scenarios} scenarios in ${job.lots} lots of ${job.lot}; waiting for ${asked.workers} workers")
    ensure(jobs.awaitWorkers(asked.workers, asked.wait)) { "only ${jobs.workers()} of ${asked.workers} workers joined" }

    val report = jobs.run(job, within = asked.within).said()
    logInfo("%5s  %-34s %4s %10s %10s".format("lot", "worker", "on", "loaded in", "ran in"))
    report.lots.forEach { logInfo("%5d  %-34s %4s %9.1fs %9.1fs".format(it.lot, it.worker, it.on, it.loadSeconds, it.runSeconds)) }
    report.failures.forEach { logInfo("failed and given to another: $it") }
    logInfo("all ${job.lots} lots answered in ${report.took}; ${report.givenAgain} given out again")
    if (asked.check.isNotEmpty()) same(store, job, asked.outputsPer, asked.check)

    if (asked.patch.isNotEmpty()) {
        val refreshed = jobs.refresh(job, asked.patch).said()
        logInfo("brought up to date with ${asked.patch}:")
        refreshed.lots.forEach { logInfo("%5d  %-34s %4s %9.3fs %9.3fs".format(it.lot, it.worker, it.on, it.loadSeconds, it.runSeconds)) }
        refreshed.failures.forEach { logInfo("failed: $it") }
        logInfo("${refreshed.lots.size} lots brought up to date in ${refreshed.took}; lost: ${refreshed.lost}")
        ensure(refreshed.lost.isEmpty()) { "lots ${refreshed.lost} were lost" }
        if (asked.checkAfter.isNotEmpty()) same(store, job, asked.outputsPer, asked.checkAfter)
    }
}

context(_: Fail<String>)
private fun <A> Either<JobFailure, A>.said(): A = mapLeft { it.toString() }.bind()

// Fails unless the job's answers are, to the last bit, the numbers in the file at [check].
context(_: Fail<String>)
private fun same(store: Store, job: Job, outputsPer: Int, check: String) {
    val theirs = store.read(check).said()
    val same = theirs.size.toLong() == job.scenarios * outputsPer && (0 until job.lots).all { lot ->
        val ours = store.read(job.answers(lot)).said()
        val from = (job.first(lot) * outputsPer).toInt()
        ours.size == job.count(lot) * outputsPer && ours.indices.all { ours[it].toRawBits() == theirs[from + it].toRawBits() }
    }
    ensure(same) { "THE ANSWERS DIFFER from $check" }
    logInfo("the answers are the same to the last bit as $check")
}

// What `put` is made of: the bucket, and the file to copy into it.
class Upload internal constructor(val store: Store, val putting: Putting)

/** Copies a file of this machine into the bucket: how what the engine's examples wrote gets there. */
object Put : SurefoldApp<Upload>() {
    internal val failed = Shared(false, "watershed-put")

    override val module: Module =
        loadedConfig() + bucket +
            config<Putting>("watershed.put") { Putting(string("from"), string("key")) } +
            single { store: Store, putting: Putting -> Upload(store, putting) }

    override fun AppScope.run(root: Upload) {
        root.store.upload(root.putting.from, root.putting.key).fold(
            { why -> logError("${why.key}: ${why.why}").also { failed.update { true } } },
            { logInfo("${root.putting.from} is at ${root.putting.key}") },
        )
    }
}

fun main(args: Array<String>) {
    val code = when (args.singleOrNull()) {
        "worker" -> runApp(Worker).code
        "submit" -> runApp(Submit).code.takeIf { it != 0 } ?: if (Submit.failed.peek()) 1 else 0
        "put" -> runApp(Put).code.takeIf { it != 0 } ?: if (Put.failed.peek()) 1 else 0
        else -> 2.also { logError("say worker, submit or put; every setting is in reference.conf, and is given with -Dwatershed.…=") }
    }
    exitProcess(code)
}
