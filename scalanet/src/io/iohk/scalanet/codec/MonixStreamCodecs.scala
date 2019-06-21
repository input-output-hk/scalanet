package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import io.iohk.decco.BufferInstantiator
import monix.reactive.Observable

object MonixStreamCodecs {
  implicit class ObservableStreamCodecs(val o: Observable[ByteBuffer]) {
    def framed(implicit bi: BufferInstantiator[ByteBuffer]): Observable[ByteBuffer] = {
      val framingCodec = new FramingCodec()
      o.flatMap(byteBuffer => Observable.fromIterable(framingCodec.streamDecode(byteBuffer)))
    }
  }
}
