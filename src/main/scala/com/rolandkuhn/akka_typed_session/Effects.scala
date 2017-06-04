/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session

import akka.typed._
import shapeless.{ Coproduct, :+:, CNil }
import shapeless.test.illTyped
import scala.annotation.implicitNotFound

sealed trait Effect
sealed trait ExternalEffect extends Effect

object E {
  sealed abstract class Read[-T] extends ExternalEffect
  sealed abstract class Send[+T] extends ExternalEffect
  sealed abstract class Fork[+E <: Effects] extends Effect
  sealed abstract class Spawn[+E <: Effects] extends Effect
  sealed abstract class Choice[+C <: Coproduct] extends ExternalEffect
  sealed abstract class Halt extends Effect

  object ops {

    @implicitNotFound("Cannot prepend ${First} to ${Second} (e.g. due to infinite loop in the first argument)")
    sealed trait Prepend[First <: Effects, Second <: Effects, Out <: Effects]

    sealed trait PrependLowLow {
      implicit def prepend[H <: Effect, T <: Effects, S <: Effects, O <: Effects](
        implicit ev: Prepend[T, S, O]): Prepend[H :: T, S, H :: O] = null
    }
    sealed trait PrependLow extends PrependLowLow {
      implicit def prependNil[F <: HNil, S <: Effects]: Prepend[F, S, S] = null
    }
    object Prepend extends PrependLow {
      implicit def prependToNil[F <: Effects, S <: HNil]: Prepend[F, S, F] = null
    }

    sealed trait Filter[E <: Effects, U, Out <: Effects]

    sealed trait FilterLow {
      implicit def notFound[H <: Effect, T <: Effects, U, O <: Effects](implicit f: Filter[T, U, O], ev: NoSub[H, U]): Filter[H :: T, U, O] = null
      implicit def loop[E <: Effects, U, O <: Effects](implicit f: Filter[E, U, O]): Filter[Loop[E], U, Loop[O]] = null
    }
    object Filter extends FilterLow {
      implicit def nil[U]: Filter[HNil, U, HNil] = null
      implicit def found[H <: Effect, T <: Effects, U >: H, O <: Effects](implicit f: Filter[T, U, O]): Filter[H :: T, U, H :: O] = null
    }

    sealed trait NoSub[T, U]
    implicit def noSub1[T, U]: NoSub[T, U] = null
    implicit def noSub2[T <: U, U]: NoSub[T, U] = null
    implicit def noSub3[T <: U, U]: NoSub[T, U] = null
  }

  trait Protocol {
    type Session <: Effects
  }

  def vetExternalProtocol[E <: Effects, F <: Effects](p: Protocol, op: Operation[_, _, E])(
    implicit f: E.ops.Filter[E, ExternalEffect, F],
    ev: F <:< p.Session): Unit = ()

  trait OpFunAux[OpFun, E <: Effects]
  object OpFunAux {
    implicit def opFun1[E <: Effects]: OpFunAux[Function1[_, Operation[_, _, E]], E] = null
    implicit def opFun2[E <: Effects]: OpFunAux[Function2[_, _, Operation[_, _, E]], E] = null
    implicit def opFun3[E <: Effects]: OpFunAux[Function3[_, _, _, Operation[_, _, E]], E] = null
    implicit def opFun4[E <: Effects]: OpFunAux[Function4[_, _, _, _, Operation[_, _, E]], E] = null
    implicit def opFun5[E <: Effects]: OpFunAux[Function5[_, _, _, _, _, Operation[_, _, E]], E] = null
    implicit def opFun6[E <: Effects]: OpFunAux[Function6[_, _, _, _, _, _, Operation[_, _, E]], E] = null
    implicit def opFun7[E <: Effects]: OpFunAux[Function7[_, _, _, _, _, _, _, Operation[_, _, E]], E] = null
  }
}

sealed trait Effects
sealed abstract class HNil extends Effects
sealed abstract class ::[+H <: Effect, +T <: Effects] extends Effects
sealed abstract class Loop[+E <: Effects] extends Effects

//object EffectsTest {
//  import E._
//  type A = E.Read[Any]
//  type B = E.Send[Any]
//  type C = E.Fork[HNil]
//  type D = E.Spawn[HNil]
//
//  illTyped("implicitly[ops.NoSub[String, Any]]")
//  illTyped("implicitly[ops.NoSub[String, String]]")
//  implicitly[ops.NoSub[String, Int]]
//  implicitly[ops.NoSub[Any, String]]
//
//  implicitly[ops.PrependAux[HNil, HNil, HNil]]
//  implicitly[ops.PrependAux[HNil, A :: B :: HNil, A :: B :: HNil]]
//  implicitly[ops.PrependAux[A :: B :: HNil, HNil, A :: B :: HNil]]
//  implicitly[ops.PrependAux[A :: B :: HNil, C :: D :: HNil, A :: B :: C :: D :: HNil]]
//
//  implicitly[ops.FilterAux[A :: B :: C :: D :: HNil, ExternalEffect, A :: B :: HNil]]
//  implicitly[ops.FilterAux[Loop[HNil], ExternalEffect, Loop[HNil]]]
//  implicitly[ops.FilterAux[A :: Loop[HNil], ExternalEffect, A :: Loop[HNil]]]
//  implicitly[ops.FilterAux[A :: B :: C :: Loop[D :: A :: C :: B :: D :: HNil], ExternalEffect, A :: B :: Loop[A :: B :: HNil]]]
//
//  case class AuthRequest(credentials: String)(replyTo: ActorRef[AuthResult])
//
//  sealed trait AuthResult
//  case object AuthRejected extends AuthResult
//  case class AuthSuccess(token: ActorRef[Command]) extends AuthResult
//
//  sealed trait Command
//  case object DoIt extends Command
//
//  object MyProto extends Protocol {
//    import E._
//
//    type Session = //
//    Send[AuthRequest] :: // first ask for authentication
//    Read[AuthResult] :: // then read the response
//    Choice[(Halt :: HNil) :+: HNil :+: CNil] :: // then possibly terminate if rejected
//    Send[Command] :: HNil // then send a command
//  }
//
//  import ScalaDSL._
//
//  def opAsk[T, U](target: ActorRef[T], msg: ActorRef[U] => T) =
//    OpDSL[U] { implicit opDSL =>
//      for {
//        self <- opProcessSelf
//        _ <- opSend(target, msg(self))
//      } yield opRead
//    }
//
//  val auth: ActorRef[AuthRequest] = ???
//  val p = OpDSL[String] { implicit opDSL ⇒
//    for {
//      AuthSuccess(token) <- opCall(opAsk(auth, AuthRequest("secret")).named("getAuth"))
//    } yield opSend(token, DoIt)
//  }
//
//  vetExternalProtocol(MyProto, p)
//
//}
