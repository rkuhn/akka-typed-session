name := "akka-typed-session"
version := "0.1.0-SNAPSHOT"
organization := "com.rolandkuhn"
scalaVersion := "2.12.1"

libraryDependencies ++= Seq(
  "com.chuusai" %% "shapeless" % "2.3.2",
  "com.typesafe.akka" %% "akka-typed-experimental" % "2.5-M1"
)
