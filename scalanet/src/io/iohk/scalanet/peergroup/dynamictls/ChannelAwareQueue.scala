package io.iohk.scalanet.peergroup.dynamictls

import io.iohk.scalanet.peergroup.CloseableQueue
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
  * WARNING: Actual limit may sometimes goes higher, as each netty read can return more than one message.
  *
  * @param limit how many items can accumulate in the queue
  * @param queue is the underlying closeable message queue
  */
private[scalanet] final class ChannelAwareQueue[M] private (limit: Int, queue: CloseableQueue[M]) {
  private val queueSize = new AtomicLong(0)

  private val lowerBound: Int = Math.max(1, limit / 2)

  def size: Long = queueSize.get()

  def offer(channelConfig: ChannelConfig, a: M): Task[Unit] = {
    Task(enableBPIfNecessary(channelConfig)).flatMap(_ => queue.offer(a).void)
  }

  def next(channelConfig: ChannelConfig): Task[Option[M]] = {
    queue.next.map {
      case Some(value) =>
        disableBPIfNecessary(channelConfig)
        Some(value)
      case None =>
        None
    }
  }

  def close(discard: Boolean): Task[Unit] = queue.close(discard)

  private def enableBPIfNecessary(channelConfig: ChannelConfig): Unit =
    if (queueSize.incrementAndGet() >= limit && channelConfig.isAutoRead) {
      channelConfig.setAutoRead(false)
      ()
    }

  private def disableBPIfNecessary(channelConfig: ChannelConfig): Unit =
    if (queueSize.decrementAndGet() <= lowerBound && !channelConfig.isAutoRead) {
      channelConfig.setAutoRead(true)
      ()
    }
}

object ChannelAwareQueue {
  def apply[M](limit: Int, channelType: ChannelType): Task[ChannelAwareQueue[M]] = {
    CloseableQueue.unbounded[M](channelType).map(queue => new ChannelAwareQueue[M](limit, queue))
  }

}
