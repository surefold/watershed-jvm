package dev.surefold.watershed.cluster

import dev.surefold.actor.remote.node
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Downing
import dev.surefold.cluster.Ready
import dev.surefold.cluster.cluster
import dev.surefold.scope
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
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

private fun settings(args: List<String>): Map<String, String> = args.associate {
    val at = it.indexOf('=')
    require(at > 0) { "$it is not name=value" }
    it.substring(0, at) to it.substring(at + 1)
}

private fun Map<String, String>.need(name: String): String = this[name] ?: error("$name= is needed\n\n$USAGE")

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(USAGE)
        exitProcess(2)
    }
    val set = settings(args.drop(1))
    val host = set["host"] ?: "127.0.0.1"
    // Nodes of a batch job come and go by being stopped; one gone quiet is given up on after this long.
    val downing = Downing.keepMajority(stableAfter = 10.seconds)
    when (args[0]) {
        "worker" -> scope {
            val node = node(set.need("name"), set.need("port").toInt(), host = host)
            // It joins, but is not counted a member that can be given lots until its worker is there to take them.
            val cluster = cluster(
                node,
                Discovery.static(set.need("seed")),
                downing = downing,
                leaveWithin = 5.seconds,
                roles = setOf(WORKER),
                ready = Ready.WhenTold,
            )
            worker(node, gpu = set["gpu"]?.toBooleanStrict(), gpuBytes = set["gpu-bytes"]?.toLong())
            cluster.admit()
            println("worker ${node.self} is up")
            CountDownLatch(1).await()
        }

        "coordinator" -> {
            val ok = scope {
                val port = set.need("port").toInt()
                val node = node("coordinator", port, host = host)
                val cluster = cluster(node, Discovery.static("$host:$port"), downing = downing, leaveWithin = 5.seconds)
                val coordinator = coordinator(node, cluster, lotWithin = (set["lot-within"]?.toInt() ?: 1800).seconds)
                val job = Job(
                    set["job"] ?: "job",
                    Path.of(set.need("plan")),
                    Path.of(set.need("inputs")),
                    set.need("inputs-per").toInt(),
                    set.need("lot").toInt(),
                    Path.of(set.need("out")),
                    columns = set["columns"]?.let { Files.readString(Path.of(it)).trim().split(",").map(String::toInt) }
                        ?: emptyList(),
                    hold = set["hold"]?.toBooleanStrict() ?: false,
                )
                val wanted = set["workers"]?.toInt() ?: 1
                println("coordinator ${node.self}: ${job.scenarios} scenarios in ${job.lots} lots of ${job.lot}; waiting for $wanted workers")
                if (!coordinator.awaitWorkers(wanted, (set["wait"]?.toInt() ?: 120).seconds)) {
                    println("only ${coordinator.workers()} of $wanted workers joined")
                    return@scope false
                }
                val report = coordinator.run(job, within = 600.minutes)
                println("%5s  %-34s %4s %10s %10s".format("lot", "worker", "on", "loaded in", "ran in"))
                for (lot in report.lots) {
                    println("%5d  %-34s %4s %9.1fs %9.1fs".format(lot.lot, lot.worker, lot.on, lot.loadSeconds, lot.runSeconds))
                }
                report.failures.forEach { println("failed and given to another: $it") }
                println("all ${job.lots} lots answered in ${report.took}; ${report.givenAgain} given out again")
                val outputs = set.need("outputs-per").toInt()

                // Whether the answers are, to the last bit, the numbers in a file.
                fun same(check: String): Boolean {
                    // Lot by lot, so that a big job's answers are never all in memory twice.
                    var same = Files.size(Path.of(check)) == job.scenarios * outputs * 8
                    for (lot in 0 until job.lots) {
                        val count = job.count(lot) * outputs
                        val ours = readNumbers(job.answers(lot), 0, count)
                        val theirs = readNumbers(Path.of(check), job.first(lot) * outputs, count)
                        same = same && ours.indices.all { ours[it].toRawBits() == theirs[it].toRawBits() }
                    }
                    println(
                        if (same) "the answers are the same to the last bit as $check" else "THE ANSWERS DIFFER from $check",
                    )
                    return same
                }

                var ok = set["check"]?.let(::same) ?: true
                set["patch"]?.let { patch ->
                    val refreshed = coordinator.refresh(job, Path.of(patch))
                    println()
                    println("brought up to date with $patch:")
                    for (lot in refreshed.lots) {
                        println("%5d  %-34s %4s %9.3fs %9.3fs".format(lot.lot, lot.worker, lot.on, lot.loadSeconds, lot.runSeconds))
                    }
                    refreshed.failures.forEach { println("failed: $it") }
                    println("${refreshed.lots.size} lots brought up to date in ${refreshed.took}; lost: ${refreshed.lost}")
                    ok = ok && refreshed.lost.isEmpty()
                    set["check-after"]?.let { ok = same(it) && ok }
                }
                ok
            }
            exitProcess(if (ok) 0 else 1)
        }

        else -> {
            println(USAGE)
            exitProcess(2)
        }
    }
}
