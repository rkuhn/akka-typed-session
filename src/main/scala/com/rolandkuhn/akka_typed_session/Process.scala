/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session

import scala.concurrent.duration.Duration
import akka.{ actor => a }
import akka.typed.Behavior
import akka.typed.scaladsl.Actor
import shapeless.{ :+:, CNil }

/**
 * A Process runs the given operation steps in a context that provides the
 * needed [[ActorRef]] of type `S` as the self-reference. Every process is
 * allotted a maximum lifetime after which the entire Actor fails; you may
 * set this to `Duration.Inf` for a server process. For non-fatal timeouts
 * take a look at [[ScalaProcess#forAndCancel]].
 *
 * The `name` of a Process is used as part of the process’ ActorRef name and
 * must therefore adhere to the path segment grammar of the URI specification.
 */
final case class Process[S, +Out, E <: Effects](
    name: String, timeout: Duration, mailboxCapacity: Int, operation: Operation[S, Out, E]) {
  if (name != "") a.ActorPath.validatePathElement(name)

  /**
   * Execute the given computation and process step after having completed
   * the current step. The current step’s computed value will be used as
   * input for the next computation.
   */
  def flatMap[T, EE <: Effects, EO <: Effects](f: Out ⇒ Operation[S, T, EE])(implicit p: E.ops.Prepend[E, EE, EO]): Process[S, T, EO] =
    copy(operation = Impl.FlatMap(operation, f))

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
  def map[T, Mapped, EOut <: Effects, EO <: Effects](f: Out ⇒ T)(
    implicit ev: MapAdapter[S, T, Mapped, EOut],
    p: E.ops.Prepend[E, EOut, EO]): Process[S, Mapped, EO] = flatMap(ev.lift(f))

  /**
   * Only continue this process if the given predicate is fulfilled, terminate
   * it otherwise.
   */
  def filter[EO <: Effects](p: Out ⇒ Boolean)(
    implicit pr: E.ops.Prepend[E, E.Choice[(E.Halt :: HNil) :+: HNil :+: CNil] :: HNil, EO]): Process[S, Out, EO] =
    flatMap(o ⇒
      ScalaDSL.opChoice(p(o), Impl.Return(o): Operation[S, Out, HNil]).orElse(Impl.ShortCircuit: Operation[S, Out, E.Halt :: HNil])
    )

  /**
   * Only continue this process if the given predicate is fulfilled, terminate
   * it otherwise.
   */
  def withFilter[EO <: Effects](p: Out ⇒ Boolean)(
    implicit pr: E.ops.Prepend[E, E.Choice[(E.Halt :: HNil) :+: HNil :+: CNil] :: HNil, EO]): Process[S, Out, EO] =
    flatMap(o ⇒
      ScalaDSL.opChoice(p(o), Impl.Return(o): Operation[S, Out, HNil]).orElse(Impl.ShortCircuit: Operation[S, Out, E.Halt :: HNil])
    )

  /**
   * Create a copy with modified name.
   */
  def named(name: String): Process[S, Out, E] = copy(name = name)

  /**
   * Create a copy with modified timeout parameter.
   */
  def withTimeout(timeout: Duration): Process[S, Out, E] = copy(timeout = timeout)

  /**
   * Create a copy with modified mailbox capacity.
   */
  def withMailboxCapacity(mailboxCapacity: Int): Process[S, Out, E] = copy(mailboxCapacity = mailboxCapacity)

  /**
   * Convert to a runnable [[Behavior]], e.g. for being used as the guardian of an [[ActorSystem]].
   */
  def toBehavior: Behavior[ActorCmd[S]] = Actor.deferred(ctx => new internal.ProcessInterpreter(this, ctx).execute(ctx))
}
