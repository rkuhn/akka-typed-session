/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session
package auditdemo

import akka.actor.typed.ActorRef
import java.net.URI
import java.util.UUID

import akka.actor.typed.receptionist.ServiceKey

object AuditService {
 val Key = ServiceKey[LogActivity]("audit-service")
}
case class LogActivity(who: ActorRef[Nothing], what: String, id: Long, replyTo: ActorRef[ActivityLogged])
case class ActivityLogged(who: ActorRef[Nothing], id: Long)

sealed trait PaymentService
case class Authorize(payer: URI, amount: BigDecimal, id: UUID, replyTo: ActorRef[PaymentResult]) extends PaymentService
case class Capture(id: UUID, amount: BigDecimal, replyTo: ActorRef[PaymentResult]) extends PaymentService
case class Void(id: UUID, replyTo: ActorRef[PaymentResult]) extends PaymentService
case class Refund(id: UUID, replyTo: ActorRef[PaymentResult]) extends PaymentService

sealed trait PaymentResult
case class PaymentSuccess(id: UUID) extends PaymentResult
case class PaymentRejected(id: UUID, reason: String) extends PaymentResult
case class IdUnkwown(id: UUID) extends PaymentResult
