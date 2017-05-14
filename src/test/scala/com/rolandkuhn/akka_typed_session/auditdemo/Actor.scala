/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session.auditdemo

import java.net.URI
import akka.typed.{ ActorRef, Behavior, Signal, Terminated }
import akka.typed.scaladsl.{ Actor, ActorContext }
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
    Actor.deferred[Msg] { ctx =>
      ctx.watch(ctx.spawn(doAudit(audit, ctx.self, "starting payment"), "preAudit"))
      Actor.immutable[Msg] {
        case (ctx, AuditDone) =>
          ctx.watch(ctx.spawn(doPayment(from, amount, payments, ctx.self), "payment"))
          Actor.immutable[Msg] {
            case (ctx, PaymentDone) =>
              ctx.watch(ctx.spawn(doAudit(audit, ctx.self, "payment finished"), "postAudit"))
              Actor.immutable[Msg] {
                case (ctx, AuditDone) =>
                  replyTo ! msg
                  Actor.stopped
              }
          } onSignal ignoreStop
      } onSignal ignoreStop
    }

  private val ignoreStop: PartialFunction[(ActorContext[Msg], Signal), Behavior[Msg]] = {
    case (ctx, t: Terminated) if !t.wasFailed => Actor.same
  }

  private def doAudit(audit: ActorRef[LogActivity], who: ActorRef[AuditDone.type], msg: String) =
    Actor.deferred[ActivityLogged] { ctx =>
      val id = Random.nextLong()
      audit ! LogActivity(who, msg, id, ctx.self)
      ctx.schedule(3.seconds, ctx.self, ActivityLogged(null, 0L))

      Actor.immutable { (ctx, msg) =>
        if (msg.who == null) throw new TimeoutException
        else if (msg.id != id) throw new IllegalStateException
        else {
          who ! AuditDone
          Actor.stopped
        }
      }
    }

  private def doPayment(from: URI, amount: BigDecimal, payments: ActorRef[PaymentService], replyTo: ActorRef[PaymentDone.type]) =
    Actor.deferred[PaymentResult] { ctx =>
      val uuid = UUID.randomUUID()
      payments ! Authorize(from, amount, uuid, ctx.self)
      ctx.schedule(3.seconds, ctx.self, IdUnkwown(null))

      Actor.immutable {
        case (ctx, PaymentSuccess(`uuid`)) =>
          payments ! Capture(uuid, amount, ctx.self)
          Actor.immutable {
            case (ctx, PaymentSuccess(`uuid`)) =>
              replyTo ! PaymentDone
              Actor.stopped
          }
        // otherwise die with MatchError
      }
    }

}
