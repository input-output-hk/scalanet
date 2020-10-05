package io.iohk.scalanet.peergroup

import cats.implicits._
import monix.execution.Scheduler
import monix.eval.Task
import monix.tail.Iterant
import monix.reactive.Observable
import org.scalatest.{FlatSpec, Matchers}
import scala.concurrent.duration._
import org.scalatest.compatible.Assertion

class CloseableQueueSpec extends FlatSpec with Matchers {
  import Scheduler.Implicits.global
  import CloseableQueue.Closed

  def testQueue(
      capacity: Int = 0
  )(f: CloseableQueue[String] => Task[Assertion]): Unit = {
    CloseableQueue[String](capacity).flatMap(f).void.runSyncUnsafe(5.seconds)
  }

  behavior of "ClosableQueue"

  it should "publish a message" in testQueue() { queue =>
    val msg = "Hello!"
    for {
      _ <- queue.tryOffer(msg)
      maybeMessage <- queue.next()
    } yield {
      maybeMessage shouldBe Some(msg)
    }
  }

  it should "return None if it's closed" in testQueue() { queue =>
    for {
      _ <- queue.close(discard = true)
      maybeOffered <- queue.tryOffer("Hung up?")
      maybeMessage <- queue.next()
    } yield {
      maybeOffered shouldBe Left(Closed)
      maybeMessage shouldBe None
    }
  }

  it should "return all remaining messages if they aren't discarded during close" in testQueue() { queue =>
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

  it should "return none of remaining messages if they are discarded during close" in testQueue() { queue =>
    for {
      _ <- queue.tryOffer("Foo")
      _ <- queue.tryOffer("Bar")
      _ <- queue.close(discard = true)
      maybeNext <- queue.next()
    } yield {
      maybeNext shouldBe empty
    }
  }

  it should "not throw if close is called multiple times" in testQueue() { queue =>
    for {
      _ <- queue.offer("Spam")
      _ <- queue.close(discard = false)
      _ <- queue.close(discard = true)
      m <- queue.next()
    } yield {
      m shouldBe None
    }
  }

  behavior of "tryOffer"

  it should "discard the latest value if the capacity is reached" in testQueue(capacity = 2) { queue =>
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

  it should "wait until messages are drained if the capacity is reached" in testQueue(capacity = 2) { queue =>
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

  it should "not wait if the queue is closed" in testQueue() { queue =>
    for {
      _ <- queue.close(discard = true)
      attempt <- queue.offer("Too late.")
    } yield {
      attempt shouldBe Left(Closed)
    }
  }

  it should "be interrupted if the queue is closed while offering" in testQueue(capacity = 2) { queue =>
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

  behavior of "toIterant"

  it should "not do internal buffering" in testQueue() { queue =>
    implicit val scheduler = Scheduler.fixedPool("test", 16)
    for {
      _ <- queue.offer("a")
      _ <- queue.offer("b")
      _ <- queue.offer("c")
      // This test is only here to demonstrate that this verstion with `.share` doesn't work:
      //o = queue.toObservable.share
      // But these one do:
      //o = queue.toObservable
      o = queue.toIterant
      _ <- o.headOptionL
      b <- o.take(1).toListL.timeoutTo(10.millis, Task.now(Nil))
      _ <- queue.close(discard = false)
      c <- queue.next()
    } yield {
      b shouldBe List("b")
      c shouldBe Some("c")
    }
  }

  implicit class ClosableQueueOps[A](queue: CloseableQueue[A]) {

    def toIterant: Iterant[Task, A] =
      Iterant.repeatEvalF(queue.next()).takeWhile(_.isDefined).map(_.get)

    def toObservable: Observable[A] =
      Observable.repeatEvalF(queue.next()).takeWhile(_.isDefined).map(_.get)
  }
}
