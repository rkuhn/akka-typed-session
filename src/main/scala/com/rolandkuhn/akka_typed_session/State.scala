/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session

  /**
   * A key into the Actor’s state map that allows access both for read and
   * update operations. Updates are modeled by emitting events of the specified
   * type. The updates are applied to the state in the order in which they are
   * emitted. For persistent state data please refer to [[PersistentStateKey]]
   * and for ephemeral non-event-sourced data take a look at [[SimpleStateKey]].
   */
  trait StateKey[T, Event] {
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
  final class SimpleStateKey[T](override val initial: T) extends StateKey[T, SetStateEvent[T]] {
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
