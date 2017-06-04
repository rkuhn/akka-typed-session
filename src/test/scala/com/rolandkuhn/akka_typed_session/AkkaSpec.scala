/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package com.rolandkuhn.akka_typed_session

import org.scalactic.Constraint

import language.postfixOps
import org.scalatest.{ BeforeAndAfterAll, WordSpecLike }
import org.scalatest.Matchers
import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }

import scala.concurrent.duration._
import scala.concurrent.Future
import com.typesafe.config.{ Config, ConfigFactory }
import akka.dispatch.Dispatchers
import akka.testkit._
import akka.testkit.TestEvent._
import org.scalactic.ConversionCheckedTripleEquals
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span

object AkkaSpec {
  val testConf: Config = ConfigFactory.parseString("""
      akka {
        loggers = ["akka.testkit.TestEventListener"]
        typed.loggers = ["akka.typed.testkit.TestEventListener"]
        loglevel = "WARNING"
        stdout-loglevel = "WARNING"
        actor {
          default-dispatcher {
            executor = "fork-join-executor"
            fork-join-executor {
              parallelism-min = 8
              parallelism-factor = 2.0
              parallelism-max = 8
            }
          }
        }
      }
                                                    """)

  def mapToConfig(map: Map[String, AnyRef]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def getCallerName(clazz: Class[_]): String = {
    val s = (Thread.currentThread.getStackTrace map (_.getClassName) drop 1)
      .dropWhile(_ matches "(java.lang.Thread|.*AkkaSpec.?$|.*StreamSpec.?$)")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }

}
