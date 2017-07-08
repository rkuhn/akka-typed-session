[![Build Status](https://travis-ci.org/rkuhn/akka-typed-session.svg?branch=master)](https://travis-ci.org/rkuhn/akka-typed-session)

# Akka Typed Session

Add-on to Akka Typed that tracks effects for use with Session Types.

## Example

Assume this message protocol:

~~~scala
case class AuthRequest(credentials: String)(replyTo: ActorRef[AuthResult])

sealed trait AuthResult
case object AuthRejected extends AuthResult
case class AuthSuccess(token: ActorRef[Command]) extends AuthResult

sealed trait Command
case object DoIt extends Command
~~~

The process to be followed is that first an `AuthRequest` is sent, answered by
an `AuthResult` that may or may not unlock further communication of `Command`
messages. A more formal definition of this sequence is the following:

~~~scala
trait Protocol {
  type Session <: Effects
}

object MyProto extends Protocol {
  type Session = //
    Send[AuthRequest] :: // first ask for authentication
    Read[AuthResult] :: // then read the response
    Choice[(Halt :: _0) :+: _0 :+: CNil] :: // then possibly terminate if rejected
    Send[Command] :: _0 // then send a command
}
~~~

Implementing the first part of this exchange is the familiar ask pattern or request–response.
We can use the process DSL to factor this out into a utility function:

~~~scala
def opAsk[T, U](target: ActorRef[T], msg: ActorRef[U] => T) =
  OpDSL[U] { implicit opDSL =>
    for {
      self <- opProcessSelf
      _ <- opSend(target, msg(self))
    } yield opRead
  }
~~~

Here the `OpDSL` constructor provides an environment in which the behavior behind a typed
`ActorRef[U]` can be defined. The `opProcessSelf` is an operation that when run will yield
the aforementioned `ActorRef[U]`. `opSend` is an operation that when run will send the given
message to the given target. As the last step in this mini-protocol `opRead` awaits the
reception of a message sent to `self`, i.e. the received message will be of type `U`.

*It should be noted that in this process DSL algebra the `.map()` combinator has the same behavior
as `.flatMap()` where possible, i.e. it will flatten if the returned value is an `Operation`. This
is necessary in order to avoid memory leaks for infinite processing loops (as seen e.g. in
server processes that respond to an unbounded number of requests).*

Now we can use this ask operation in the context of the larger overall process. Calling such
a compound operation is done via the `opCall` operator.

~~~scala
val auth: ActorRef[AuthRequest] = ??? // assume we get the authentication endpoint from somewhere

val p = OpDSL[String] { implicit opDSL ⇒
  for {
    AuthSuccess(token) <- opCall(opAsk(auth, AuthRequest("secret")).named("getAuth"))
  } yield opSend(token, DoIt)
}
~~~

The resulting type of `p` is not just an Operation with `String` for the self-type, yielding `Unit` (the result of `opSend`), 
but it also tracks the effects that occur when executing the whole process. We can assert that the externally visible effects
of sending and receiving messages match the protocol definition given above by asking the compiler to construct a proof:

~~~scala
def vetProtocol[E <: Effects, F <: Effects](p: Protocol, op: Operation[_, _, E])(
  implicit f: E.ops.FilterAux[E, ExternalEffect, F], ev: F <:< p.Session): Unit = ()

vetProtocol(MyProto, p)
~~~

This works in two steps: first the list of effects E (which is somewhat like an HList with a special node for infinite loops)
is filtered so that only effects remain that are subtypes of `ExternalEffect`, yielding the list F. Then this list is compared
to the `Session` type member of the given protocol to see whether it is a subtype.

The result is that the whole program only compiles if the process performs all the required externally visible effects in
the right order. If a step is forgotten or duplicated then the `vetProtocol` invocation will raise a type error.

## Legal

See the LICENSE file for details on licensing and CONTRIBUTING for the contributor’s guide.

Copyright 2017 Roland Kuhn
