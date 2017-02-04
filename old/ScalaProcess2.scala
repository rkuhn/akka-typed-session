/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package com.rolandkuhn.akka_typed_session

import akka.typed._
import scala.concurrent.duration._
import akka.{ actor ⇒ a }
import scala.util.control.NoStackTrace
import scala.annotation.implicitNotFound

/**
 * A DSL for writing reusable behavior pieces that are executed concurrently
 * within Actors.
 *
 * Terminology:
 *
 *  - a Process has a 1:1 relationship with an ActorRef
 *  - an Operation is a step that a Process takes and that produces a value
 *  - Processes are concurrent, but not distributed: all failures stop the entire Actor
 *  - each Process has its own identity (due to ActorRef), and the Actor has its own
 *    identity (an ActorRef[ActorCmd[_]]); processSelf is the Process’ identity, actorSelf is the Actor’s
 *  - process timeout means failure
 *  - every Actor has a KV store for state
 *
 *      - querying by key (using a single element per slot)
 *      - updating is an Operation that produces events that are applied to the state
 *      - persistence can be plugged in transparently (NOT YET IMPLEMENTED)
 *      - recovery means acquiring state initially (which might trigger internal replay)
 */
object ScalaProcess2 {

  /**
   * Exception type that is thrown by the `retry` facility when after the
   * given number of retries still no value has been obtained.
   */
  final class RetriesExceeded(message: String) extends RuntimeException(message) with NoStackTrace

  import language.implicitConversions
  /**
   * This implicit expresses that operations that do not use their input channel can be used in any context.
   */
  private implicit def nothingIsSomething[T, U, E <: E1, E1 <: Effects](op: Operation[Nothing, T, E]): Operation[U, T, E1] =
    op.asInstanceOf[Operation[U, T, E1]]

  /**
   * This is a compile-time marker for the type of self-reference expected by
   * the process that is being described. No methods can be called on a value
   * of this type. It is used as follows:
   *
   * {{{
   * OpDSL[MyType] { implicit opDSL =>
   *   ... // use Operation operators here
   * }
   * }}}
   */
  sealed trait OpDSL extends Any {
    type Self
  }

  /**
   * This object offers different constructors that provide a scope within
   * which [[Operation]] values can be created using the `op*` methods. The
   * common characteristic of these constructors is that they lift their
   * contents completely into the resulting process description, in other
   * words the code within is only evaluated once the [[Operation]] has been
   * called, forked, or spawned within an Actor.
   *
   * It is strongly recommended to always use the same name for the required
   * implicit function argument (`opDSL` in the examples below) in order to
   * achieve proper scoping for nested declarations.
   *
   * Usage for single-shot processes:
   * {{{
   * OpDSL[MyType] { implicit opDSL =>
   *   for {
   *     x &lt;- step1
   *     y &lt;- step2
   *     ...
   *   } ...
   * }
   * }}}
   *
   * Usage for bounded repetition (will run the whole process three times
   * in this example and yield a list of the three results):
   * {{{
   * OpDSL.loop[MyType](3) { implicit opDSL =>
   *   for {
   *     x &lt;- step1
   *     y &lt;- step2
   *     ...
   *   } ...
   * }
   * }}}
   *
   * Usage for infinite repetition, for example when writing a server process:
   * {{{
   * OpDSL.loopInf[MyType] { implicit opDSL =>
   *   for {
   *     x &lt;- step1
   *     y &lt;- step2
   *     ...
   *   } ...
   * }
   * }}}
   */
  object OpDSL {
    private val _unit: Operation[Nothing, Null, _0] = opUnit(null)(null: OpDSL { type Self = Nothing })
    private def unit[S, Out]: Operation[S, Out, _0] = _unit.asInstanceOf[Operation[S, Out, _0]]

    def loopInf[S]: NextLoopInf[S] = nextLoopInf.asInstanceOf[NextLoopInf[S]]
    trait NextLoopInf[S] {
      def apply[U, E <: Effects](body: OpDSL { type Self = S } ⇒ Operation[S, U, E]): Operation[S, Nothing, Loop[E]] = {
        lazy val l: Operation[S, Nothing, E] = unit[S, OpDSL { type Self = S }].flatMap(body).withEffects[_0].flatMap(_ ⇒ l)
        l.withEffects[Loop[E]]
      }
    }
    private object nextLoopInf extends NextLoopInf[Nothing]

    def apply[T]: Next[T] = next.asInstanceOf[Next[T]]
    trait Next[T] {
      def apply[U, E <: Effects](body: OpDSL { type Self = T } ⇒ Operation[T, U, E]): Operation[T, U, E] =
        unit[T, OpDSL { type Self = T }].flatMap(body)
    }
    private object next extends Next[Nothing]

    trait NextStep[T] {
      def apply[U, E <: Effects](mailboxCapacity: Int, body: OpDSL { type Self = T } ⇒ Operation[T, U, E])(
        implicit opDSL: OpDSL): Operation[opDSL.Self, U, E] =
        Call(Process("nextStep", Duration.Inf, mailboxCapacity, body(null)), None)
    }
    object nextStep extends NextStep[Nothing]
  }

  /**
   * Helper to make `Operation.map` or `Operation.foreach` behave like `flatMap` when needed.
   */
  sealed trait MapAdapter[Self, Out, Mapped, EOut <: Effects] {
    def lift[O](f: O ⇒ Out): O ⇒ Operation[Self, Mapped, EOut]
  }
  /**
   * Helper to make `Operation.map` or `Operation.foreach` behave like `flatMap` when needed.
   */
  object MapAdapter extends MapAdapterLow {
    private val _adapter =
      new MapAdapter[Any, Operation[Any, Any, _0], Any, _0] {
        override def lift[O](f: O ⇒ Operation[Any, Any, _0]): O ⇒ Operation[Any, Any, _0] = f
      }

    implicit def mapAdapterOperation[Self, M, E <: Effects]: MapAdapter[Self, Operation[Self, M, E], M, E] =
      _adapter.asInstanceOf[MapAdapter[Self, Operation[Self, M, E], M, E]]
  }
  /**
   * Helper to make `Operation.map` or `Operation.foreach` behave like `flatMap` when needed.
   */
  trait MapAdapterLow {
    private val _adapter =
      new MapAdapter[Any, Any, Any, _0] {
        override def lift[O](f: O ⇒ Any): O ⇒ Operation[Any, Any, _0] = o ⇒ Return(f(o))
      }

    implicit def mapAdapterAny[Self, Out]: MapAdapter[Self, Out, Out, _0] =
      _adapter.asInstanceOf[MapAdapter[Self, Out, Out, _0]]
  }

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
    def flatMap[T, EE <: Effects](f: Out ⇒ Operation[S, T, EE])(implicit p: E.ops.Prepend[E, EE]): Process[S, T, p.Out] =
      copy(operation = FlatMap(operation, f))

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
      p: E.ops.Prepend[E, EOut]): Process[S, Mapped, p.Out] = flatMap(ev.lift(f))

    /**
     * Only continue this process if the given predicate is fulfilled, terminate
     * it otherwise.
     */
    def filter(p: Out ⇒ Boolean): Process[S, Out, E] = flatMap(o ⇒ if (p(o)) Return(o) else ShortCircuit)

    /**
     * Only continue this process if the given predicate is fulfilled, terminate
     * it otherwise.
     */
    def withFilter(p: Out ⇒ Boolean): Process[S, Out, E] = flatMap(o ⇒ if (p(o)) Return(o) else ShortCircuit)

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
    def toBehavior: Behavior[ActorCmd[S]] = ???
  }

  sealed trait Effect
  sealed trait SessionEffect extends Effect

  object E {
    sealed abstract class Read[-T] extends SessionEffect
    sealed abstract class Send[+T] extends SessionEffect
    sealed abstract class Fork[+E <: Effects] extends Effect
    sealed abstract class Spawn[+E <: Effects] extends Effect
    sealed abstract class Choice[+C <: :+:[_, _ <: :+:[_, _]]] extends SessionEffect
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
  }

  sealed trait Effects
  sealed abstract class ::[+H <: Effect, +T <: Effects] extends Effects
  sealed abstract class Loop[+E <: Effects] extends Effects

  sealed trait Choices
  sealed abstract class :+:[+H <: Effects, +T <: Choices] extends Choices

  sealed trait Processes
  final case class :|:[+H <: Process[_, _, _], +T <: Processes](h: H, t: T) extends Processes

  sealed abstract class _0 extends Effects with Choices with Processes
  case object _0 extends _0

  object Processes {
    import language.higherKinds

    sealed trait lub[P <: Processes] {
      type Out
    }
    type lubAux[P <: Processes, O] = lub[P] { type Out = O }

    object lub {
      sealed trait LUB[T1, T2, Out]
      implicit def LUB[L, T1 <: L, T2 <: L]: LUB[T1, T2, L] = null

      implicit def nil: lubAux[_0, Nothing] = null
      implicit def cons[S, O, E <: Effects, T <: Processes, LT, L](
        implicit l: lubAux[T, LT], L: LUB[O, LT, L]): lubAux[Process[S, O, E] :|: T, L] = null
    }

    trait ForkMapper[Val[_], Eff[_ <: Effects] <: Effect] {
      def apply[S, O, E <: Effects](p: Process[S, O, E]) = ???
    }

    sealed trait fork[P <: Processes, TC[_ <: Effects] <: Effect] {
      type Out <: Effects
    }
    type forkAux[P <: Processes, TC[_ <: Effects] <: Effect, O <: Effects] = fork[P, TC] { type Out = O }

    object fork {
      implicit def nil[T[_ <: Effects] <: Effect]: forkAux[_0, T, _0] = null
      implicit def cons[S, O, E <: Effects, TC[_ <: Effects] <: Effect, T <: Processes, TE <: Effects](
        implicit f: forkAux[T, TC, TE]): forkAux[Process[S, O, E] :|: T, TC, TC[E] :: TE] = null
    }
  }

  implicitly[Processes.lub.LUB[Int, Nothing, Int]]
  implicitly[Processes.lubAux[_0, Nothing]]
  implicitly[Processes.lubAux[Process[Any, Int, _0] :|: _0, Int]]
  implicitly[Processes.lubAux[Process[Any, Long, _0] :|: Process[Any, Int, _0] :|: _0, AnyVal]]

  implicitly[Processes.forkAux[_0, E.Fork, _0]]
  implicitly[Processes.forkAux[Process[String, String, _0] :|: _0, E.Fork, E.Fork[_0] :: _0]]
  implicitly[Processes.forkAux[Process[Any, Any, E.Read[Int] :: _0] :|: Process[String, String, _0] :|: _0, E.Fork, E.Fork[E.Read[Int] :: _0] :: E.Fork[_0] :: _0]]

  private implicit class WithEffects[S, O](op: Operation[S, O, _]) {
    def withEffects[E <: Effects]: Operation[S, O, E] = op.asInstanceOf[Operation[S, O, E]]
  }
  implicit class WithoutEffects[S, O](op: Operation[S, O, _]) {
    def ignoreEffects: Operation[S, O, _0] = op.asInstanceOf[Operation[S, O, _0]]
  }

  def opChoice[S, O, L <: Effects, R <: Effects](
    p: Boolean, l: ⇒ Operation[S, O, L], r: ⇒ Operation[S, O, R]): Operation[S, O, E.Choice[L :+: R :+: _0] :: _0] =
    (if (p) l else r).withEffects[E.Choice[L :+: R :+: _0] :: _0]

  object EffectsTest {
    import E.ops
    type A = E.Read[Any]
    type B = E.Send[Any]
    type C = E.Fork[_0]
    type D = E.Spawn[_0]

    //implicitly[Effects.NoSub[String, Any]]
    //implicitly[Effects.NoSub[String, String]]
    implicitly[ops.NoSub[String, Int]]
    implicitly[ops.NoSub[Any, String]]

    implicitly[ops.PrependAux[_0, _0, _0]]
    implicitly[ops.PrependAux[_0, A :: B :: _0, A :: B :: _0]]
    implicitly[ops.PrependAux[A :: B :: _0, _0, A :: B :: _0]]
    implicitly[ops.PrependAux[A :: B :: _0, C :: D :: _0, A :: B :: C :: D :: _0]]

    implicitly[ops.FilterAux[A :: B :: C :: D :: _0, SessionEffect, A :: B :: _0]]
    implicitly[ops.FilterAux[Loop[_0], SessionEffect, Loop[_0]]]
    implicitly[ops.FilterAux[A :: Loop[_0], SessionEffect, A :: Loop[_0]]]
    implicitly[ops.FilterAux[A :: B :: C :: Loop[D :: A :: C :: B :: D :: _0], SessionEffect, A :: B :: Loop[A :: B :: _0]]]

    trait Protocol {
      type Session <: Effects
    }
    object Protocol {
      @implicitNotFound("The effects of ${E2} do not match the expected session type ${E1}")
      sealed trait Eq[E1 <: Effects, E2 <: Effects]
      implicit def eq[E1 <: Effects, E2 <: Effects](implicit ev: E1 =:= E2): Eq[E1, E2] = null
    }

    object MyProto extends Protocol {
      type Session = E.Read[String] :: E.Send[String] :: Loop[E.Read[String] :: _0]
    }

    def vetProtocol[E <: Effects, F <: Effects](p: Protocol, op: Operation[_, _, E])(
      implicit f: E.ops.FilterAux[E, SessionEffect, F], ev: Protocol.Eq[p.Session, F]): Unit = ()

    val p = OpDSL[String] { implicit opDSL ⇒
      opProcessSelf
        .flatMap(_ ⇒ opRead)
        .flatMap(_ ⇒
          opSchedule(Duration.Zero, "", null)
            .flatMap(_ ⇒ OpDSL.loopInf(_ ⇒ opRead))
        )
    }

    vetProtocol(MyProto, p)

  }

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
    def flatMap[T, EE <: Effects](f: Out ⇒ Operation[S, T, EE])(implicit p: E.ops.Prepend[E, EE]): Operation[S, T, p.Out] = FlatMap(this, f)

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
    def filter(p: Out ⇒ Boolean): Operation[S, Out, E] = flatMap(o ⇒ if (p(o)) Return(o) else ShortCircuit)

    /**
     * Only continue this process if the given predicate is fulfilled, terminate
     * it otherwise.
     */
    def withFilter(p: Out ⇒ Boolean): Operation[S, Out, E] = flatMap(o ⇒ if (p(o)) Return(o) else ShortCircuit)

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

  final case class FlatMap[S, Out1, Out2, E1 <: Effects, E2 <: Effects, E <: Effects](
      first: Operation[S, Out1, E1], then: Out1 ⇒ Operation[S, Out2, E2]) extends Operation[S, Out2, E] {
    override def toString: String = s"FlatMap($first)"
  }
  case object ShortCircuit extends Operation[Nothing, Nothing, _0] {
    override def flatMap[T, E <: Effects](f: Nothing ⇒ Operation[Nothing, T, E])(
      implicit p: E.ops.Prepend[_0, E]): Operation[Nothing, T, p.Out] = this.asInstanceOf[Operation[Nothing, T, p.Out]]
  }

  case object System extends Operation[Nothing, ActorSystem[Nothing], _0]
  case object Read extends Operation[Nothing, Nothing, E.Read[Any] :: _0]
  case object ProcessSelf extends Operation[Nothing, ActorRef[Any], _0]
  case object ActorSelf extends Operation[Nothing, ActorRef[ActorCmd[Nothing]], _0]
  final case class Return[T](value: T) extends Operation[Nothing, T, _0]
  final case class Call[S, T, E <: Effects](process: Process[S, T, E], replacement: Option[T]) extends Operation[Nothing, T, E]
  final case class Fork[S, E <: Effects](process: Process[S, Any, E]) extends Operation[Nothing, SubActor[S], E.Fork[E] :: _0]
  final case class Spawn[S, E <: Effects](process: Process[S, Any, E], deployment: DeploymentConfig) extends Operation[Nothing, ActorRef[ActorCmd[S]], E.Spawn[E] :: _0]
  final case class Schedule[T](delay: FiniteDuration, msg: T, target: ActorRef[T]) extends Operation[Nothing, a.Cancellable, E.Send[T] :: _0]
  sealed trait AbstractWatchRef { type Msg }
  final case class WatchRef[T](watchee: ActorRef[Nothing], msg: T, target: ActorRef[T], onFailure: Throwable ⇒ Option[T])
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

  /*
   * The core operations: keep these minimal!
   */

  /**
   * Obtain a reference to the ActorSystem in which this process is running.
   */
  def opSystem(implicit opDSL: OpDSL): Operation[opDSL.Self, ActorSystem[Nothing], _0] = System

  /**
   * Read a message from this process’ input channel.
   */
  def opRead(implicit opDSL: OpDSL): Operation[opDSL.Self, opDSL.Self, E.Read[opDSL.Self] :: _0] = Read

  /**
   * Obtain this process’ [[ActorRef]], not to be confused with the ActorRef of the Actor this process is running in.
   */
  def opProcessSelf(implicit opDSL: OpDSL): Operation[opDSL.Self, ActorRef[opDSL.Self], _0] = ProcessSelf

  /**
   * Obtain the [[ActorRef]] of the Actor this process is running in.
   */
  def opActorSelf(implicit opDSL: OpDSL): Operation[opDSL.Self, ActorRef[ActorCmd[Nothing]], _0] = ActorSelf

  /**
   * Lift a plain value into a process that returns that value.
   */
  def opUnit[U](value: U)(implicit opDSL: OpDSL): Operation[opDSL.Self, U, _0] = Return(value)

  /**
   * Execute the given process within the current Actor, await and return that process’ result.
   * If the process does not return a result (due to a non-matching `filter` expression), the
   * replacement value is used if the provided Option contains a value.
   */
  def opCall[Self, Out, E <: Effects](process: Process[Self, Out, E], replacement: Option[Out] = None)(
    implicit opDSL: OpDSL): Operation[opDSL.Self, Out, E] =
    Call(process, replacement)

  /**
   * Create and execute a process with a self reference of the given type,
   * await and return that process’ result. This is equivalent to creating
   * a process with [[OpDSL]] and using `call` to execute it. A replacement
   * value is not provided; if recovery from a halted subprocess is desired
   * please use `opCall` directly.
   */
  def opNextStep[T]: OpDSL.NextStep[T] =
    OpDSL.nextStep.asInstanceOf[OpDSL.NextStep[T]]

  /**
   * Execute the given process within the current Actor, concurrently with the
   * current process. The value computed by the forked process cannot be
   * observed, instead you would have the forked process send a message to the
   * current process to communicate results. The returned [[SubActor]] reference
   * can be used to send messages to the forked process or to cancel it.
   */
  def opFork[Self, E <: Effects](process: Process[Self, Any, E])(implicit opDSL: OpDSL): Operation[opDSL.Self, SubActor[Self], E.Fork[E] :: _0] =
    Fork(process)

  /**
   * Execute the given process in a newly spawned child Actor of the current
   * Actor. The new Actor is fully encapsulated behind the [[ActorRef]] that
   * is returned.
   *
   * The mailboxCapacity for the Actor is configured using the optional
   * [[DeploymentConfig]] while the initial process’ process mailbox is
   * limited based on the [[Process]] configuration as usual. When sizing
   * the Actor mailbox capacity you need to consider that communication
   * between the processes hosted by that Actor and timeouts also go through
   * this mailbox.
   */
  def opSpawn[Self, E <: Effects](process: Process[Self, Any, E], deployment: DeploymentConfig = EmptyDeploymentConfig)(
    implicit opDSL: OpDSL): Operation[opDSL.Self, ActorRef[ActorCmd[Self]], E.Spawn[E] :: _0] =
    Spawn(process, deployment)

  /**
   * Schedule a message to be sent after the given delay has elapsed.
   */
  def opSchedule[T](delay: FiniteDuration, msg: T, target: ActorRef[T])(implicit opDSL: OpDSL): Operation[opDSL.Self, a.Cancellable, E.Send[T] :: _0] =
    Schedule(delay, msg, target)

  /**
   * Watch the given [[ActorRef]] and send the specified message to the given
   * target when the watched actor has terminated. The returned Cancellable
   * can be used to unwatch the watchee, which will inhibit the message from
   * being dispatched—it might still be delivered if it was previously dispatched.
   *
   * If `onFailure` is provided it can override the value to be sent if the
   * watched Actor failed and was a child Actor of the Actor hosting this process.
   */
  def opWatch[T](watchee: ActorRef[Nothing], msg: T, target: ActorRef[T], onFailure: Throwable ⇒ Option[T] = any2none)(
    implicit opDSL: OpDSL): Operation[opDSL.Self, a.Cancellable, _0] =
    WatchRef(watchee, msg, target, onFailure)

  val any2none = (_: Any) ⇒ None
  private val _any2Nil = (state: Any) ⇒ Nil → state
  private def any2Nil[T] = _any2Nil.asInstanceOf[T ⇒ (Nil.type, T)]

  /**
   * Read the state stored for the given [[StateKey]], suspending this process
   * until after all outstanding updates for the key have been completed if
   * `afterUpdates` is `true`.
   */
  def opReadState[T](key: StateKey[T], afterUpdates: Boolean = true)(implicit opDSL: OpDSL): Operation[opDSL.Self, T, _0] =
    State[T, StateKey[T], key.Event, T](key, afterUpdates, any2Nil)

  /**
   * Update the state stored for the given [[StateKey]] by emitting events that
   * are applied to the state in order, suspending this process
   * until after all outstanding updates for the key have been completed if
   * `afterUpdates` is `true`. The return value is determined by the transform
   * function based on the current state; if you want to return the state that
   * results from having applied the emitted events then please see
   * [[ScalaProcess#opUpdateAndReadState]].
   */
  def opUpdateState[T, Ev, Ex](key: StateKey[T] { type Event = Ev }, afterUpdates: Boolean = true)(
    transform: T ⇒ (Seq[Ev], Ex))(implicit opDSL: OpDSL): Operation[opDSL.Self, Ex, _0] =
    State(key, afterUpdates, transform)

  /**
   * Update the state by emitting a sequence of events, returning the updated state. The
   * process is suspended until after all outstanding updates for the key have been
   * completed if `afterUpdates` is `true`.
   */
  def opUpdateAndReadState[T, Ev](key: StateKey[T] { type Event = Ev }, afterUpdates: Boolean = true)(
    transform: T ⇒ Seq[Ev])(implicit opDSL: OpDSL): Operation[opDSL.Self, T, _0] =
    StateR(key, afterUpdates, transform)

  /**
   * FIXME not yet implemented
   *
   * Instruct the Actor to persist the state for the given [[StateKey]] after
   * all currently outstanding updates for this key have been completed,
   * suspending this process until done.
   */
  //def opTakeSnapshot[T](key: PersistentStateKey[T])(implicit opDSL: OpDSL): Operation[opDSL.Self, T] =
  //  Snapshot(key)

  /**
   * FIXME not yet implemented
   *
   * Restore the state for the given [[StateKey]] from persistent event storage.
   * If a snapshot is found it will be used as the starting point for the replay,
   * otherwise events are replayed from the beginning of the event log, starting
   * with the given initial data as the state before the first event is applied.
   */
  //def opReplayPersistentState[T](key: PersistentStateKey[T])(implicit opDSL: OpDSL): Operation[opDSL.Self, T] =
  //  Replay(key)

  /**
   * Remove the given [[StateKey]] from this Actor’s storage. The slot can be
   * filled again using `updateState` or `replayPersistentState`.
   */
  def opForgetState[T](key: StateKey[T])(implicit opDSL: OpDSL): Operation[opDSL.Self, akka.Done, _0] =
    Forget(key)

  /**
   * Run the given cleanup handler after the operations that will be chained
   * off of this one, i.e. this operation must be further transformed to make
   * sense.
   *
   * Usage with explicit combinators:
   * {{{
   * opCleanup(() => doCleanup())
   *   .flatMap { _ =>
   *     ...
   *   } // doCleanup() will run here
   *   .flatMap { ... }
   * }}}
   *
   * Usage with for-expressions:
   * {{{
   * (for {
   *     resource &lt;- obtainResource
   *     _ &lt;- opCleanup(() => doCleanup(resource))
   *     ...
   *   } yield ...
   * ) // doCleanup() will run here
   * .flatMap { ... }
   * }}}
   *
   * Unorthodox usage:
   * {{{
   * (for {
   *     resource &lt;- obtainResource
   *     ...
   *   } yield opCleanup(() => doCleanup(resource))
   * ) // doCleanup() will run here
   * .flatMap { ... }
   * }}}
   */
  def opCleanup(cleanup: () ⇒ Unit)(implicit opDSL: OpDSL): Operation[opDSL.Self, akka.Done, _0] =
    Cleanup(cleanup)

  /**
   * Terminate processing here, ignoring further transformations. If this process
   * has been called by another process then the `replacement` argument to `opCall`
   * determines whether the calling process continues or halts as well: if no
   * replacement is given, processing cannot go on.
   */
  def opHalt(implicit opDSL: OpDSL): Operation[opDSL.Self, Nothing, _0] = ShortCircuit

  // FIXME opChildList
  // FIXME opProcessList
  // FIXME opTerminate
  // FIXME opStopChild
  // FIXME opAsk(Main)
  // FIXME opParallel
  // FIXME opUpdate(Read)SimpleState

  /*
   * State Management
   */

  /**
   * A key into the Actor’s state map that allows access both for read and
   * update operations. Updates are modeled by emitting events of the specified
   * type. The updates are applied to the state in the order in which they are
   * emitted. For persistent state data please refer to [[PersistentStateKey]]
   * and for ephemeral non-event-sourced data take a look at [[SimpleStateKey]].
   */
  trait StateKey[T] {
    type Event
    def apply(state: T, event: Event): T
    def initial: T
  }

  /**
   * Event type emitted in conjunction with [[SimpleStateKey]], the only
   * implementation is [[SetState]].
   */
  sealed trait SetStateEvent[T] {
    def value: T
  }
  /**
   * Event type that instructs the state of a [[SimpleStateKey]] to be
   * replaced with the given value.
   */
  final case class SetState[T](override val value: T) extends SetStateEvent[T] with Seq[SetStateEvent[T]] {
    def iterator: Iterator[SetStateEvent[T]] = Iterator.single(this)
    def apply(idx: Int): SetStateEvent[T] =
      if (idx == 0) this
      else throw new IndexOutOfBoundsException(s"$idx (for single-element sequence)")
    def length: Int = 1
  }

  /**
   * Use this key for state that shall neither be persistent nor event-sourced.
   * In effect this turns `updateState` into access to a State monad identified
   * by this key instance.
   *
   * Beware that reference equality is used to identify this key: you should
   * create the key as a `val` inside a top-level `object`.
   */
  final class SimpleStateKey[T](override val initial: T) extends StateKey[T] {
    type Event = SetStateEvent[T]
    def apply(state: T, event: SetStateEvent[T]) = event.value
    override def toString: String = f"SimpleStateKey@$hashCode%08X($initial)"
  }

  /**
   * The data for a [[StateKey]] of this kind can be marked as persistent by
   * invoking `replayPersistentState`—this will first replay the stored events
   * and subsequently commit all emitted events to the journal before applying
   * them to the state.
   *
   * FIXME persistence is not yet implemented
   */
  //trait PersistentStateKey[T] extends StateKey[T] {
  //  def clazz: Class[Event]
  //}

  /*
   * Derived operations
   */

  /**
   * Fork the given processes the return the first value emitted by any one of
   * them. As soon as one process has yielded its value all others are canceled.
   */
  def firstOf[P <: Processes, T](processes: P)(implicit l: Processes.lubAux[P, T], f: Processes.fork[P, E.Fork]): Operation[T, T, f.Out] = {
    def forkAll[P0 <: Processes](self: ActorRef[T], index: Int = 0, p: P0 = processes, acc: List[SubActor[Nothing]] = Nil)(
      implicit opDSL: OpDSL { type Self = T }, f: Processes.fork[P0, E.Fork]): Operation[T, List[SubActor[Nothing]], f.Out] =
      p match {
        case _0 ⇒ opUnit(acc)
        case x :|: xs ⇒
          opFork(x.copy(name = s"$index-${x.name}").map(self ! _))
            .flatMap(sub ⇒ forkAll(self, index + 1, xs, sub :: acc))
      }
    OpDSL[T] { implicit opDSL ⇒
      for {
        self ← opProcessSelf
        subs ← forkAll(self)
        value ← opRead
      } yield {
        subs.foreach(_.cancel())
        value
      }
    }
  }

  /**
   * Suspend the process for the given time interval and deliver the specified
   * value afterwards. This is especially useful as a timeout value for `firstOf`.
   */
  def delay[T](time: FiniteDuration, value: T): Operation[T, T, _0] =
    OpDSL[T] { implicit opDSL ⇒
      for {
        self ← opProcessSelf
        _ ← opSchedule(time, value, self)
      } yield opRead
    }.ignoreEffects

  /**
   * Fork the given process, but also fork another process that will cancel the
   * first process after the given timeout.
   */
  def forkAndCancel[T, E <: Effects](timeout: FiniteDuration, process: Process[T, Any, E])(
    implicit opDSL: OpDSL): Operation[opDSL.Self, SubActor[T], E.Fork[E] :: E.Fork[E.Send[Boolean] :: E.Read[Boolean] :: _0] :: _0] = {
    def guard(sub: SubActor[T]) = OpDSL[Boolean] { implicit opDSL ⇒
      for {
        self ← opProcessSelf
        _ ← opWatch(sub.ref, false, self)
        _ ← opSchedule(timeout, true, self)
        cancel ← opRead
        if cancel
      } yield sub.cancel()
    }

    for {
      sub ← opFork(process)
      _ ← opFork(guard(sub).named("cancelAfter"))
    } yield sub
  }

  /**
   * Retry the given process the specified number of times, always bounding
   * the wait time by the given timeout and canceling the fruitless process.
   * If the number of retries is exhausted, the entire Actor will be failed.
   */
  // FIXME figure out effects
  //  def retry[S, T](timeout: FiniteDuration, retries: Int, ops: Process[S, T])(implicit opDSL: OpDSL): Operation[opDSL.Self, T] = {
  //    opCall(firstOf(ops.map(Some(_)), delay(timeout, None).named("retryTimeout")).named("firstOf"))
  //      .map {
  //        case Some(res)           ⇒ opUnit(res)
  //        case None if retries > 0 ⇒ retry(timeout, retries - 1, ops)
  //        case None                ⇒ throw new RetriesExceeded(s"process ${ops.name} has been retried $retries times with timeout $timeout")
  //      }
  //  }

  /**
   * The main ActorRef of an Actor hosting [[Process]] instances accepts this
   * type of messages. The “main process” is the one with which the Actor is
   * spawned and which may fork or call other processes. Its input of type `T`
   * can be reached using [[MainCmd]] messages. Other subtypes are used for
   * internal purposes.
   */
  sealed trait ActorCmd[+T]
  /**
   * Send a message to the “main process” of an Actor hosting processes. Note
   * that this message is routed via the host Actor’s behavior and then through
   * the [[Process]] mailbox of the main process.
   */
  case class MainCmd[+T](cmd: T) extends ActorCmd[T]
  trait InternalActorCmd[+T] extends ActorCmd[T]

  /**
   * Forking a process creates a sub-Actor within the current actor that is
   * executed concurrently. This sub-Actor [[Process]] has its own [[ActorRef]]
   * and it can be canceled.
   */
  trait SubActor[-T] {
    def ref: ActorRef[T]
    def cancel(): Unit
  }
}
