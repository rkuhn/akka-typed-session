/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session

import akka.typed.{ ActorSystem, ActorRef, Behavior, Props }
import akka.{ actor => a }
import shapeless.{ Coproduct, :+:, CNil }
import shapeless.ops._
import scala.concurrent.duration._

/**
 * An Operation is a step executed by a [[Process]]. It exists in a context
 * characterized by the process’ ActorRef of type `S` and computes
 * a value of type `Out` when executed.
 *
 * Operations are created by using the `op*` methods of [[ScalaProcess]]
 * inside an [[OpDSL]] environment.
 */
sealed trait Operation[S, +Out, E <: Effects] {

  /**
   * Execute the given computation and process step after having completed
   * the current step. The current step’s computed value will be used as
   * input for the next computation.
   */
  def flatMap[T, EE <: Effects](f: Out ⇒ Operation[S, T, EE])(implicit p: E.ops.Prepend[E, EE]): Operation[S, T, p.Out] = Impl.FlatMap(this, f)

  /**
   * Map the value computed by this process step by the given function,
   * flattening the result if it is an [[Operation]] (by executing the
   * operation and using its result as the mapped value).
   *
   * The reason behind flattening when possible is to allow the formulation
   * of infinite process loops (as performed for example by server processes
   * that respond to any number of requests) using for-comprehensions.
   * Without this flattening a final pointless `map` step would be added
   * for each iteration, eventually leading to an OutOfMemoryError.
   */
  def map[T, Mapped, EOut <: Effects](f: Out ⇒ T)(
    implicit ev: MapAdapter[S, T, Mapped, EOut],
    p: E.ops.Prepend[E, EOut]): Operation[S, Mapped, p.Out] = flatMap(ev.lift(f))

  /**
   * Only continue this process if the given predicate is fulfilled, terminate
   * it otherwise.
   */
  def filter(p: Out ⇒ Boolean)(
    implicit pr: E.ops.Prepend[E, E.Choice[(E.Halt :: _0) :+: _0 :+: CNil] :: _0]): Operation[S, Out, pr.Out] =
    flatMap(o ⇒
      ScalaDSL.opChoice(p(o), Impl.Return(o): Operation[S, Out, _0]).orElse(Impl.ShortCircuit: Operation[S, Out, E.Halt :: _0])
    )

  /**
   * Only continue this process if the given predicate is fulfilled, terminate
   * it otherwise.
   */
  def withFilter(p: Out ⇒ Boolean)(
    implicit pr: E.ops.Prepend[E, E.Choice[(E.Halt :: _0) :+: _0 :+: CNil] :: _0]): Operation[S, Out, pr.Out] =
    flatMap(o ⇒
      ScalaDSL.opChoice(p(o), Impl.Return(o): Operation[S, Out, _0]).orElse(Impl.ShortCircuit: Operation[S, Out, E.Halt :: _0])
    )

  /**
   * Wrap as a [[Process]] with infinite timeout and a mailbox capacity of 1.
   * Small processes that are called or chained often interact in a fully
   * sequential fashion, where these defaults make sense.
   */
  def named(name: String): Process[S, Out, E] = Process(name, Duration.Inf, 1, this)

  /**
   * Wrap as a [[Process]] with the given mailbox capacity and infinite timeout.
   */
  def withMailboxCapacity(mailboxCapacity: Int): Process[S, Out, E] = named("").withMailboxCapacity(mailboxCapacity)

  /**
   * Wrap as a [[Process]] with the given timeout and a mailbox capacity of 1.
   */
  def withTimeout(timeout: Duration): Process[S, Out, E] = named("").withTimeout(timeout)

  /**
   * Wrap as a [[Process]] but without a name and convert to a [[Behavior]].
   */
  def toBehavior: Behavior[ActorCmd[S]] = named("").toBehavior

}

/*
   * These are the private values that make up the core algebra.
   */

object Impl {
  final case class FlatMap[S, Out1, Out2, E1 <: Effects, E2 <: Effects, E <: Effects](
      first: Operation[S, Out1, E1], andThen: Out1 ⇒ Operation[S, Out2, E2]) extends Operation[S, Out2, E] {
    override def toString: String = s"FlatMap($first)"
  }
  case object ShortCircuit extends Operation[Nothing, Nothing, E.Halt :: _0] {
    override def flatMap[T, E <: Effects](f: Nothing ⇒ Operation[Nothing, T, E])(
      implicit p: E.ops.Prepend[E.Halt :: _0, E]): Operation[Nothing, T, p.Out] = this.asInstanceOf[Operation[Nothing, T, p.Out]]
  }

  case object System extends Operation[Nothing, ActorSystem[Nothing], _0]
  case object Read extends Operation[Nothing, Nothing, E.Read[Any] :: _0]
  case object ProcessSelf extends Operation[Nothing, ActorRef[Any], _0]
  case object ActorSelf extends Operation[Nothing, ActorRef[ActorCmd[Nothing]], _0]
  final case class Choice[S, T, E <: Coproduct](ops: Operation[S, T, _0]) extends Operation[S, T, E.Choice[E] :: _0]
  final case class Return[T](value: T) extends Operation[Nothing, T, _0]
  final case class Call[S, T, E <: Effects](process: Process[S, T, E], replacement: Option[T]) extends Operation[Nothing, T, E]
  final case class Fork[S, E <: Effects](process: Process[S, Any, E]) extends Operation[Nothing, SubActor[S], E.Fork[E] :: _0]
  final case class Spawn[S, E <: Effects](process: Process[S, Any, E], deployment: Props) extends Operation[Nothing, ActorRef[ActorCmd[S]], E.Spawn[E] :: _0]
  final case class Schedule[T](delay: FiniteDuration, msg: T, target: ActorRef[T]) extends Operation[Nothing, a.Cancellable, E.Send[T] :: _0]
  sealed trait AbstractWatchRef { type Msg }
  final case class WatchRef[T](watchee: ActorRef[Nothing], target: ActorRef[T], msg: T, onFailure: Throwable ⇒ Option[T])
      extends Operation[Nothing, a.Cancellable, _0] with AbstractWatchRef {
    type Msg = T
    override def equals(other: Any) = super.equals(other)
    override def hashCode() = super.hashCode()
  }
  //final case class Replay[T](key: StateKey[T]) extends Operation[Nothing, T]
  //final case class Snapshot[T](key: StateKey[T]) extends Operation[Nothing, T]
  final case class State[S, T <: StateKey[S], Ev, Ex](key: T { type Event = Ev }, afterUpdates: Boolean, transform: S ⇒ (Seq[Ev], Ex)) extends Operation[Nothing, Ex, _0]
  final case class StateR[S, T <: StateKey[S], Ev](key: T { type Event = Ev }, afterUpdates: Boolean, transform: S ⇒ Seq[Ev]) extends Operation[Nothing, S, _0]
  final case class Forget[T](key: StateKey[T]) extends Operation[Nothing, akka.Done, _0]
  final case class Cleanup(cleanup: () ⇒ Unit) extends Operation[Nothing, akka.Done, _0]
}
