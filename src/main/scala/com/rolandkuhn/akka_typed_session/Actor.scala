/**
 * Copyright (C) 2017 Roland Kuhn <http://rolandkuhn.com>
 */
package com.rolandkuhn.akka_typed_session

import akka.actor.typed.ActorRef

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
