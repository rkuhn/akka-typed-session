name := "akka-typed-session"
version := "0.1.0-SNAPSHOT"
organization := "com.rolandkuhn"
scalaVersion := "2.12.5"

//scalacOptions += "-deprecation"
logBuffered in Test := false

// Update to 2.5.12 once released. Current SNAPSHOT has many API changes over 2.5.11
val akkaVersion = "2.5-SNAPSHOT"

resolvers += "Akka Snapshot Repository" at "http://repo.akka.io/snapshots/"

libraryDependencies ++= Seq(
  "com.chuusai" %% "shapeless" % "2.3.3",
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit-typed" % akkaVersion % "test",
  "org.scalatest" %% "scalatest" % "3.0.1" % "test",
  "org.scalacheck" %% "scalacheck" % "1.13.4" % "test"
)
