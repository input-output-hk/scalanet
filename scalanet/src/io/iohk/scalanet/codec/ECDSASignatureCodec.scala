package io.iohk.scalanet.codec

import java.math.BigInteger

import io.iohk.scalanet.crypto.ECDSASignature
import scodec.Attempt.{Failure, Successful}
import scodec.bits.{BitVector, ByteVector}
import scodec.{Attempt, Codec, DecodeResult, SizeBound}
import scodec.codecs.implicits.implicitByteVectorCodec

object ECDSASignatureCodec extends Codec[ECDSASignature] {
  override def decode(bits: BitVector): Attempt[DecodeResult[ECDSASignature]] = {
    implicitByteVectorCodec.decode(bits) match{
      case Failure(f) => Failure(f)
      case Successful(r) => implicitByteVectorCodec.decode(r.remainder) match{
        case Failure(f) => Failure(f)
        case Successful(s) => Successful(new DecodeResult[ECDSASignature](ECDSASignature(new BigInteger(r.value.toArray),new BigInteger(s.value.toArray)),s.remainder))
      }
    }
  }

  override def encode(value: ECDSASignature): Attempt[BitVector] = {
    implicitByteVectorCodec.encode(ByteVector(value.r.toByteArray)) match{
      case Failure(f) => Failure(f)
      case Successful(r) => implicitByteVectorCodec.encode(ByteVector(value.s.toByteArray)) match{
        case Failure(f) => Failure(f)
        case Successful(s) => Successful(r++s)
      }
    }
  }

  override def sizeBound: SizeBound = SizeBound(implicitByteVectorCodec.sizeBound.lowerBound*2,{
    if(implicitByteVectorCodec.sizeBound.upperBound.isEmpty) None
    else Some(implicitByteVectorCodec.sizeBound.upperBound.get*2)
  })
}
