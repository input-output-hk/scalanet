package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.netty.util
import monix.eval.Task
import monix.execution.Cancelable

object InetPeerGroupUtils {
  def toTask(f: util.concurrent.Future[_]): Task[Unit] = {
    Task.create[Unit] { (_, cb) =>
      f.addListener(
        (future: util.concurrent.Future[_]) => if (future.isSuccess) cb.onSuccess(()) else cb.onError(future.cause())
      )
      Cancelable.empty
    }
  }

  def getChannelId(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress): Seq[Byte] = {
    val b = ByteBuffer.allocate(16)
    b.put(remoteAddress.getAddress.getAddress)
    b.putInt(remoteAddress.getPort)
    b.put(localAddress.getAddress.getAddress)
    b.putInt(localAddress.getPort)
    b.array().toIndexedSeq
  }
}
