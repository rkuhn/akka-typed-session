package com.rolandkuhn

import language.implicitConversions

package object akka_typed_session {

  /**
   * This implicit expresses that operations that do not use their input channel can be used in any context.
   */
  private[akka_typed_session] implicit def nothingIsSomething[T, U, E <: E1, E1 <: Effects](op: Operation[Nothing, T, E]): Operation[U, T, E1] =
    op.asInstanceOf[Operation[U, T, E1]]

  private[akka_typed_session] implicit class WithEffects[S, O](op: Operation[S, O, _]) {
    def withEffects[E <: Effects]: Operation[S, O, E] = op.asInstanceOf[Operation[S, O, E]]
  }
  implicit class WithoutEffects[S, O](op: Operation[S, O, _]) {
    def ignoreEffects: Operation[S, O, HNil] = op.asInstanceOf[Operation[S, O, HNil]]
  }

}
