// watershed for the JVM: one build for the native library, the Java core,
// and the typed layers for Kotlin and Scala 3.
//
//     sbt test                    builds everything and runs every test
//     sbt exampleKotlin/run       a small Kotlin program
//     sbt exampleScala/run        the same in Scala
//     sbt benchmark               times the engine's benchmark graph from Kotlin
//
// It needs Rust (cargo), a JDK (22 or later) and sbt.

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
  // Set WATERSHED_CUDA to build it able to run plans on an NVIDIA GPU.
  val cuda = if (sys.env.contains("WATERSHED_CUDA")) Seq("--features", "watershed-jvm/cuda") else Seq()
  if (Process(Seq("cargo", "build", "--release", "--locked") ++ cuda, root).!(log) != 0)
    sys.error("building the native library failed")
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
  Test / test := (Test / test).dependsOn(ThisBuild / buildNative).value,
)

// The calls use the JDK's foreign function API, which is in JDK 22 and later.
val jdk = Seq(javacOptions ++= Seq("--release", "22"))

val kotlinModule = Seq(
  kotlinVersion := kotlin,
  kotlincJvmTarget := "22",
  kotlinLib("stdlib"),
  // No Scala here.
  autoScalaLibrary := false,
  crossPaths := false,
)

// One Java class that calls the C library, and the bytes that cross to it.
lazy val core = (project in file("core"))
  .settings(jdk)
  .settings(
    name := "watershed-core",
    autoScalaLibrary := false,
    crossPaths := false,
  )

// What a Kotlin program uses.
lazy val kotlinLayer = (project in file("kotlin"))
  .enablePlugins(KotlinPlugin)
  .dependsOn(core)
  .settings(jdk, kotlinModule, native)
  .settings(
    name := "watershed-kotlin",
    libraryDependencies ++= Seq(
      "org.jetbrains.kotlin" % "kotlin-test-junit5" % kotlin % Test,
      "com.github.sbt.junit" % "jupiter-interface" % "0.19.0" % Test,
    ),
  )

// What a Scala program uses.
lazy val scalaLayer = (project in file("scala"))
  .dependsOn(core)
  .settings(jdk, native)
  .settings(
    name := "watershed-scala",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Werror"),
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test,
  )

lazy val exampleKotlin = (project in file("examples/kotlin"))
  .enablePlugins(KotlinPlugin)
  .dependsOn(kotlinLayer)
  .settings(jdk, kotlinModule, native)
  .settings(
    name := "watershed-example-kotlin",
    Compile / mainClass := Some("MainKt"),
    publish / skip := true,
  )

lazy val exampleScala = (project in file("examples/scala"))
  .dependsOn(scalaLayer)
  .settings(jdk, native)
  .settings(
    name := "watershed-example-scala",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Werror"),
    publish / skip := true,
  )

lazy val root = (project in file("."))
  .aggregate(core, kotlinLayer, scalaLayer, exampleKotlin, exampleScala)
  .settings(
    name := "watershed-jvm",
    publish / skip := true,
  )

// `sbt benchmark`: the engine's benchmark graph, built and updated from Kotlin.
addCommandAlias("benchmark", "exampleKotlin/runMain BenchmarkKt")
