# watershed-jvm

[watershed](https://github.com/surefold/watershed) for the JVM: the
incremental calculation engine, called from Kotlin and Scala 3. Change an
input, and only what depends on it is recalculated.

```kotlin
val g = Graph()
val qty = g.input("qty", 10.0)                     // Input<Double>
val price = g.input("price", 2.5)
val total = g.define("total", qty * price * 1.2)   // Node<Double>

g[price] = 3.0
g.stabilize()                    // recalculates total, nothing else
val now: Double = g[total]       // 36.0
```

```scala
import watershed.*

val g = Graph()
val qty = g.input("qty", 10.0)                     // Input[Double]
val price = g.input("price", 2.5)
val big = g.define("big", qty * price > 20 && qty < 100)   // Node[Boolean]

g(price) = 3.0
g.stabilize()
```

## Try it

```bash
sbt test                  # builds everything and runs every test
sbt exampleKotlin/run     # a small Kotlin program
sbt exampleScala/run      # the same in Scala
sbt benchmark             # times the engine's benchmark graph from Kotlin
```

It needs Rust (`cargo`), a JDK from 22 up, and sbt. The engine's repository
is private, so `git` on the machine must be able to fetch it.

## Typed, with nothing written as text

An expression has a type, as in the engine's Rust API. These do not compile
(the Scala tests check that they do not):

- `qty + region`: a number plus a string
- `qty * count`: a `Double` times a `Long`
- `net * qty`: a `BigDecimal` times a `Double`
- setting a calculated node, or setting a `Double` input to a string

The types are `Double`, `Long`, `BigDecimal`, `Boolean`, `String` and a list
of strings. A whole number beside a number expression is taken as that kind
of number (`price * 12`, `1 + taxRate`).

| | Kotlin | Scala 3 |
|---|---|---|
| set, read | `g[price] = 3.0`, `g[total]` | `g(price) = 3.0`, `g(total)` |
| compare | `a gt b`, `lt`, `le`, `ge` | `a > b`, `<`, `<=`, `>=` |
| equal | `a eq b`, `a ne b` | `a === b`, `a =!= b` |
| logic | `a and b`, `a or b`, `!a` | `a && b`, `a \|\| b`, `!a` |
| join text | `a + b` | `a ++ b` |
| a row's field | `orders.upsert("o-1", qty(2))` | `orders.upsert("o-1", qty := 2)` |
| conditional | `whenever(condition, then, otherwise)` | the same |

Kotlin's `<` and `==` must give a `Boolean`, so its comparisons are words.
In Scala only equality needs its own spelling.

### Decimals

A `BigDecimal` is exact, for money: `(net * vat).round(2)` puts a value on
two places, a tie going to the even neighbour (or name another
`RoundingMode`). It does not mix with a `Double` unless you say `toDouble`.
The engine holds 28 significant digits; a `BigDecimal` with more is refused,
not rounded.

### Collections

A keyed table of rows whose totals update by only the rows that change:

```kotlin
val orders = g.collection("orders")
val units = orders.number("units")                 // Field<Double>
val unitPrice = orders.decimal("unit price")       // Field<BigDecimal>
val line = orders.column("line", units * 2.0)
val revenue = orders.sum("revenue", line)          // Node<Double>
orders.upsert("o-1", units(2), unitPrice(BigDecimal("9.99")))
orders.upsertAll(listOf(row("o-2", units(1)), row("o-3", units(5))))
```

### When something goes wrong

A node that could not be calculated (a division by zero, say) makes reading
it throw a `WatershedException` with the reason; `g.getOrNull(node)` in
Kotlin and `g.get(node)` in Scala give `null` or `None` instead. Anything
the engine refuses is a `WatershedException` too.

## How fast

`sbt benchmark` builds the engine's benchmark graph (about a million nodes)
from Kotlin and times the same updates as the engine's own Rust benchmark.
On one machine, one after the other, they are level: one price changing
takes about 95 µs either way.

What one call costs from Kotlin:

| | set | read |
|---|---|---|
| a `Double` | 125 ns | 42 ns |
| a `Long` | 41 ns | 41 ns |
| a `BigDecimal` | 83 ns | 250 ns |
| a `String` | 250 ns | 125 ns |

An update with nothing to do is 42 ns. (The clock on that machine ticks
every 41 ns, so the smallest figures are "one tick".) Adding a node is
about 0.8 µs, and a row of two fields and a column about 1.2 µs.

## Many scenarios at once (experimental)

Kotlin only so far. A graph's plan runs it for many sets of inputs at once,
on all CPU cores or an NVIDIA GPU, and gives what the graph gives for each,
to the last bit. It is the engine's
[scenario plans](https://github.com/surefold/watershed/blob/main/crates/watershed-gpu/README.md),
with the same limits on what a plan can hold.

```kotlin
val g = Graph()
val rate = g.input("rate", 0.01)
val fee = g.input("fee", 0.002)
val worth = g.define("worth", (rate - fee + 1.0) * 100.0)
g.stabilize()

val plan = g.plan(vary = listOf(rate), outputs = listOf(worth), shared = listOf(fee))
val answers: DoubleArray = plan.runCpu(doubleArrayOf(0.01, 0.02, 0.03))   // one answer a scenario
val onGpu = Gpu().run(plan, doubleArrayOf(0.01, 0.02, 0.03))               // needs a build with a GPU

plan.save(Path.of("worth.plan"))             // for another process, or another machine
val back = Plan.load(Path.of("worth.plan"))  // gives the same answers
```

The native library is built without a GPU unless asked: `WATERSHED_CUDA=1 sbt
test` builds it against CUDA 13.1 (NVIDIA driver 580 or later), and
`WATERSHED_CUDA=12` against CUDA 12.8 (driver 570 or later). Built for a GPU,
the tests run a plan on it and check it against the CPU.

## A cluster (experimental)

`cluster/` cuts a run of many scenarios into lots and gives them to the
workers of a [Surefold Core](https://github.com/surefold/surefold-core)
cluster. A coordinator keeps the lots not yet answered and gives one to each
idle worker; a worker runs a lot on its GPU, or its CPU, and writes the
answers to a file. A lot whose worker leaves, cannot be reached, fails or
takes too long is given to another. Scenarios do not depend on one another,
so the answers put together are the same, to the last bit, as one run of them
all.

Plans are big (2 GB for a graph of 20 million nodes), so they do not cross
between nodes: only "run scenarios 2,500 to 3,749 of this plan" does. Every
node must be able to read the plan and the scenarios and write the answers
under the same paths, which on one machine is a folder and across machines
is a shared volume.

```kotlin
// On each worker:
val node = node("worker-1", 25520, host = "10.0.0.8")
val cluster = cluster(node, Discovery.static("10.0.0.7:25520"), roles = setOf(WORKER), ready = Ready.WhenTold)
worker(node)
cluster.admit()

// On the coordinator:
val coordinator = coordinator(node, cluster)
val report = coordinator.run(Job("book", plan, scenarios, inputsPerScenario = 240, lot = 1_250, out = folder))
```

`cluster/run-local.sh` starts a coordinator and workers as separate processes
on one machine, and `cluster/image.sh` builds one container image for every
node, which `cluster/run-containers.sh` runs as containers on a Docker
network.

What has been tried, all on one machine with one RTX 3090, on the engine's
insurance book (20,000 kinds of policy, 19.9 million nodes, 10,000 market
paths, in lots of 1,250), one run of each:

| | Time | Answers |
|---|---|---|
| One run in one process, no cluster | 11.6 s | the reference |
| One GPU worker | 17.7 s, 6 s of it reading the plan | the same to the last bit |
| Two GPU workers sharing the card | 23.5 s | the same |
| Four GPU workers sharing the card | 22.4 s and 32.4 s (two runs) | the same |
| Three GPU workers, one killed part way (lots of 625) | 21.8 s, one lot given out again | the same |
| One GPU worker, built against CUDA 12.8 | 20.0 s | the same |

Workers that share one card take turns on it, so more of them is no faster
here: this shows that the answers are right and what the cluster costs, not
a speed-up. **It has not been run on more than one machine, or on more than
one GPU.** A small book (500 kinds of policy, 400 paths) gave the same
answers from two GPU workers and a CPU worker together, and from CPU workers
in three containers.

### Bringing a run up to date after a change

A job can ask each worker to hold on to the lots it runs: every kept value
of every scenario, in the worker's memory. When an input of the graph
changes, the engine's `Kept` gives a patch, a small plan of what the change
reaches, and `coordinator.refresh` has every worker run it on the lots it
holds and write their answers again. Nothing is run again but that.

```kotlin
val kept = g.kept(vary = paths, outputs = totals, keep = groupSums)
kept.plan.save(planFile)
val job = Job("book", planFile, scenarios, 240, 1_250, folder, columns = kept.outputColumns.toList(), hold = true)
coordinator.run(job)

g[promised] = 1.1 * was          // one policy changes
g.stabilize()
kept.patch(listOf(promised))!!.save(patchFile)
val report = coordinator.refresh(job, patchFile)   // report.lost: lots whose worker has gone
```

On the same insurance book, holding 11,436 values a path (915 MB in all),
after one policy's promised amount changes, one run of each:

| | First run | Brought up to date | Answers after |
|---|---|---|---|
| The engine alone, in one process | 11.2 s | 0.14 to 0.22 s | the reference |
| One GPU worker | 21.1 s | 0.41 s | the same to the last bit |
| Two GPU workers sharing the card | 22.4 s | 0.37 s | the same |

Both tables were measured before the coordinator and the worker were rewritten
as actors. After the rewrite the same kinds of run gave the same answers, to
the last bit, in about the same time: 16.3 s with one GPU worker, 20.3 s with
two, and 0.24 s to bring a run held by two workers up to date (one run of
each).

The patch is 1 MB and works out 9,327 nodes for each path, of 19.9 million.
A lot whose worker has gone since the first run cannot be brought up to
date: it is left as it was and listed in the report, and
`coordinator.run(job, only = report.lost)`, with a plan made after the
change, runs just those again. The tests do that. As with the engine's
`Kept`, this helps only a change that reaches a small part of the model.

Not done: a job is lost if the coordinator stops; the files must be on
storage every node reaches, and nothing here copies them; one job runs at a
time; what a worker holds is in its memory only, and is gone if it stops.

## How it fits together

```
native/    the engine as a C library (Rust): about thirty plain functions
core/      one Java class, Native, that calls them, and Wire, which writes
           the bytes that cross
kotlin/    the typed layer for Kotlin, and its tests
scala/     the typed layer for Scala 3, and its tests
cluster/   a run's lots given to the workers of a Surefold Core cluster
examples/  a small program in each, and the benchmark
```

- `native` depends on the engine at a commit of its own repository (see
  `native/Cargo.toml`). To take a newer engine, move `rev` forward and run
  `sbt test`.
- The JVM calls the C functions directly, through its foreign function API
  (`java.lang.foreign`), which is why it needs JDK 22 or later and
  `--enable-native-access`. Numbers cross in registers; a name, an
  expression or a batch of rows crosses as bytes.
- All the unsafe Rust is in `native/src/ffi.rs`, and its first comment says
  what the JVM side must get right. A graph or collection is freed when its
  Kotlin or Scala object can no longer be reached.
- `build.sbt` runs `cargo build --release` before any program or test, and
  tells the JVM where the library is with `-Dwatershed.library.path`.

## Not done yet

- Groupings and history (`explain` as of an earlier update), which the
  engine has.
- Jars with the native library inside for each platform. Today a program
  outside this build needs the built library and
  `-Dwatershed.library.path`.
- Tried on macOS (arm64) and Linux (x86-64, under WSL2).
- Plans and the cluster from Scala.
- In Kotlin, `Collection` shares a name with Kotlin's own, which wins when
  the type is written out: say `dev.surefold.watershed.Collection`, or let
  it be inferred.
