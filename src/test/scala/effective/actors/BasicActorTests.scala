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

import utest._
import actor._
import cats.effect._
import cats.effect.internals.IOContextShift
import cats.implicits._

object BasicActorTests extends TestSuite {

  sealed trait Messages
  case object Inc extends Messages
  val behaviour: (Int, Messages) => IO[(Int, Int)] = {
    case (c, Inc) =>
//      IO(println("Received Inc")) *>
      IO((c + 1, c + 1))
  }

  implicit val ec = IOContextShift.global

  override val tests: Tests = Tests {
    "counter" - {

      val counter = createActor(0, behaviour, IO(UUID.randomUUID().toString))

      val res = for {
        (ref, killIt)   <- counter
        (ref2, killIt2) <- counter
        _               <- ref(Inc)
        _               <- ref(Inc)
        _               <- ref(Inc)
        _               <- ref(Inc)
        result          <- ref(Inc)
        res2            <- ref2(Inc)
        _               <- killIt.start
        _               <- killIt2.start
      } yield (result, res2)

      assert(res.unsafeRunSync() == (5, 1))
    }
  }
}
