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
import java.util.concurrent.atomic.AtomicInteger

import utest._
import actor._
import cats.{ Applicative, Eq, Id }
import cats.effect._
import cats.effect.concurrent.Ref
import cats.effect.internals.IOContextShift
import cats.implicits._
import effective.actors.actor.Return.Result

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object BasicActorTests extends TestSuite {

  sealed trait Messages
  case object Inc extends Messages
  implicit def eqFoo[O]: Eq[Return[O]] = Eq.fromUniversalEquals

  def behaviour[F[_]: Applicative]: Messages => Int => F[(Int, Result[Int])] = {
    case Inc =>
      counter =>
        Applicative[F].pure(counter + 1, Return.Result(counter + 1))
  }

  implicit val ec = IOContextShift.global

  implicit val timer = IO.timer(ExecutionContext.Implicits.global)

  def repeatN[T](n: Int, f: IO[T]): IO[T] = {
    def _repeatN(n: Int, acc: IO[T]): IO[T] =
      if (n <= 1) acc else _repeatN(n - 1, acc *> f)
    _repeatN(n, f)
  }

  override val tests: Tests = Tests {
    "counter" - {
      val counter = mkActorSync(0, behaviour[Id], IO(UUID.randomUUID().toString))

      val res = for {
        (id1, ref, killIt)   <- counter
        (id2, ref2, killIt2) <- counter
        _                    <- ref(Inc)
        _                    <- ref(Inc)
        _                    <- ref(Inc)
        _                    <- ref(Inc)
        result               <- ref(Inc)
        res2                 <- ref2(Inc)
        _                    <- killIt
        _                    <- killIt2
      } yield (result, res2)

      assert(res.unsafeRunSync() === ((Result(5), Result(1))))
    }
    "forever.start.cancel should return instantly" - {
      val f = for {
        fiber <- IO("success").foreverM.start
        _     <- fiber.cancel
      } yield "success"

      assert(IO.race(f, IO.sleep(200 millis) *> IO("failed")).unsafeRunSync() == Left("success"))
    }

    "a killed actor should never respond" - {
      val counter = mkActorSync(0, behaviour[Id], IO(UUID.randomUUID().toString))

      val shouldNotTerminate = for {
        (_, ref, killIt) <- counter
        _                <- ref(Inc)
        _                <- killIt
      } yield ref
      val raced: IO[Either[ActorId, Return[Int]]] =
        IO.race(IO.sleep(500 millis) *> IO("failed"), shouldNotTerminate.flatMap(f => f(Inc)))

      val result = raced.unsafeRunSync()
      println(result)
      assert(result.isLeft)
    }
    val times = 1000 * 1000

    "benchmark with Ref" - {
      val eff = for {
        ref <- Ref[IO].of(0)
        res <- repeatN(times, ref.modify(c => (c + 1, c + 1)))
      } yield res
      assert(eff.unsafeRunSync == times)
    }

    "benchmark with AtomicInt" - {
      val i = new AtomicInteger(0)
      while (i.get < times) {
        i.addAndGet(1)
      }
      assert(i.get() == times)
    }

    "benchmark without overhead" - {
      var i = 0
      while (i < times) {
        i += 1
      }
      assert(i == times)
    }

    "benchmark with fs2" - {
      val stream: fs2.Stream[IO, Int] = fs2.Stream.fromIterator[IO, Int]({
        var i = 0
        new Iterator[Int] {
          override def hasNext: Boolean = i < times

          override def next(): Int = {
            i += 1
            1
          }
        }
      })
      val result: Option[Int] = stream.fold(0)(_ + _).compile.last.unsafeRunSync()
      assert(result == Option(times))
    }

    def baseActorTest(actor: IO[Actor[IO, Int, Messages, Int]]): Int = {
      val result: IO[Return[Int]] = for {
        (_, ref, kill) <- actor
        res            <- repeatN(times, ref(Inc))
        _              <- kill.start
      } yield res

      val r = result.unsafeRunSync()
      r match {
        case Return.Result(i) =>
          i
        case r =>
          throw AssertionError(s"result was $r", Seq.empty)
      }

    }

    "benchmark with actor Sync " - {

      val counter = mkActorSync(0, behaviour[Id], IO(UUID.randomUUID().toString))
      assert(baseActorTest(counter) == times)
    }
    
    "benchmark with actor IO" - {
      val b: Messages => Int => IO[(Int, Result[Int])] = behaviour[IO]
      val counter                                      = mkActorF[IO, Int, Messages, Int](0, b, IO(UUID.randomUUID().toString))
      val r                                            = baseActorTest(counter)
      assert(r == times)
    }
  }
}
