package io.iohk.scalanet.peergroup

import java.net.{InetSocketAddress, ServerSocket}
import io.netty.util
import monix.eval.Task

object InetPeerGroupUtils {

  type ChannelId = (InetSocketAddress, InetSocketAddress)

  // TODO: This has nothing to do with InetPeerGroup, move it somewhere else.
  def toTask(f: => util.concurrent.Future[_]): Task[Unit] = {
    Task.cancelable[Unit] { cb =>
      val f2 = f.addListener(
        (future: util.concurrent.Future[_]) =>
          if (future.isSuccess || future.isCancelled) cb.onSuccess(()) else cb.onError(future.cause())
      )

      Task {
        f2.cancel(true)
        ()
      }
    }
  }

  def getChannelId(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress): ChannelId = {
    (remoteAddress, localAddress)
  }

  // TODO: Can we move this to tests?
  def aRandomAddress(): InetSocketAddress = {
    val s = new ServerSocket(0)
    try {
      new InetSocketAddress("localhost", s.getLocalPort)
    } finally {
      s.close()
    }
  }
}
