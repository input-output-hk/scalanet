package io.iohk.scalanet.codec

import io.iohk.decco.{BufferInstantiator, Codec}

trait StreamCodec[T] extends Codec[T] {
  def streamDecode[B](source: B)(implicit bi: BufferInstantiator[B]): Seq[T]
}
