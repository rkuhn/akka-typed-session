/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session

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
      override def lift[O](f: O ⇒ Any): O ⇒ Operation[Any, Any, _0] = o ⇒ Impl.Return(f(o))
    }

  implicit def mapAdapterAny[Self, Out]: MapAdapter[Self, Out, Out, _0] =
    _adapter.asInstanceOf[MapAdapter[Self, Out, Out, _0]]
}
