/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session

import akka.typed.patterns.Receptionist._
import akka.typed.ActorRef

object FSMprotocol {
  sealed abstract class Nat extends Product with Serializable {
    def N: Int
  }
  sealed abstract class _0 extends Nat
  case object _0 extends _0 {
    val N = 0
  }
  case class S[P <: Nat](p: P) extends Nat {
    lazy val N = p.N + 1
  }

  val _1 = S(_0); type _1 = _1.type
  val _2 = S(_1); type _2 = _2.type
  val _3 = S(_2); type _3 = _3.type
  val _4 = S(_3); type _4 = _4.type
  val _5 = S(_4); type _5 = _5.type
  val _6 = S(_5); type _6 = _6.type
  val _7 = S(_6); type _7 = _7.type
}

trait FSMprotocol {

  type Start
  def start: Start

  trait Transition[S1, E <: Effect, S2] extends (S1 => S2)
  object Transition {
    def apply[S1, E <: Effect, S2](f: S1 => S2): Transition[S1, E, S2] =
      new Transition[S1, E, S2] {
        def apply(s: S1) = f(s)
      }
  }

  trait MakeSteps[S1, EE <: Effects, S2] extends (S1 => S2)
  implicit def makeNoSteps[S]: MakeSteps[S, HNil, S] = new MakeSteps[S, HNil, S] {
    def apply(s: S) = s
  }
  implicit def makeAStep[S1, E <: Effect, S2, T <: Effects, S3](
    implicit t: Transition[S1, E, S2], steps: MakeSteps[S2, T, S3]): MakeSteps[S1, E :: T, S3] =
    new MakeSteps[S1, E :: T, S3] {
      def apply(s: S1) = steps(t(s))
    }
}

object FSMsample extends FSMprotocol {
  import FSMprotocol._

  sealed trait FromClient
  sealed trait ToClient
  case class Login(client: ActorRef[ToClient]) extends FromClient
  case class Challenge() extends ToClient
  case class Response() extends FromClient
  case class Handle() extends ToClient
  case class Query() extends FromClient
  case class Result() extends ToClient

  /*
   * - register with receptionist
   * - enter infinite loop of
   *     - read Login
   *     - send challenge
   *     - read response
   *     - if wrong, send new challenge, up to three times
   *     - if correct, send access handle for query
   *     - query session allows 3 queries
   */

  type Start = Start.type
  def start = Start

  case object Start
  implicit val sendRegistration: Transition[Start.type, E.Send[Register[Login]], RegistrationSent.type] = Transition(s => RegistrationSent)

  case object RegistrationSent
  implicit val readRegistered: Transition[RegistrationSent.type, E.Read[Registered[Login]], GotRegistered.type] = Transition(r => GotRegistered)

  case object GotRegistered
  implicit val readLogin: Transition[GotRegistered.type, E.Read[Login], GotLogin.type] = Transition(r => GotLogin)

  case object GotLogin
  implicit val sendChallenge: Transition[GotLogin.type, E.Send[Challenge], ChallengeSent[_2]] = Transition(r => ChallengeSent(_2))

  case class ChallengeSent[N <: Nat](n: N)
  implicit def readResponse[N <: Nat]: Transition[ChallengeSent[N], E.Read[Response], GotResponse[N]] = Transition(c => GotResponse(c.n))

  case class GotResponse[N <: Nat](n: N)
  implicit val sendHandle: Transition[GotResponse[_], E.Send[Handle], HandleSent.type] = Transition(r => HandleSent)
  implicit def sendChallenge2[N <: Nat]: Transition[GotResponse[S[N]], E.Send[Challenge], ChallengeSent[N]] = Transition(r => ChallengeSent(r.n.p))
  implicit def sendNoChallenge: Transition[GotResponse[_0], E.Halt, Final.type] = Transition(r => Final)

  case object HandleSent
  implicit val readQuery: Transition[HandleSent.type, E.Read[Query], GotQuery[_2]] = Transition(h => GotQuery(_2))

  case class GotQuery[N <: Nat](n: N)
  implicit def sendResult[N <: Nat]: Transition[GotQuery[N], E.Send[Result], SentResult[N]] = Transition(q => SentResult(q.n))

  case class SentResult[N <: Nat](n: N)
  implicit def readQuery2[N <: Nat]: Transition[SentResult[S[N]], E.Read[Query], GotQuery[N]] = Transition(r => GotQuery(r.n.p))
  implicit val readNoQuery: Transition[SentResult[_0], E.Halt, Final.type] = Transition(r => Final)

  case object Final
}

object FSM {
  final case class Step[Protocol <: FSMprotocol, State, Self, Out] private[FSM] (
      p: Protocol, state: State, op: Operation[Self, Out, HNil]) {
    def andThen[Out2, E <: Effects, Next](f: (State, Out) => Operation[Self, Out2, E])(
      implicit steps: p.MakeSteps[State, E, Next]) =
      Step(p, steps(state), op.flatMap(x => f(state, x)).ignoreEffects)
    def capturing[Next, Out2](block: (Out, Step[Protocol, State, Self, Out]) => Step[Protocol, Next, Self, Out2]) =
      ???
  }

  def apply[P <: FSMprotocol](protocol: P)(implicit opDSL: ScalaDSL.OpDSL) = {
    val state = protocol.start
    new Step(protocol, state, ScalaDSL.opUnit(state))
  }
}

object Sample {
  import ScalaDSL._
  import FSMsample._

  object key extends ServiceKey[Login]

  OpDSL[Login] { implicit opDSL =>
    FSM(FSMsample)
      .andThen((_, _) => opProcessSelf)
      .andThen((_, self) => opCall(registerService(key, self).named("register")))
      .andThen((_, _) => opRead)
      .andThen((_, login) => opSend())
      .op
  }

}
