/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session.auditdemo

case class Greet(whom: String)

class Greeter extends akka.actor.Actor {
  var count = 1
  def receive = {
    case Greet(whom) =>
      println(s"Hello $whom, you’re #$count!")
      count += 1
  }
}

object Greeter {
  import akka.typed.Behavior, akka.typed.scaladsl.Actor

  val behavior = init(1)

  private def init(count: Int): Behavior[Greet] =
    Actor.immutable { (ctx, greet) =>
      println(s"Hello ${greet.whom}, you’re #$count!")
      init(count + 1)
    }
}
