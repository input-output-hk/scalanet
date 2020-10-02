package io.iohk.scalanet.peergroup

import java.net.{InetSocketAddress, ServerSocket}

import io.netty.util
import monix.eval.Task

object InetPeerGroupUtils {

  type ChannelId = (InetSocketAddress, InetSocketAddress)

  // TODO: This has nothing to do with InetPeerGroup, move it somewhere else.
  def toTask(f: => util.concurrent.Future[_]): Task[Unit] = {
    Task.async[Unit] { cb =>
      try {
        f.addListener(
          (future: util.concurrent.Future[_]) => if (future.isSuccess) cb.onSuccess(()) else cb.onError(future.cause())
        )
        ()
      } catch {
        case t: Throwable =>
          cb.onError(t)
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
