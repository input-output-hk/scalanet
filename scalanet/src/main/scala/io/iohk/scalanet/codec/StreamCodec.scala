package io.iohk.scalanet.codec

import io.iohk.decco.{BufferInstantiator, Codec}
import monix.execution.Ack.Continue
import monix.execution.{Ack, Scheduler}
import monix.reactive.Observable
import monix.reactive.Observable.Operator
import monix.reactive.observers.Subscriber

import scala.concurrent.Future

/**
  * A class to represent the need to
  * 1. Encode a message just like a normal decco codec
  * 2. Decode it again, even though the message might be fragmented.
  * This requires some kind of message delimiting strategy such as
  * framing (byte stuffing also looks promising: https://en.wikipedia.org/wiki/Consistent_Overhead_Byte_Stuffing)
  *
  * The key, new operation is streamDecode(buff): Seq[T]. The signature reflects the fact that
  * the incoming buff might contain 0..many messages.
  *
  * It also provides a 'cleanSlate' operation, which is a kind of clone.
  * Because StreamCodecs need to do buffering, they are stateful. This
  * means we need a fresh instance for every new channel created.

  * For discussion, I have also provided a monix Operator and an implicit
  * lifting syntax though it turns out they are not needed in the TCP peer group.
  * Perhaps they will be handy in some other place?
  */
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

  def cleanSlate: StreamCodec[T]
}

object StreamCodec {

  implicit class StreamCodecSyntax[B](val o: Observable[B]) {
    def voodooLift[T](implicit streamCodec: StreamCodec[T], bi: BufferInstantiator[B]): Observable[T] = {
      o.flatMap(byteBuffer => Observable.fromIterable(streamCodec.streamDecode(byteBuffer)))
    }
  }
}
