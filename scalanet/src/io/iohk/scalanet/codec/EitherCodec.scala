package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import scodec.Attempt.{Failure, Successful}
import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}

class EitherCodec[A, B](implicit a: Codec[A], b: Codec[B], byteCodec: Codec[Byte]) extends Codec[Either[A, B]] {
  override def decode(bits: BitVector): Attempt[DecodeResult[Either[A, B]]] = {
    byteCodec.decode(bits) match {
      case Failure(f) => Failure(f)
      case Successful(byte) => {
        if (byte.value == 0.toByte) {
          a.decode(byte.remainder) match {
            case Failure(f) => Failure(f)
            case Successful(v) => Successful(new DecodeResult[Either[A, B]](Left(v.value), v.remainder))
          }
        } else if (byte.value == 1.toByte) {
          b.decode(byte.remainder) match {
            case Failure(f) => Failure(f)
            case Successful(v) => Successful(new DecodeResult[Either[A, B]](Right(v.value), v.remainder))
          }
        } else Failure(Err("Encoded Either invalid"))
      }
    }
  }

  override def encode(value: Either[A, B]): Attempt[BitVector] = value match {
    case Left(v) => {
      a.encode(v) match {
        case Failure(f) => Failure(f)
        case Successful(value) => Successful(byteCodec.encode(0).toOption.get ++ value)
      }
    }
    case Right(v) => {
      b.encode(v) match {
        case Failure(f) => Failure(f)
        case Successful(value) => Successful(byteCodec.encode(0).toOption.get ++ value)
      }
    }
  }

  override def sizeBound: SizeBound =
    SizeBound(
      a.sizeBound.lowerBound.min(b.sizeBound.lowerBound) + 1, {
        if (a.sizeBound.upperBound.isEmpty || b.sizeBound.upperBound.isEmpty) None
        else Some(a.sizeBound.upperBound.get.max(b.sizeBound.upperBound.get) + 1)
      }
    )
}
