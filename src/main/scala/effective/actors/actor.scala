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

import java.util.UUID

import cats.FlatMap
import cats.effect.{ IO, _ }
import cats.effect.concurrent._
import cats.implicits._
import cats.effect.syntax._
import fs2.async.mutable.Queue
import fs2.async.unboundedQueue

import scala.language.higherKinds

object actor {

  type ActorId = String

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
  def createActor[F[_], S, I, O](
      initialState: S,
      receive: (S, I) => F[(S, O)],
      identifier: F[ActorId],
  )(implicit F: Concurrent[F], M: FlatMap[F]): F[(I => F[O], CancelToken[F])] = {

    val receiveOneMsg: Ref[F, S] => Queue[F, (I, Deferred[F, O])] => F[Unit] =
      state =>
        queue =>
          for {
            (i, deferred)        <- queue.dequeue1
            currentState         <- state.get
            (nextState, outcome) <- receive(currentState, i)
            _                    <- state.set(nextState)
            _                    <- deferred.complete(outcome)
            _                    <- IO.cancelBoundary.to[F]
          } yield ()

    val sendToMailbox: Queue[F, (I, Deferred[F, O])] => I => F[O] = { queue => msg =>
      for {
        deferred <- Deferred.apply[F, O]
        _        <- queue.offer1((msg, deferred))
        result   <- deferred.get
      } yield result
    }

    for {
      id    <- identifier
      _     <- Concurrent[F].delay(println(s"Starting actor $id"))
      state <- Ref.of[F, S](initialState)
      queue <- unboundedQueue[F, (I, Deferred[F, O])]
      fiber <- F.start[Unit](receiveOneMsg(state)(queue).foreverM)
    } yield
      (sendToMailbox(queue), Concurrent[F].delay[Unit](println(s"Stopping ${id}")) *> fiber.cancel)

  }

}
