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

## How it fits together

```
native/    the engine as a C library (Rust): about thirty plain functions
core/      one Java class, Native, that calls them, and Wire, which writes
           the bytes that cross
kotlin/    the typed layer for Kotlin, and its tests
scala/     the typed layer for Scala 3, and its tests
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
- Only tried on macOS (arm64).
- In Kotlin, `Collection` shares a name with Kotlin's own, which wins when
  the type is written out: say `dev.surefold.watershed.Collection`, or let
  it be inferred.
