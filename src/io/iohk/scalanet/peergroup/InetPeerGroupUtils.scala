package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress

import io.netty.util
import monix.eval.Task

import scala.concurrent.Promise
import scala.util.Success

object InetPeerGroupUtils {

  type ChannelId = (InetSocketAddress, InetSocketAddress)

  def toTask(f: util.concurrent.Future[_]): Task[Unit] = {
    val promisedCompletion = Promise[Unit]()
    f.addListener((_: util.concurrent.Future[_]) => promisedCompletion.complete(Success(())))
    Task.fromFuture(promisedCompletion.future)
  }

  def getChannelId(remoteAddress: InetSocketAddress, localAddress: InetSocketAddress): ChannelId = {
    (remoteAddress, localAddress)
  }

}
