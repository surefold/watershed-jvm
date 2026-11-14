// watershed for the JVM: one build for the native library, the Kotlin core,
// and the typed layers for Kotlin and Scala 3.
//
//     sbt test                    checks the source against AGENTS.md, builds everything and runs every test
//     sbt checkHygiene            that check alone
//     sbt exampleKotlin/run       a small Kotlin program
//     sbt exampleScala/run        the same in Scala
//     sbt benchmark               times the engine's benchmark graph from Kotlin
//
// It needs Rust (cargo), JDK 25 and sbt.

import org.jetbrains.sbt.kotlin.Keys._
import org.jetbrains.sbt.kotlin.KotlinPlugin
import scala.sys.process._

ThisBuild / organization := "dev.surefold"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.10.1"

val kotlin = "2.4.21"

// The engine as a C library, built by cargo into target/release.
val buildNative = taskKey[Unit]("Builds the engine as the C library the JVM calls.")
ThisBuild / buildNative := {
  val root = (ThisBuild / baseDirectory).value
  val log = streams.value.log
  // Set WATERSHED_CUDA to build it able to run plans on an NVIDIA GPU: to 12 for CUDA 12.8 (NVIDIA driver 570 or
  // later), or to anything else for CUDA 13.1 (driver 580 or later).
  val cuda = sys.env.get("WATERSHED_CUDA") match {
    case Some("12") => Seq("--features", "watershed-jvm/cuda-12")
    case Some(_) => Seq("--features", "watershed-jvm/cuda")
    case None => Seq()
  }
  if (Process(Seq("cargo", "build", "--release", "--locked") ++ cuda, root).!(log) != 0)
    sys.error("building the native library failed")
}

// The rules in AGENTS.md that a machine can check, checked before any test runs: see project/Hygiene.scala.
val checkHygiene = taskKey[Unit]("Refuses source that does by hand what Surefold does, or that is Java.")
ThisBuild / checkHygiene := {
  val faults = Hygiene.faults((ThisBuild / baseDirectory).value, Seq("core", "kotlin", "scala", "cluster", "examples"))
  if (faults.nonEmpty) {
    faults.foreach(fault => streams.value.log.error(fault))
    throw new MessageOnlyException(s"${faults.size} lines break the rules in AGENTS.md; a line that must is marked `hygiene: ` with why")
  }
}

// Every program and test runs in its own JVM, told where the library is
// and given leave to call into it.
val native = Seq(
  fork := true,
  javaOptions ++= Seq(
    s"-Dwatershed.library.path=${(ThisBuild / baseDirectory).value / "target" / "release"}",
    "--enable-native-access=ALL-UNNAMED",
  ),
  Compile / run := (Compile / run).dependsOn(ThisBuild / buildNative).evaluated,
  Compile / runMain := (Compile / runMain).dependsOn(ThisBuild / buildNative).evaluated,
  Test / test := (Test / test).dependsOn(ThisBuild / buildNative, ThisBuild / checkHygiene).value,
)

// Everything is built for Java 25: the calls use the JDK's foreign function API (JDK 22 and later), and Surefold,
// which every failure here is declared with, is built for 25.
val jdk = Seq(javacOptions ++= Seq("--release", "25"))

val surefold = "0.18.1"

val kotlinModule = Seq(
  kotlinVersion := kotlin,
  kotlincJvmTarget := "25",
  kotlinLib("stdlib"),
  // No Scala here.
  autoScalaLibrary := false,
  crossPaths := false,
)

// The suites are surefold-junit's: each an object, found by Surefold's own JUnit Platform engine.
val suites = Seq(
  libraryDependencies ++= Seq(
    "dev.surefold" % "surefold-junit" % surefold % Test,
    "com.github.sbt.junit" % "jupiter-interface" % "0.19.0" % Test,
  ),
)

// The engine's C functions, called from Kotlin, and the bytes that cross to it.
lazy val core = (project in file("core"))
  .enablePlugins(KotlinPlugin)
  .settings(jdk, kotlinModule)
  .settings(
    name := "watershed-core",
    libraryDependencies += "dev.surefold" % "surefold" % surefold,
  )

// What a Kotlin program uses.
lazy val kotlinLayer = (project in file("kotlin"))
  .enablePlugins(KotlinPlugin)
  .dependsOn(core)
  .settings(jdk, kotlinModule, native, suites)
  .settings(name := "watershed-kotlin")

// What a Scala program uses.
lazy val scalaLayer = (project in file("scala"))
  .dependsOn(core)
  .settings(jdk, native)
  .settings(
    name := "watershed-scala",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Werror"),
    libraryDependencies ++= Seq(
      // The core declares its failures with Surefold's types, which this layer turns into its own exception.
      "dev.surefold" % "surefold" % surefold,
      "org.scalameta" %% "munit" % "1.3.6" % Test,
    ),
  )

// EXPERIMENTAL. A run of many scenarios cut into lots and given to the workers of a Surefold Core cluster.
lazy val cluster = (project in file("cluster"))
  .enablePlugins(KotlinPlugin)
  .dependsOn(kotlinLayer)
  .settings(jdk, kotlinModule, native, suites)
  .settings(
    name := "watershed-cluster",
    libraryDependencies ++= Seq(
      "dev.surefold" % "surefold-cluster" % surefold,
      // A node is a Surefold application: a graph built from configuration, its cluster joined as a section says.
      "dev.surefold" % "surefold-app" % surefold,
      "dev.surefold" % "surefold-app-actor" % surefold,
      "dev.surefold" % "surefold-app-typesafe" % "0.18.0",
      "dev.surefold" % "surefold-app-cluster" % "0.18.0",
      // A bucket every node reaches. Surefold's platform modules are published at 0.18.0, one behind its core.
      "dev.surefold" % "surefold-files" % "0.18.0",
      // Surefold's own test fixtures for nodes: a port nothing listens on, for a node a test starts.
      "dev.surefold" % "surefold-actor-remote" % surefold % Test classifier "test-fixtures",
    ),
    dependencyOverrides ++= Seq("surefold", "surefold-actor", "surefold-actor-remote", "surefold-cluster", "surefold-app", "surefold-app-actor")
      .map("dev.surefold" % _ % surefold),
  )

lazy val exampleKotlin = (project in file("examples/kotlin"))
  .enablePlugins(KotlinPlugin)
  .dependsOn(kotlinLayer)
  .settings(jdk, kotlinModule, native)
  .settings(
    name := "watershed-example-kotlin",
    libraryDependencies += "dev.surefold" % "surefold" % surefold,
    Compile / mainClass := Some("MainKt"),
    publish / skip := true,
  )

lazy val exampleScala = (project in file("examples/scala"))
  .dependsOn(scalaLayer)
  .settings(jdk, native)
  .settings(
    name := "watershed-example-scala",
    libraryDependencies += "dev.surefold" % "surefold" % surefold,
    scalacOptions ++= Seq("-deprecation", "-feature", "-Werror"),
    publish / skip := true,
  )

lazy val root = (project in file("."))
  .aggregate(core, kotlinLayer, scalaLayer, cluster, exampleKotlin, exampleScala)
  .settings(
    name := "watershed-jvm",
    publish / skip := true,
  )

// `sbt benchmark`: the engine's benchmark graph, built and updated from Kotlin.
addCommandAlias("benchmark", "exampleKotlin/runMain BenchmarkKt")
