package io.iohk.scalanet.peergroup

import cats.implicits._
import monix.execution.Scheduler
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}
import scala.concurrent.duration._
import org.scalatest.compatible.Assertion

class CloseableQueueSpec extends FlatSpec with Matchers {
  import Scheduler.Implicits.global
  import CloseableQueue.Closed

  def withQueue(
      capacity: Int = 0
  )(f: CloseableQueue[String] => Task[Assertion]): Unit = {
    CloseableQueue[String](capacity).flatMap(f).void.runSyncUnsafe(5.seconds)
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
      maybeOffered <- queue.tryOffer("Hung up?")
      maybeMessage <- queue.next()
    } yield {
      maybeOffered shouldBe Left(Closed)
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

  behavior of "tryOffer"

  it should "discard the latest value if the capacity is reached" in withQueue(capacity = 2) { queue =>
    for {
      offered <- List.range(0, 5).traverse(i => queue.tryOffer(i.toString))
      maybe0 <- queue.next()
      maybe1 <- queue.next()
      maybe3 <- queue.next().start // No more message so this would block.
      _ <- queue.close(discard = false)
      maybe3 <- maybe3.join
    } yield {
      offered.head shouldBe Right(true)
      offered.last shouldBe Right(false)
      maybe0 should not be empty
      maybe1 should not be empty
      maybe3 shouldBe empty
    }
  }

  behavior of "offer"

  it should "wait until messages are drained if the capacity is reached" in withQueue(capacity = 2) { queue =>
    for {
      _ <- queue.offer("a")
      _ <- queue.offer("b")
      attempt1 <- queue.tryOffer("c")
      offering <- queue.offer("c").start
      _ <- queue.next()
      attempt2 <- offering.join
    } yield {
      attempt1 shouldBe Right(false)
      attempt2 shouldBe Right(())
    }
  }

  it should "not wait if the queue is closed" in withQueue() { queue =>
    for {
      _ <- queue.close(discard = true)
      attempt <- queue.offer("Too late.")
    } yield {
      attempt shouldBe Left(Closed)
    }
  }

  it should "be interrupted if the queue is closed while offering" in withQueue(capacity = 2) { queue =>
    for {
      _ <- queue.offer("a")
      _ <- queue.offer("b")
      offering <- queue.offer("c").start
      _ <- queue.close(discard = true)
      offered <- offering.join
    } yield {
      offered shouldBe Left(Closed)
    }
  }
}
