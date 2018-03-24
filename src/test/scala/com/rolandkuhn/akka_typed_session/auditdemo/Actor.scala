/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session.auditdemo

import java.net.URI
import akka.actor.typed.{ ActorRef, Behavior, Signal, Terminated }
import akka.actor.typed.scaladsl.{ Behaviors, ActorContext }
import scala.util.Random
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException
import java.util.UUID

object ActorBased {

  sealed trait Msg
  private case object AuditDone extends Msg
  private case object PaymentDone extends Msg

  def getMoney[R](from: URI, amount: BigDecimal,
                  payments: ActorRef[PaymentService], audit: ActorRef[LogActivity],
                  replyTo: ActorRef[R], msg: R) =
    Behaviors.setup[Msg] { ctx =>
      ctx.watch(ctx.spawn(doAudit(audit, ctx.self, "starting payment"), "preAudit"))
      Behaviors.receiveMessagePartial[Msg] {
        case AuditDone =>
          ctx.watch(ctx.spawn(doPayment(from, amount, payments, ctx.self), "payment"))
          Behaviors.receiveMessagePartial[Msg] {
            case PaymentDone =>
              ctx.watch(ctx.spawn(doAudit(audit, ctx.self, "payment finished"), "postAudit"))
              Behaviors.receiveMessagePartial[Msg] {
                case AuditDone =>
                  replyTo ! msg
                  Behaviors.stopped
              }
          } receiveSignal  ignoreStop
      } receiveSignal ignoreStop
    }

  private val ignoreStop: PartialFunction[(ActorContext[Msg], Signal), Behavior[Msg]] = {
    case (_, t: Terminated) if t.failure.isEmpty => Behaviors.same
  }

  private def doAudit(audit: ActorRef[LogActivity], who: ActorRef[AuditDone.type], msg: String) =
    Behaviors.setup[ActivityLogged] { ctx =>
      val id = Random.nextLong()
      audit ! LogActivity(who, msg, id, ctx.self)
      ctx.schedule(3.seconds, ctx.self, ActivityLogged(null, 0L))

      Behaviors.receive { (ctx, msg) =>
        if (msg.who == null) throw new TimeoutException
        else if (msg.id != id) throw new IllegalStateException
        else {
          who ! AuditDone
          Behaviors.stopped
        }
      }
    }

  private def doPayment(from: URI, amount: BigDecimal, payments: ActorRef[PaymentService], replyTo: ActorRef[PaymentDone.type]) =
    Behaviors.setup[PaymentResult] { ctx =>
      val uuid = UUID.randomUUID()
      payments ! Authorize(from, amount, uuid, ctx.self)
      ctx.schedule(3.seconds, ctx.self, IdUnkwown(null))

      Behaviors.receivePartial {
        case (_, PaymentSuccess(`uuid`)) =>
          payments ! Capture(uuid, amount, ctx.self)
          Behaviors.receivePartial {
            case (_, PaymentSuccess(`uuid`)) =>
              replyTo ! PaymentDone
              Behaviors.stopped
          }
        // otherwise die with MatchError
      }
    }

}
