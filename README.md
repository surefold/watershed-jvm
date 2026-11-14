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

It needs Rust (`cargo`), JDK 25 and sbt. The engine's repository
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

In Kotlin nothing throws. A call the engine can refuse (defining a node,
setting an input, reading a value, running a plan) is made in a scope that
declares the failure, using Surefold's `Fail`:

```kotlin
// attempt opens a scope and answers how it ended.
val total: Either<Refused, Double> = attempt {
    val g = Graph()
    val qty = g.input("qty", 10.0)
    val price = g.input("price", 2.5)
    g[g.define("total", qty * price)]
}

// A function that calls the engine takes the scope of whoever calls it.
context(_: Fail<Refused>)
fun reprice(g: Graph, price: Input<Double>, to: Double) {
    g[price] = to
    g.stabilize()
}
```

A node that could not be calculated (a division by zero, say) makes reading
it a `Refused` with the reason, as is anything else the engine will not do.
A scope costs nothing when nothing is refused: a set is 15 ns and a read
10 ns in a loop of twenty million, the same as before the scope was there.

Scala is the same, with a `using` clause where Kotlin has a context:

```scala
val total: Either[Refused, Double] = attempt:
  val g = Graph()
  val qty = g.input("qty", 10.0)
  val price = g.input("price", 2.5)
  g(g.define("total", qty * price))

def reprice(g: Graph, price: Input[Double], to: Double)(using Refusing): Unit =
  g(price) = to
  g.stabilize()
```

Neither layer has a `throw`, a `try` or a `var` in it. A library that cannot
be found, or was built from another version of the engine, is a `Refused`
from the first call, not an error at startup.

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
val onGpu = Gpu.open().run(plan, doubleArrayOf(0.01, 0.02, 0.03))               // needs a build with a GPU

plan.save("worth.plan")                      // for another process, or another machine
val back = Plan.load("worth.plan")           // gives the same answers
val bytes = plan.saved()                     // or as bytes, read a part at a time, to keep anywhere
```

The native library is built without a GPU unless asked: `WATERSHED_CUDA=1 sbt
test` builds it against CUDA 13.1 (NVIDIA driver 580 or later), and
`WATERSHED_CUDA=12` against CUDA 12.8 (driver 570 or later). Built for a GPU,
the tests run a plan on it and check it against the CPU.

## A cluster (experimental)

`cluster/` cuts a run of many scenarios into lots and runs them on the nodes
of a [Surefold](https://github.com/surefold/surefold-core) cluster. Scenarios
do not depend on one another, so the answers put together are the same, to
the last bit, as one run of them all. It is built from what Surefold's
cluster already has:

- **A lot is a sharded entity.** It is an actor with an id, and the cluster
  decides which worker runs it and moves it to another when that worker
  goes. Nothing here assigns lots to workers. The coordinator asks where an
  id would land before it uses it, and picks ids that spread a job's lots
  evenly: ten lots over three workers is three, three and four.
- **The coordinator is a singleton**: one actor in the cluster, on the oldest
  node with the `coordinator` role. A job is submitted from any node. If the
  coordinator's node stops, the next one takes over, whoever submitted the
  job tells it to start again, and the lots that had already run say so
  without running again.
- **A worker runs one lot at a time**, on its GPU or its CPU, through one
  actor on each node that every lot of that node queues behind.
- **Files are in a bucket**, through `surefold-files`: any S3-compatible
  store (AWS, Cloudflare R2, MinIO and the like). A plan is big (2 GB for a
  graph of 20 million nodes), so it never crosses as a message: a lot is told
  the plan's key, and its worker fetches it once, as bytes, straight into
  the engine. Nodes share nothing but the bucket and the cluster, and keep
  nothing on a disk of their own.

```kotlin
// On every node, after joining the cluster with the roles it is to have:
val cluster = cluster(node, Discovery.static("10.0.0.7:25520"), roles = setOf(WORKER), ready = Ready.WhenTold)
val store = Store(S3Files.of(FilesConfig(bucket = "runs", region = "auto", accessKey = key, secretKey = secret, endpoint = r2)))
val jobs = jobs(cluster, store, working = Working()).bind()     // Either<NoGpu, Jobs>; working = null on a node that runs no lots
cluster.admit()

// From any of them:
val report: Either<JobFailure, Report> = attempt {
    val job = Job.read(store, "book", plan = "book/plan", inputs = "book/paths", inputsPerScenario = 240, lot = 1_250, out = "book/out").bind()
    jobs.run(job).bind()
}
```

Nothing in it throws: a bad job, a file that cannot be read, a lot that keeps
failing and a run out of time are each a case of `JobFailure`.

A lot whose worker stops is run by another once the cluster has given up on
that worker. A lot that runs longer than it is allowed, on a worker that has
hung but not stopped, is given out again under another id, with every lot
waiting behind it. A lot that fails is tried on other workers, and fails the
job the third time.

A node is a Surefold application: a graph of what it is made of, built from
configuration and started and released by `runApp`. It joins the cluster as
its `watershed.cluster` section says, through `surefold-app-cluster`, and is
admitted once everything in it is built. `cluster/src/main/resources/reference.conf`
has every setting; each is given in an `application.conf`, as a system
property, or, for the bucket's keys and a few others, from the environment.

```
java -Dwatershed.cluster.roles.0=worker -Dwatershed.cluster.static.seeds.0=10.0.0.7:25520 ... AppKt worker
java -Dwatershed.cluster.roles.0=coordinator -Dwatershed.job.plan=book/plan -Dwatershed.job.lot=1250 ... AppKt submit
java -Dwatershed.put.from=worth.plan -Dwatershed.put.key=book/plan ... AppKt put
```

`worker` runs lots until it is stopped; `submit` runs the job in
`watershed.job`, logs how it went and leaves; `put` copies a file of this
machine into the bucket. `cluster/run-local.sh` starts them and an S3 server
as separate processes on one machine; `cluster/image.sh` builds one container
image for every node, which `cluster/run-containers.sh` runs as containers
on a Docker network with the S3 server beside them.

### Bringing a run up to date after a change

A job can ask each lot to hold on to what it ran: every kept value of every
scenario, in its worker's memory. When an input of the graph changes, the
engine's `Kept` gives a patch, a small plan of what the change reaches, and
`jobs.refresh` has every lot run it on what it holds and write its answers
again. Nothing is run again but that. The patch reaches the right worker
because it is told to the lot, by its id.

```kotlin
val kept = g.kept(vary = paths, outputs = totals, keep = groupSums)
store.put("book/plan", kept.plan.saved()).bind()
val job = Job.read(store, "book", "book/plan", "book/paths", 240, 1_250, "book/out", columns = kept.outputColumns.toList(), hold = true).bind()
jobs.run(job).bind()

g[promised] = 1.1 * was          // one policy changes
g.stabilize()
kept.patch(listOf(promised))?.let { store.put("book/patch", it.saved()).bind() }
val report = jobs.refresh(job, "book/patch").bind()   // report.lost: lots whose worker has gone
```

A lot whose worker has gone since the first run holds nothing: it is left as
it was and listed in the report, and `jobs.run(job, only = report.lost)`,
with a plan made after the change, runs just those again. As with the
engine's `Kept`, this helps only a change that reaches a small part of the
model.

### What has been tried

All on one machine with one RTX 3090, with the S3 server on the same
machine, on the engine's insurance book (20,000 kinds of policy, 19.9
million nodes, 10,000 market paths), one run of each. Every run's answers
were the same, to the last bit, as one run in one process.

| | Time |
|---|---|
| One run in one process, no cluster | 11.6 s |
| Two GPU workers sharing the card, four lots | 24.6 s, 7 s of it each worker fetching and reading the plan |
| Three GPU workers, one killed part way | 39.8 s, 10 s of it waiting for the cluster to give up on the worker |
| A held run on two GPU workers | 28.5 s, 10 s of it each worker fetching the plan |
| That run brought up to date after one policy changes | 0.33 s |

Workers that share one card take turns on it, so more of them is no faster
here: this shows that the answers are right and what the cluster costs, not
a speed-up. **It has not been run on more than one machine, or on more than
one GPU, or against a bucket over a real network.** A small book (500 kinds
of policy, 400 paths) gave the same answers from CPU workers in three
containers that shared only the bucket, held and brought up to date
included.

### What to know before relying on it

- **A worker that joins takes some lots from the others.** That is how the
  cluster's sharding places things. A job waits until the workers have been
  the same for five seconds before it starts; a worker that joins while it
  runs has some lots run again on it, and a held lot that moves is lost, as
  if its worker had gone.
- **A killed worker's lots wait for the cluster to give up on it** (ten
  seconds as the command line sets it) before another runs them.
- What a lot holds is in its worker's memory only.
- One job runs at a time.
- Lots are spread by count, not by how fast a worker is.

## How it fits together

```
native/    the engine as a C library (Rust): about thirty plain functions
core/      Native, which calls them, and Wire, which writes the bytes that
           cross: Kotlin, with what the engine refuses declared as a failure
kotlin/    the typed layer for Kotlin, and its tests
scala/     the typed layer for Scala 3, and its tests
cluster/   a run cut into lots, on a Surefold cluster, its files in a bucket
examples/  a small program in each, and the benchmark
```

- `native` depends on the engine at a commit of its own repository (see
  `native/Cargo.toml`). To take a newer engine, move `rev` forward and run
  `sbt test`.
- The JVM calls the C functions directly, through its foreign function API
  (`java.lang.foreign`), which is why it needs `--enable-native-access`.
  It is built for JDK 25 because Surefold, which the failures are declared
  with, is. Numbers cross in registers; a name, an
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
- In Kotlin, `Collection` shares a name with Kotlin's own, which wins when
  the type is written out: say `dev.surefold.watershed.Collection`, or let
  it be inferred.
