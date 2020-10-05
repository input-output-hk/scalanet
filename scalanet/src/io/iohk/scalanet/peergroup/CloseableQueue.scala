package io.iohk.scalanet.peergroup

import cats.implicits._
import cats.effect.concurrent.{TryableDeferred, Deferred}
import monix.catnap.ConcurrentQueue
import monix.eval.Task
import monix.execution.{BufferCapacity, ChannelType}
import scala.util.{Left, Right}

/** Wraps an underlying concurrent queue so that polling can return None when
  * the producer side is finished.
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

  /** Stop accepting items in the queue. Clear items if `discard` is true, otherwise let them be drained. */
  def close(discard: Boolean): Task[Unit] =
    closed.complete(discard) >> queue.clear.whenA(discard)

  /** Try to put a new item in the queue, unless the capactiy has been reached or the queue has been closed. */
  def tryOffer(item: A): Task[Boolean] =
    closed.tryGet
      .map(_.isDefined)
      .ifM(
        Task.pure(false),
        // We could drop the oldest item if the queue is full, rather than drop the latest,
        // but the capacity should be set so it only prevents DoS attacks, so it shouldn't
        // be that crucial to serve clients who overproduce.
        queue.tryOffer(item)
      )
}

object CloseableQueue {
  def apply[A](capacity: BufferCapacity, channelType: ChannelType = ChannelType.MPMC) =
    for {
      closed <- Deferred.tryable[Task, Boolean]
      queue <- ConcurrentQueue.withConfig[Task, A](capacity, channelType)
    } yield new CloseableQueue[A](closed, queue)
}
