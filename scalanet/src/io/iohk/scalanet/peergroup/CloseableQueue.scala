package io.iohk.scalanet.peergroup

import cats.implicits._
import cats.effect.concurrent.{TryableDeferred, Deferred}
import monix.catnap.ConcurrentQueue
import monix.eval.Task
import monix.execution.{BufferCapacity, ChannelType}
import scala.util.{Left, Right}

/** Wraps an underlying concurrent queue so that polling can return None when
  * the producer side is finished, or vice versa the producer can tell when
  * the consumer is no longer interested in receiving more values.
  *
  *
  * @param closed indicates whether the producer side has finished and whether
  * the messages already in the queue should or discarded (true) or consumed (false).
  * @param queue is the underlying message queue
  */
class CloseableQueue[A](
    closed: TryableDeferred[Task, Boolean],
    queue: ConcurrentQueue[Task, A]
) {
  import CloseableQueue.Closed

  /** Fetch the next item from the queue, or None if the production has finished
    * and the queue has been emptied.
    */
  def next(): Task[Option[A]] =
    closed.tryGet.flatMap {
      case Some(true) =>
        Task.pure(None)

      case Some(false) =>
        queue.tryPoll

      case None =>
        Task.race(closed.get, queue.poll).flatMap {
          case Left(_) =>
            next()
          case Right(item) =>
            Task.pure(Some(item))
        }
    }

  /** Stop accepting items in the queue. Clear items if `discard` is true, otherwise let them be drained.
    * If the queue is already closed it does nothing; this is because either the producer or the consumer
    * could have closed the queue before.
    */
  def close(discard: Boolean): Task[Unit] =
    closed.complete(discard).attempt >> queue.clear.whenA(discard)

  /** Try to put a new item in the queue, unless the capactiy has been reached or the queue has been closed. */
  def tryOffer(item: A): Task[Either[Closed, Boolean]] =
    // We could drop the oldest item if the queue is full, rather than drop the latest,
    // but the capacity should be set so it only prevents DoS attacks, so it shouldn't
    // be that crucial to serve clients who overproduce.
    unlessClosed(queue.tryOffer(item))

  /** Try to put a new item in the queue unless the queue has already been closed. Waits if the capacity has been reached. */
  def offer(item: A): Task[Either[Closed, Unit]] =
    unlessClosed {
      Task.race(closed.get, queue.offer(item)).map(_.leftMap(_ => Closed))
    }.map(_.joinRight)

  private def unlessClosed[T](task: Task[T]): Task[Either[Closed, T]] =
    closed.tryGet
      .map(_.isDefined)
      .ifM(
        Task.pure(Left(Closed)),
        task.map(Right(_))
      )
}

object CloseableQueue {

  /** Indicate that the queue was closed. */
  object Closed
  type Closed = Closed.type

  /** Create a queue with a given capacity; 0 or negative means unbounded. */
  def apply[A](capacity: Int, channelType: ChannelType = ChannelType.MPMC) = {
    val buffer = capacity match {
      case i if i <= 0 => BufferCapacity.Unbounded()
      // Capacity is approximate and a power of 2, min value 2.
      case i => BufferCapacity.Bounded(math.max(2, i))
    }
    for {
      closed <- Deferred.tryable[Task, Boolean]
      queue <- ConcurrentQueue.withConfig[Task, A](buffer, channelType)
    } yield new CloseableQueue[A](closed, queue)
  }

  def unbounded[A](channelType: ChannelType = ChannelType.MPMC) =
    apply[A](capacity = 0)
}
