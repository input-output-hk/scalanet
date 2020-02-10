package io.iohk.scalanet.codec

import io.iohk.scalanet.crypto.ECDSASignature
import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult, SizeBound}
import io.iohk.scalanet.codec.DefaultCodecs.General._

object ECDSASignatureCodec extends Codec[ECDSASignature] {
  override def decode(bits: BitVector): Attempt[DecodeResult[ECDSASignature]] = {
    for {
      r <- bigIntegerCodec.decode(bits)
      s <- bigIntegerCodec.decode(r.remainder)
    } yield new DecodeResult[ECDSASignature](ECDSASignature(r.value, s.value), s.remainder)
  }

  override def encode(value: ECDSASignature): Attempt[BitVector] = {
    for {
      r <- bigIntegerCodec.encode(value.r.bigInteger)
      s <- bigIntegerCodec.encode(value.s.bigInteger)
    } yield r ++ s
  }

  override def sizeBound: SizeBound =
    SizeBound(
      bigIntegerCodec.sizeBound.lowerBound * 2,
      for {
        v <- bigIntegerCodec.sizeBound.upperBound
      } yield 2 * v
    )
}
