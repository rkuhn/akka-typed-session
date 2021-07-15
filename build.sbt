name := "akka-typed-session"
version := "0.1.1-SNAPSHOT"
organization := "com.rolandkuhn"
scalaVersion := "2.12.14"

//scalacOptions += "-deprecation"
logBuffered in Test := false

val akkaVersion = "2.5.12"

libraryDependencies ++= Seq(
  "com.chuusai" %% "shapeless" % "2.3.7",
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit-typed" % akkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.0.9" % Test,
  "org.scalacheck" %% "scalacheck" % "1.13.5" % Test
)
