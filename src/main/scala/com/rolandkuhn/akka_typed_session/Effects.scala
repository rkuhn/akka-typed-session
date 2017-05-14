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
    import language.higherKinds

    @implicitNotFound("Cannot prepend ${First} to ${Second} (e.g. due to infinite loop in the first argument)")
    sealed trait Prepend[First <: Effects, Second <: Effects] {
      type Out <: Effects
    }
    type PrependAux[F <: Effects, S <: Effects, O <: Effects] = Prepend[F, S] { type Out = O }

    sealed trait PrependLowLow {
      implicit def prepend[H <: Effect, T <: Effects, S <: Effects](
        implicit ev: Prepend[T, S]): PrependAux[H :: T, S, H :: ev.Out] = null
    }
    sealed trait PrependLow extends PrependLowLow {
      implicit def prependNil[F <: _0, S <: Effects]: PrependAux[F, S, S] = null
    }
    object Prepend extends PrependLow {
      implicit def prependToNil[F <: Effects, S <: _0]: PrependAux[F, S, F] = null
    }

    sealed trait Filter[E <: Effects, U] {
      type Out <: Effects
    }
    type FilterAux[E <: Effects, U, O <: Effects] = Filter[E, U] { type Out = O }

    sealed trait FilterLow {
      implicit def notFound[H <: Effect, T <: Effects, U](implicit f: Filter[T, U], ev: NoSub[H, U]): FilterAux[H :: T, U, f.Out] = null
      implicit def loop[E <: Effects, U](implicit f: Filter[E, U]): FilterAux[Loop[E], U, Loop[f.Out]] = null
    }
    object Filter extends FilterLow {
      implicit def nil[U]: FilterAux[_0, U, _0] = null
      implicit def found[H <: Effect, T <: Effects, U >: H](implicit f: Filter[T, U]): FilterAux[H :: T, U, H :: f.Out] = null
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
    implicit f: E.ops.FilterAux[E, ExternalEffect, F],
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
sealed abstract class _0 extends Effects
sealed abstract class ::[+H <: Effect, +T <: Effects] extends Effects
sealed abstract class Loop[+E <: Effects] extends Effects

object EffectsTest {
  import E._
  type A = E.Read[Any]
  type B = E.Send[Any]
  type C = E.Fork[_0]
  type D = E.Spawn[_0]

  illTyped("implicitly[ops.NoSub[String, Any]]")
  illTyped("implicitly[ops.NoSub[String, String]]")
  implicitly[ops.NoSub[String, Int]]
  implicitly[ops.NoSub[Any, String]]

  implicitly[ops.PrependAux[_0, _0, _0]]
  implicitly[ops.PrependAux[_0, A :: B :: _0, A :: B :: _0]]
  implicitly[ops.PrependAux[A :: B :: _0, _0, A :: B :: _0]]
  implicitly[ops.PrependAux[A :: B :: _0, C :: D :: _0, A :: B :: C :: D :: _0]]

  implicitly[ops.FilterAux[A :: B :: C :: D :: _0, ExternalEffect, A :: B :: _0]]
  implicitly[ops.FilterAux[Loop[_0], ExternalEffect, Loop[_0]]]
  implicitly[ops.FilterAux[A :: Loop[_0], ExternalEffect, A :: Loop[_0]]]
  implicitly[ops.FilterAux[A :: B :: C :: Loop[D :: A :: C :: B :: D :: _0], ExternalEffect, A :: B :: Loop[A :: B :: _0]]]

  case class AuthRequest(credentials: String)(replyTo: ActorRef[AuthResult])

  sealed trait AuthResult
  case object AuthRejected extends AuthResult
  case class AuthSuccess(token: ActorRef[Command]) extends AuthResult

  sealed trait Command
  case object DoIt extends Command

  object MyProto extends Protocol {
    import E._

    type Session = //
    Send[AuthRequest] :: // first ask for authentication
    Read[AuthResult] :: // then read the response
    Choice[(Halt :: _0) :+: _0 :+: CNil] :: // then possibly terminate if rejected
    Send[Command] :: _0 // then send a command
  }

  import ScalaDSL._

  def opAsk[T, U](target: ActorRef[T], msg: ActorRef[U] => T) =
    OpDSL[U] { implicit opDSL =>
      for {
        self <- opProcessSelf
        _ <- opSend(target, msg(self))
      } yield opRead
    }

  val auth: ActorRef[AuthRequest] = ???
  val p = OpDSL[String] { implicit opDSL ⇒
    for {
      AuthSuccess(token) <- opCall(opAsk(auth, AuthRequest("secret")).named("getAuth"))
    } yield opSend(token, DoIt)
  }

  vetExternalProtocol(MyProto, p)

}
