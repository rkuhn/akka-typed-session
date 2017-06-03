/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session
package auditdemo

import ScalaDSL._
import java.net.URI
import akka.typed.ActorRef
import akka.Done
import scala.util.Random
import scala.concurrent.duration._
import java.util.UUID
import shapeless.{ :+:, CNil }

object ProcessBased {

  def getMoney[R](from: URI, amount: BigDecimal,
                  payments: ActorRef[PaymentService],
                  replyTo: ActorRef[R], msg: R) =
    OpDSL[Nothing] { implicit opDSL =>
      for {
        self <- opActorSelf
        audit <- opCall(getService(AuditService).named("getAudit"))
        _ <- opCall(doAudit(audit, self, "starting payment").named("preAudit"))
        _ <- opCall(doPayment(from, amount, payments).named("payment"))
        _ <- opCall(doAudit(audit, self, "payment finished").named("postAudit"))
      } yield replyTo ! msg
    }

  private def doAudit(audit: ActorRef[LogActivity], who: ActorRef[Nothing], msg: String) =
    OpDSL[ActivityLogged] { implicit opDSL =>
      val id = Random.nextLong()
      for {
        self <- opProcessSelf
        _ <- opSend(audit, LogActivity(who, msg, id, self))
        ActivityLogged(`who`, `id`) <- opRead
      } yield Done
    }.withTimeout(3.seconds)

  private def doPayment(from: URI, amount: BigDecimal, payments: ActorRef[PaymentService]) =
    OpDSL[PaymentResult] { implicit opDSL =>
      val uuid = UUID.randomUUID()
      for {
        self <- opProcessSelf
        _ <- opSend(payments, Authorize(from, amount, uuid, self))
        PaymentSuccess(`uuid`) <- opRead
        _ <- opSend(payments, Capture(uuid, amount, self))
        PaymentSuccess(`uuid`) <- opRead
      } yield Done
    }.withTimeout(3.seconds)

  object GetMoneyProtocol extends E.Protocol {
    type Session = //
    E.Send[LogActivity] :: // preAudit
    E.Read[ActivityLogged] :: //
    E.Choice[(E.Halt :: HNil) :+: HNil :+: CNil] :: // possibly terminate
    E.Send[Authorize] :: // do payment
    E.Read[PaymentResult] :: //
    E.Choice[(E.Halt :: HNil) :+: HNil :+: CNil] :: // possibly terminate
    E.Send[Capture] :: //
    E.Read[PaymentResult] :: //
    E.Choice[(E.Halt :: HNil) :+: HNil :+: CNil] :: // possibly terminate
    E.Send[LogActivity] :: // postAudit
    E.Read[ActivityLogged] :: //
    E.Choice[(E.Halt :: HNil) :+: HNil :+: CNil] :: // possibly terminate
    HNil
  }

  // compile-time verification
  private def verify = E.vetExternalProtocol(GetMoneyProtocol, getMoney(???, ???, ???, ???, ???))

}
