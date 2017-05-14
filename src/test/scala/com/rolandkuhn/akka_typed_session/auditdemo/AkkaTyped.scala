/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session.auditdemo

case class Greet(whom: String)

class Greeter extends akka.actor.Actor {
  def receive = {
    case Greet(whom) => println(s"Hello $whom!")
  }
}

object Greeter {
  import akka.typed.scaladsl.Actor
  val behavior =
    Actor.immutable[Greet] { (ctx, greet) =>
      println(s"Hello ${greet.whom}!")
      Actor.same
    }
}
