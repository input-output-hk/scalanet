package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress

import io.netty.util
import monix.eval.Task
import monix.execution.Cancelable

object InetPeerGroupUtils {

  type ChannelId = (InetSocketAddress, InetSocketAddress)

  def toTask(f: => util.concurrent.Future[_]): Task[Unit] = {
    Task.create[Unit] { (_, cb) =>
      f.addListener(
        (future: util.concurrent.Future[_]) => if (future.isSuccess) cb.onSuccess(()) else cb.onError(future.cause())
      )
      Cancelable.empty
    }
  }

  def getChannelId(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress): ChannelId = {
    (remoteAddress, localAddress)
  }

}
