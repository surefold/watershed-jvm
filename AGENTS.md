# Instructions for anyone writing code here, people and agents alike

This repository is written on [Surefold](https://github.com/surefold/surefold-core). Surefold is the house style for
everything, not a library called for one job: errors, concurrency, tests, configuration, logging and files all go
through it. Code that works but does by hand what Surefold ships is wrong here, and `sbt test` refuses the part of
that a machine can see.

## Read before writing

Do this first, every time, and read the source repositories, not only the published jars. A jar does not show a
module's test fixtures, its guides, or the modules beside it.

1. **Surefold core**, `surefold/surefold-core`: `llms.txt` (written for agents; its "What to reach for" table names
   what to use for each want), then `docs/core.md`, `docs/app.md`, `docs/cookbook.md`, `docs/actors.md`,
   `docs/cluster.md` and `docs/testing.md` as the task needs.
2. **Surefold integrations**, `surefold/surefold-integrations`: configuration, a cluster joined from config, the remote
   codecs, observability. Anything that wraps a library outside the JDK and Kotlin is here.
3. **Surefold platform**, `surefold/surefold-platform`: files (S3), jobs, and the rest of what a service needs.
4. **Doorlist's standards**, `surefold/doorlist`: `CODING_STANDARDS.md`, `docs/agents/kotlin.md` and
   `docs/agents/testing.md`, and a suite or two of its tests. Doorlist is the reference for how Surefold is used
   properly; where this file is silent, do as Doorlist does.

Before writing a helper, look for it: in those repositories' `src/main`, in their `src/testFixtures` (published
with the classifier `test-fixtures`), and in their docs. If Surefold has it, use it.

## The rules

- **A failure is a value.** No `throw`, no `try`/`catch`, no `require`, `check`, `error`, `!!` or `runCatching`. What
  can go wrong is declared: `attempt { }`, `bind()`, `ensure`, `ensureNotNull`, and a function that can fail takes its
  scope from context, `context(_: Fail<E>)` in Kotlin and `(using Refusing)` in Scala. What a library throws is caught
  once, with `Either.catch { }.mapLeft { }`. Null does not mean failure.
- **No hand-made concurrency**, in main or test code: no `Thread`, executor, lock, atomic, `ConcurrentHashMap`,
  `CountDownLatch`, `CompletableFuture`, `ThreadLocal` or `@Volatile`. State lives in an actor and changes a message
  at a time; a deadline is an actor's timer; blocking work is a child actor's turn; waiting is a `Shared`, a `Promise`
  or an `ask`; work alongside is a fork of a `scope`; something tried again is a `Schedule`.
- **No `var`.** A fold, recursion, immutable actor state, or a `Shared`.
- **No Java source files, and no raw Java where Kotlin or Surefold has it.** No `java.io`, `java.nio`, `java.time` or
  `java.util.concurrent`. What is left of `java.*`, and why, is short: the foreign function API and `Cleaner` in
  `Native.kt`, the only way to call the engine and free what it gave; `BigDecimal`, the decimal type the API takes;
  and `java.net.URI`, which Surefold's own `FilesConfig` takes.
- **A program is a Surefold application**: a `Module` graph run by `runApp`, its settings read by `config` from
  `reference.conf`, a secret read as a `secret`, a cluster joined by `surefold-app-cluster`. No hand-parsed
  arguments, no `System.getenv` in a node.
- **Output is a log line**: `logInfo`, `logError`. No `println`.
- **Kotlin tests are surefold-junit suites**: an `object … : Suite()`, a `story` with Given, When and Then for
  anything that crosses nodes, Surefold's assertions (`shouldBe`, `shouldBeRight`), `eventually` and never a sleep,
  a port from Surefold's `freePort()`. No kotlin-test and no JUnit Jupiter annotations. The suite comes first in
  its file, its helpers below it.
- **A cluster is built from Surefold's cluster**: sharded entities, a singleton, membership. Nothing here assigns work
  to nodes by hand.
- **What crosses between nodes is a `@Serializable` class**, written by `surefold-actor-remote-kotlinx`'s `Kotlinx`
  codecs. No codec is written field by field. A message carries data, not who to answer: an actor that must be
  answered is reached by its name in the cluster.
- **An actor's logic is tested on Surefold's test actors** (`testActors`, `advance`): no nodes, no threads, no
  waiting. A test starts real nodes only for what real nodes are the point of.
- **Log lines go to SLF4J** through `surefold-slf4j`, with one backend for the program, so a library's lines and
  Surefold's come out in one place.
- **Comments say why**, in a line or two. They do not restate the code or tell its history.

## The check

`sbt checkHygiene` (and so `sbt test`) refuses a `.java` file under `src`, and any line of Kotlin or Scala that breaks
the rules a pattern can find; `project/Hygiene.scala` lists them. A line that truly has to break one carries
`hygiene: ` and the reason, on the line. Passing the check is not the same as following the rules: it cannot see a
hand-rolled retry, a helper Surefold already has, or a design that ignores sharding. Reading first is what does that.

## Before saying something is done

- `sbt test` passes: the check, then every suite.
- For the cluster, a real run still gives the same answers to the last bit: `cluster/run-local.sh`.
- What was measured is said as measured, with how many runs; what was not run is said as not run.
