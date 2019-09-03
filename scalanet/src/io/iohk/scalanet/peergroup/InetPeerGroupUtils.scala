package io.iohk.scalanet.peergroup

import java.net.{InetSocketAddress, ServerSocket}

import io.netty.util
import monix.eval.Task
import monix.execution.Cancelable

object InetPeerGroupUtils {

  type ChannelId = (InetSocketAddress, InetSocketAddress)

  def toTask(f: => util.concurrent.Future[_]): Task[Unit] = {
    Task.create[Unit] { (_, cb) =>
      try {
        f.addListener(
          (future: util.concurrent.Future[_]) => if (future.isSuccess) cb.onSuccess(()) else cb.onError(future.cause())
        )
      } catch {
        case t: Throwable =>
          cb.onError(t)
      }
      Cancelable.empty
    }
  }

  def getChannelId(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress): ChannelId = {
    (remoteAddress, localAddress)
  }

  def aRandomAddress(): InetSocketAddress = {
    val s = new ServerSocket(0)
    try {
      new InetSocketAddress("localhost", s.getLocalPort)
    } finally {
      s.close()
    }
  }
}
