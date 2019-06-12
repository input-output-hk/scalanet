package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.netty.util
import monix.eval.Task
import monix.execution.Cancelable

import scala.concurrent.Promise
import scala.util.Success

object InetPeerGroupUtils {
  def toTask(f: util.concurrent.Future[_]): Task[Unit] = {
    Task.create[Unit] { (_, cb) =>
      f.addListener(
        (future: util.concurrent.Future[_]) => if (future.isSuccess) cb.onSuccess(()) else cb.onError(future.cause())
      )
      Cancelable.empty
    }

    val promisedCompletion = Promise[Unit]()
    f.addListener((_: util.concurrent.Future[_]) => promisedCompletion.complete(Success(())))
    Task.fromFuture(promisedCompletion.future)
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
