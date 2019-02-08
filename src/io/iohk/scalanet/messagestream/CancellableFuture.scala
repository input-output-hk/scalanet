package io.iohk.scalanet.messagestream

import monix.execution.{CancelableFuture => MonixFuture}

import scala.concurrent.{CanAwait, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.Try

trait CancellableFuture[+T] extends Future[T] with Cancellable

object CancellableFuture {
  def apply[T](mf: MonixFuture[T]): CancellableFuture[T] = new CancellableFuture[T] {
    override def onComplete[U](f: Try[T] => U)(implicit executor: ExecutionContext): Unit =
      mf.onComplete(f)

    override def isCompleted: Boolean =
      mf.isCompleted

    override def value: Option[Try[T]] =
      mf.value

    override def transform[S](f: Try[T] => Try[S])(implicit executor: ExecutionContext): Future[S] =
      mf.transform(f)

    override def transformWith[S](f: Try[T] => Future[S])(implicit executor: ExecutionContext): Future[S] =
      mf.transformWith(f)

    override def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
      mf.ready(atMost)
      this
    }

    override def result(atMost: Duration)(implicit permit: CanAwait): T =
      mf.result(atMost)

    override def cancel(): Unit =
      mf.cancel()
  }
}
