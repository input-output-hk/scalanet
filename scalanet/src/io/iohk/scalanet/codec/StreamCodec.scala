package io.iohk.scalanet.codec

import io.iohk.decco.{BufferInstantiator, Codec}
import monix.execution.Ack.Continue
import monix.execution.{Ack, Scheduler}
import monix.reactive.Observable
import monix.reactive.Observable.Operator
import monix.reactive.observers.Subscriber

import scala.concurrent.Future

trait StreamCodec[T] extends Codec[T] {
  def streamDecode[B](source: B)(implicit bi: BufferInstantiator[B]): Seq[T]

  def monixOperator[B](implicit bi: BufferInstantiator[B]): Operator[B, T] = { out: Subscriber[T] =>
    new Subscriber[B] {
      override implicit def scheduler: Scheduler = out.scheduler
      override def onNext(elem: B): Future[Ack] = {
        streamDecode(elem).foreach(outElem => out.onNext(outElem))
        Continue
      }
      override def onError(ex: Throwable): Unit = out.onError(ex)
      override def onComplete(): Unit = out.onComplete()
    }
  }
}

object StreamCodec {

  implicit class StreamCodecSyntax[B](val o: Observable[B]) {
    def voodooLift[T](implicit streamCodec: StreamCodec[T], bi: BufferInstantiator[B]): Observable[T] = {
      o.flatMap(byteBuffer => Observable.fromIterable(streamCodec.streamDecode(byteBuffer)))
    }
  }
}