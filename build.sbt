name := "akka-typed-session"
version := "0.1.0-SNAPSHOT"
organization := "com.rolandkuhn"
scalaVersion := "0.5.0-RC1"

scalacOptions += "-deprecation"
logBuffered in Test := false

val akkaVersion = "2.5.8"

libraryDependencies ++= Seq(
  "com.chuusai" %% "shapeless" % "2.3.2",
  "com.typesafe.akka" %% "akka-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-typed-testkit" % akkaVersion % "test",
  "org.scalatest" %% "scalatest" % "3.0.4" % "test",
  "org.scalacheck" %% "scalacheck" % "1.13.4" % "test"
).map(lib => lib.withDottyCompat())

sources in Test := List()
