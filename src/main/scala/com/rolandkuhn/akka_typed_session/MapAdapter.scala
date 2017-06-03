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
    new MapAdapter[Any, Operation[Any, Any, HNil], Any, HNil] {
      override def lift[O](f: O ⇒ Operation[Any, Any, HNil]): O ⇒ Operation[Any, Any, HNil] = f
    }

  implicit def mapAdapterOperation[Self, M, E <: Effects]: MapAdapter[Self, Operation[Self, M, E], M, E] =
    _adapter.asInstanceOf[MapAdapter[Self, Operation[Self, M, E], M, E]]
}
/**
 * Helper to make `Operation.map` or `Operation.foreach` behave like `flatMap` when needed.
 */
trait MapAdapterLow {
  private val _adapter =
    new MapAdapter[Any, Any, Any, HNil] {
      override def lift[O](f: O ⇒ Any): O ⇒ Operation[Any, Any, HNil] = o ⇒ Impl.Return(f(o))
    }

  implicit def mapAdapterAny[Self, Out]: MapAdapter[Self, Out, Out, HNil] =
    _adapter.asInstanceOf[MapAdapter[Self, Out, Out, HNil]]
}
