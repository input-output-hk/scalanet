package io.iohk.scalanet.peergroup.dynamictls

import io.iohk.scalanet.peergroup.CloseableQueue
import io.iohk.scalanet.peergroup.CloseableQueue.Closed
import io.netty.channel.ChannelConfig
import monix.eval.Task
import monix.execution.ChannelType

import java.util.concurrent.atomic.AtomicLong

/**
  * Wraps an underlying unbounded CloseableQueue queue and bounds it based on netty auto-read feature.
  * While auto-read is disabled received messages start to accumulate in underlying os RCV tcp buffer. When RCV buffer is full
  * sender SND buffer will start to buffer un-sent bytes. When sender SND buffer is full, the default behaviour is that
  * write(xxx) will block.  In our case, sendMessage Task will not finish until there will be place in SND buffer
  *
  *
  * WARNING: Actual limit may sometimes go higher, as each netty read can return more than one message.
  *
  * @param limit how many items can accumulate in the queue
  * @param queue is the underlying closeable message queue
  */
private[scalanet] final class ChannelAwareQueue[M] private (
    limit: Int,
    queue: CloseableQueue[M],
    channelConfig: ChannelConfig
) {
  private val queueSize = new AtomicLong(0)

  private val lowerBound: Int = Math.max(1, limit / 2)

  def size: Long = queueSize.get()

  def offer(a: M): Task[Either[Closed, Unit]] = {
    Task(enableBackPressureIfNecessary()) >> queue.offer(a)
  }

  def next: Task[Option[M]] = {
    queue.next.map {
      case Some(value) =>
        disableBackPressureIfNecessary()
        Some(value)
      case None =>
        None
    }
  }

  def close(discard: Boolean): Task[Unit] = queue.close(discard)

  private def enableBackPressureIfNecessary(): Unit =
    if (queueSize.incrementAndGet() >= limit && channelConfig.isAutoRead) {
      channelConfig.setAutoRead(false)
      ()
    }

  private def disableBackPressureIfNecessary(): Unit =
    if (queueSize.decrementAndGet() <= lowerBound && !channelConfig.isAutoRead) {
      channelConfig.setAutoRead(true)
      ()
    }
}

object ChannelAwareQueue {
  def apply[M](limit: Int, channelType: ChannelType, channelConfig: ChannelConfig): Task[ChannelAwareQueue[M]] = {
    CloseableQueue.unbounded[M](channelType).map(queue => new ChannelAwareQueue[M](limit, queue, channelConfig))
  }

}
