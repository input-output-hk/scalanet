package io.iohk.scalanet.codec

import scodec.Codec
import scodec.bits.BitVector

/**
  * A class to represent the need to
  * 1. Encode a message just like a normal scodec codec
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
  */
trait StreamCodec[T] extends Codec[T] {
  def streamDecode(source: BitVector): Seq[T]

  def cleanSlate: StreamCodec[T]
}
