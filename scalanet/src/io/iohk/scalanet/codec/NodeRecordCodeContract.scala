package io.iohk.scalanet.codec

import java.math.BigInteger
import java.nio.ByteBuffer

import io.iohk.decco.Codec.{DecodeResult, Failure}
import io.iohk.decco.CodecContract
import io.iohk.decco.auto.instances.NativeInstances
import io.iohk.scalanet.peergroup.kademlia.KRouter
import scodec.bits.BitVector

class NodeRecordCodeContract[A](codecContract: CodecContract[A]) extends CodecContract[KRouter.NodeRecord[A]] {
  override def size(t: KRouter.NodeRecord[A]): Int = {
    val idSize = NativeInstances.ByteArrayCodec.size(t.id.toByteArray)
    val messagingAddressSize = NativeInstances.ByteArrayCodec.size(t.id.toByteArray)
    val routingAddressSize = codecContract.size(t.routingAddress)
    val seqSize = 8
    val signSize = ECDSASignatureCodecContract.size(t.sign)
    idSize + messagingAddressSize + routingAddressSize + seqSize + signSize
  }

  override def encodeImpl(t: KRouter.NodeRecord[A], start: Int, destination: ByteBuffer): Unit = {
    val idArray = t.id.toByteArray
    NativeInstances.ByteArrayCodec.encodeImpl(idArray, start, destination)
    codecContract.encodeImpl(t.messagingAddress, start + NativeInstances.ByteArrayCodec.size(idArray), destination)
    codecContract.encodeImpl(
      t.routingAddress,
      start + NativeInstances.ByteArrayCodec.size(idArray) + codecContract.size(t.messagingAddress),
      destination)
    NativeInstances.LongCodec.encodeImpl(
      t.seq,
      start + NativeInstances.ByteArrayCodec.size(idArray) + codecContract.size(t.messagingAddress) + codecContract
        .size(t.routingAddress),
      destination
    )
    ECDSASignatureCodecContract.encodeImpl(
      t.sign,
      start + NativeInstances.ByteArrayCodec.size(idArray) + codecContract.size(t.messagingAddress) + codecContract
        .size(t.routingAddress) + 8,
      destination
    )
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[KRouter.NodeRecord[A]]] = {
    NativeInstances.ByteArrayCodec.decodeImpl(start, source) match {
      case Left(f) => Left(f)
      case Right(idArray) => {
        codecContract.decodeImpl(idArray.nextIndex, source) match {
          case Left(f) => Left(f)
          case Right(messagingAddress) => {
            codecContract.decodeImpl(messagingAddress.nextIndex, source) match {
              case Left(f) => Left(f)
              case Right(routingAddress) =>
                NativeInstances.LongCodec.decodeImpl(routingAddress.nextIndex, source) match {
                  case Left(f) => Left(f)
                  case Right(seq) =>
                    ECDSASignatureCodecContract.decodeImpl(seq.nextIndex, source) match {
                      case Left(f) => Left(f)
                      case Right(sign) =>
                        Right(
                          new DecodeResult[KRouter.NodeRecord[A]](
                            KRouter.NodeRecord(
                              BitVector(idArray.decoded),
                              routingAddress.decoded,
                              messagingAddress.decoded,
                              seq.decoded,
                              sign.decoded),
                            sign.nextIndex))
                    }
                }
            }
          }
        }
      }
    }
  }
}
