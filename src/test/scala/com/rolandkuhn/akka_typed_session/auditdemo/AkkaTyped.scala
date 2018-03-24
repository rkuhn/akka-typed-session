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
  import akka.actor.typed.scaladsl.Behaviors
  val behavior =
    Behaviors.receive[Greet] { (_, greet) =>
      println(s"Hello ${greet.whom}!")
      Behaviors.same
    }
}
