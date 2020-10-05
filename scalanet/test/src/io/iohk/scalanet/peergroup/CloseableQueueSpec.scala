package io.iohk.scalanet.peergroup

import cats.implicits._
import monix.execution.{BufferCapacity, Scheduler}
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}
import scala.concurrent.duration._

class CloseableQueueSpec extends FlatSpec with Matchers {
  import Scheduler.Implicits.global

  def withQueue(
      capacity: BufferCapacity = BufferCapacity.Unbounded()
  )(f: CloseableQueue[String] => Task[Unit]): Unit = {
    CloseableQueue[String](capacity).flatMap(f).runSyncUnsafe(5.seconds)
  }

  behavior of "ClosableQueue"

  it should "publish a message" in withQueue() { queue =>
    val msg = "Hello!"
    for {
      _ <- queue.tryOffer(msg)
      maybeMessage <- queue.next()
    } yield {
      maybeMessage shouldBe Some(msg)
    }
  }

  it should "return None if it's closed" in withQueue() { queue =>
    for {
      _ <- queue.close(discard = true)
      maybeMessage <- queue.next()
    } yield {
      maybeMessage shouldBe None
    }
  }

  it should "return all remaining messages if they aren't discarded during close" in withQueue() { queue =>
    for {
      _ <- queue.tryOffer("Foo")
      _ <- queue.tryOffer("Bar")
      _ <- queue.close(discard = false)
      maybeFoo <- queue.next()
      maybeBar <- queue.next()
      maybeNext <- queue.next()
    } yield {
      maybeFoo should not be empty
      maybeBar should not be empty
      maybeNext shouldBe empty
    }
  }

  it should "return none of remaining messages if they are discarded during close" in withQueue() { queue =>
    for {
      _ <- queue.tryOffer("Foo")
      _ <- queue.tryOffer("Bar")
      _ <- queue.close(discard = true)
      maybeNext <- queue.next()
    } yield {
      maybeNext shouldBe empty
    }
  }

  it should "discard the latest value if the capacity is reached" in withQueue(capacity = BufferCapacity.Bounded(2)) {
    queue =>
      for {
        _ <- List.range(0, 5).traverse(i => queue.tryOffer(i.toString))
        maybe0 <- queue.next()
        maybe1 <- queue.next()
        maybe3 <- queue.next().start // No more message so this would block.
        _ <- queue.close(discard = false)
        maybe3 <- maybe3.join
      } yield {
        maybe0 should not be empty
        maybe1 should not be empty
        maybe3 shouldBe empty
      }
  }
}
