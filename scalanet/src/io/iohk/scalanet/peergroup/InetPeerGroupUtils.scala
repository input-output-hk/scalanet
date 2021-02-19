package io.iohk.scalanet.peergroup

import java.net.{InetSocketAddress, ServerSocket}
import io.netty
import io.netty.util.concurrent.{Future, GenericFutureListener}
import monix.eval.Task

import java.util.concurrent.CancellationException

object InetPeerGroupUtils {

  type ChannelId = (InetSocketAddress, InetSocketAddress)

  // TODO: This has nothing to do with InetPeerGroup, move it somewhere else.
  def toTask(f: => netty.util.concurrent.Future[_]): Task[Unit] = {
    fromNettyFuture(Task.delay(f)).void
  }

  def fromNettyFuture[A](ff: Task[netty.util.concurrent.Future[A]]): Task[A] = {
    ff.flatMap { nettyFuture =>
      Task.cancelable { cb =>
        subscribeToFuture(nettyFuture, cb)
        Task.delay({ nettyFuture.cancel(true); () })
      }
    }
  }

  private def subscribeToFuture[A](cf: netty.util.concurrent.Future[A], cb: Either[Throwable, A] => Unit): Unit = {
    cf.addListener(new GenericFutureListener[Future[A]] {
      override def operationComplete(future: Future[A]): Unit = {
        if (future.isSuccess) {
          cb(Right(future.getNow))
        } else {
          future.cause() match {
            case _: CancellationException =>
              ()
            case ex => cb(Left(ex))
          }
        }
      }
    })
    ()
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
