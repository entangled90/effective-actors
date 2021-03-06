/*
 * Copyright 2018 carlo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package effective.actors

import java.time.Instant

import cats.FlatMap
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.effect.syntax._
import effective.actors.actor.Return
import fs2.async.mutable.Queue
import fs2.async.unboundedQueue

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds
import scala.util.control.NoStackTrace

object actor {

  type ActorId = String
  case class IllegalStateUpdate[S](previous: S, next: S) extends Exception with NoStackTrace

  trait Logger[F[_]] {
    def debug(s: => String): F[Unit]
    def info(s: => String): F[Unit]
    def warn(s: => String): F[Unit]
    def error(s: => String): F[Unit]
  }

  object Logger {
    def apply[F[_]: Concurrent: Timer]: Logger[F] = implicitly[Logger[F]]

    def apply[F[_]: Concurrent: Timer](output: String => F[Unit]): Logger[F] =
      new LoggerImpl[F](output)

    private class LoggerImpl[F[_]](output: String => F[Unit])(implicit eff: Concurrent[F],
                                                              timer: Timer[F])
        extends Logger[F] {
      private val log: (=> String, => String) => F[Unit] = (s, level) => {
        timer.clock
          .realTime(MILLISECONDS)
          .flatMap(millis => output(s"[$level] @ ${Instant.ofEpochMilli(millis)}: $s"))
      }
      override def debug(s: => String): F[Unit] = log(s, "DEBUG")
      override def info(s: => String): F[Unit]  = log(s, "INFO")
      override def warn(s: => String): F[Unit]  = log(s, "WARN")
      override def error(s: => String): F[Unit] = log(s, "ERROR")
    }

    implicit def logger[F[_]](implicit eff: Concurrent[F], timer: Timer[F]): Logger[F] =
      new LoggerImpl[F](s => Concurrent[F].delay(println(s)))
  }

  val killTimeout: FiniteDuration = 200 millis

  type Actor[F[_], S, I, O] = (ActorId, I => F[Return[O]], CancelToken[F])

  /**
    *
    *
    * @param initialState
    * @tparam F effect
    * @tparam S state
    * @tparam I input message
    * @tparam O output result
    * @return
    */
  def mkActorF[F[_], S, I, O](
      initialState: S,
      receive: I => S => F[(S, Return[O])],
      identifier: F[ActorId],
  )(implicit F: Concurrent[F],
    timer: Timer[F],
    contextShift: ContextShift[F],
    log: Logger[F],
    M: FlatMap[F]): F[Actor[F, S, I, O]] = {
    val modifyState: I => Ref[F, S] => F[Return[O]] = msg =>
      ref =>
        for {
          state                <- ref.get
          (nextState, outcome) <- receive(msg)(state)
          setResult            <- ref.compareAndSet(state, nextState)
          _ <- if (setResult) Concurrent[F].unit
          else Concurrent[F].raiseError(IllegalStateUpdate(state, nextState))
        } yield outcome
    mkActor(initialState, modifyState, identifier)
  }

  def mkActorSync[F[_], S, I, O](
      initialState: S,
      receive: I => S => (S, Return[O]),
      identifier: F[ActorId],
  )(implicit F: Concurrent[F],
    timer: Timer[F],
    contextShift: ContextShift[F],
    log: Logger[F],
    M: FlatMap[F]): F[Actor[F, S, I, O]] = {
    val modifyState: I => Ref[F, S] => F[Return[O]] = msg => ref => ref.modify(receive(msg))
    mkActor(initialState, modifyState, identifier)
  }

  def mkActorLowContention[F[_], S, I, O](
      initialState: S,
      receive: I => S => (S, Return[O]),
      identifier: F[ActorId],
  )(implicit F: Concurrent[F],
    timer: Timer[F],
    contextShift: ContextShift[F],
    log: Logger[F],
    M: FlatMap[F]): F[Actor[F, S, I, O]] = ???

  private def mkActor[F[_], S, I, O](
      initialState: S,
      modifyState: I => Ref[F, S] => F[Return[O]],
      identifier: F[ActorId],
  )(implicit F: Concurrent[F],
    M: FlatMap[F],
    timer: Timer[F],
    log: Logger[F],
    contextShift: ContextShift[F]): F[Actor[F, S, I, O]] = {

    val receiveOneMsg: Ref[F, S] => Queue[F, (I, Deferred[F, Return[O]])] => F[Return[O]] =
      state =>
        queue =>
          for {
            (i, deferred) <- queue.dequeue1
            outcome       <- modifyState(i)(state)
            _             <- deferred.complete(outcome)
          } yield outcome

    val sendToMailbox: Queue[F, (I, Deferred[F, Return[O]])] => I => F[Return[O]] = {
      queue => msg =>
        for {
          deferred <- Deferred.apply[F, Return[O]]
          _        <- queue.offer1((msg, deferred))
          result   <- deferred.get
        } yield result
    }

    for {
      id    <- identifier
      _     <- log.info(s"starting actor $id")
      state <- Ref.of(initialState)
      // Just here for the moment.
      ctx = Ctx.empty[F](id)
      queue <- unboundedQueue[F, (I, Deferred[F, Return[O]])]
      loop = receiveOneMsg(state)(queue) >>= handleReturn[F, O].apply(ctx)
      fiber <- F.start[Unit](loop.foreverM[Unit])
      cancelToken = log.debug(s"Stopping actor $id") *> F.race(
        fiber.cancel,
        timer.sleep(killTimeout)
      ) *> log.debug(
        s"Actor $id stopped"
      )
    } yield (id, sendToMailbox(queue), cancelToken)
  }

  private def handleReturn[F[_]: Logger: Concurrent: Timer, O]: Ctx[F] => Return[O] => F[Unit] =
    ctx => {
      case _: Return.Result[_] =>
        Concurrent[F].unit
      case err: Return.Error[_] =>
        Logger[F].error(s"Actor ${ctx.id} failed: ${err.reason}") *>
        Concurrent[F].never[Unit]
      case Return.Stop =>
        Logger[F].error(s"Actor ${ctx.id} stopped by external command") *>
        Concurrent[F].never[Unit]
    }

  case class Ctx[F[_]](id: ActorId, stop: F[Unit])

  object Ctx {
    def empty[F[_]: Concurrent](id: ActorId): Ctx[F] = Ctx[F](id, Concurrent[F].unit)
  }

  sealed trait Return[O]
  object Return {
    case class Result[O](result: O) extends Return[O]
    case object Stop                extends Return[Nothing]
    case class Error[E](reason: E)  extends Return[Nothing]
  }

}
