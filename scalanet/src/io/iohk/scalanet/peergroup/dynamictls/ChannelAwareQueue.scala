package io.iohk.scalanet.peergroup.dynamictls

import io.iohk.scalanet.peergroup.CloseableQueue
import io.netty.channel.ChannelConfig
import monix.eval.Task
import monix.execution.ChannelType

import java.util.concurrent.atomic.AtomicLong

private[scalanet] final class ChannelAwareQueue[M] private (limit: Int, queue: CloseableQueue[M]) {
  private val queueSize = new AtomicLong(0)

  private val lowerBound: Int = Math.max(1, limit / 2)

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
