package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import io.iohk.decco
import io.iohk.decco.Codec.Failure
import io.iohk.decco.auto.instances.NativeInstances
import io.iohk.decco.{Codec, CodecContract}
import io.iohk.scalanet.crypto.ECDSASignature

object ECDSASignatureCodecContract extends CodecContract[ECDSASignature] {
  override def size(t: ECDSASignature): Int = {
    val sizeR = NativeInstances.ByteArrayCodec.size(t.r.toByteArray)
    val sizeS = NativeInstances.ByteArrayCodec.size(t.s.toByteArray)
    sizeR + sizeS
  }

  override def encodeImpl(t: ECDSASignature, start: Int, destination: ByteBuffer): Unit = {
    val sizeR = NativeInstances.ByteArrayCodec.size(t.r.toByteArray)

    NativeInstances.ByteArrayCodec.encodeImpl(t.r.toByteArray, start, destination)
    NativeInstances.ByteArrayCodec.encodeImpl(t.s.toByteArray, start + sizeR, destination)
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, Codec.DecodeResult[ECDSASignature]] = {
    NativeInstances.ByteArrayCodec.decodeImpl(start, source) match {
      case Left(f) => Left(f)
      case Right(r) =>
        NativeInstances.ByteArrayCodec.decodeImpl(r.nextIndex, source) match {
          case Left(f) => Left(f)
          case Right(s) =>
            Right(
              new decco.Codec.DecodeResult[ECDSASignature](
                ECDSASignature(BigInt(r.decoded), BigInt(s.decoded)),
                s.nextIndex))
        }
    }
  }
}
