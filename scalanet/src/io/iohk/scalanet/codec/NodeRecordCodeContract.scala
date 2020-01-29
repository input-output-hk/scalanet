package io.iohk.scalanet.codec

import java.math.BigInteger
import java.nio.ByteBuffer

import io.iohk.decco.Codec.{DecodeResult, Failure}
import io.iohk.decco.CodecContract
import io.iohk.decco.auto.instances.NativeInstances
import io.iohk.scalanet.peergroup.kademlia.KRouter
import scodec.bits.BitVector

class NodeRecordCodeContract[A](codecContract: CodecContract[A]) extends CodecContract[KRouter.NodeRecord[A]]{
  override def size(t: KRouter.NodeRecord[A]): Int = NativeInstances.ByteArrayCodec.size(t.id.toByteArray) + codecContract.size(t.messagingAddress) + codecContract.size(t.routingAddress)+8+NativeInstances.ByteArrayCodec.size(t.sign._1.toByteArray) + NativeInstances.ByteArrayCodec.size(t.sign._2.toByteArray)

  override def encodeImpl(t: KRouter.NodeRecord[A], start: Int, destination: ByteBuffer): Unit = {
    val idArray = t.id.toByteArray
    NativeInstances.ByteArrayCodec.encodeImpl(idArray,start,destination)
    codecContract.encodeImpl(t.messagingAddress,start + NativeInstances.ByteArrayCodec.size(idArray),destination)
    codecContract.encodeImpl(t.routingAddress,start + NativeInstances.ByteArrayCodec.size(idArray) + codecContract.size(t.messagingAddress),destination)
    NativeInstances.LongCodec.encodeImpl(t.sec_number,start + NativeInstances.ByteArrayCodec.size(idArray) + codecContract.size(t.messagingAddress) + codecContract.size(t.routingAddress),destination)
    NativeInstances.ByteArrayCodec.encodeImpl(t.sign._1.toByteArray,start + NativeInstances.ByteArrayCodec.size(idArray) + codecContract.size(t.messagingAddress) + codecContract.size(t.routingAddress)+8,destination)
    NativeInstances.ByteArrayCodec.encodeImpl(t.sign._2.toByteArray,start + NativeInstances.ByteArrayCodec.size(idArray) + codecContract.size(t.messagingAddress) + codecContract.size(t.routingAddress)+8+NativeInstances.ByteArrayCodec.size(t.sign._1.toByteArray),destination)
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[KRouter.NodeRecord[A]]] = {
    NativeInstances.ByteArrayCodec.decodeImpl(start,source) match{
      case Left(f) => Left(f)
      case Right(idArray) =>{
        codecContract.decodeImpl(idArray.nextIndex,source) match{
          case Left(f) => Left(f)
          case Right(messagingAddress) => {
            codecContract.decodeImpl(messagingAddress.nextIndex,source) match{
              case Left(f) => Left(f)
              case Right(routingAddress) => NativeInstances.LongCodec.decodeImpl(routingAddress.nextIndex,source) match{
                case Left(f) => Left(f)
                case Right(sec_number) => NativeInstances.ByteArrayCodec.decodeImpl(sec_number.nextIndex,source) match{
                  case Left(f) => Left(f)
                  case Right(signed_1) => NativeInstances.ByteArrayCodec.decodeImpl(signed_1.nextIndex,source) match{
                    case Left(f) => Left(f)
                    case Right(signed_2) => Right (new DecodeResult[KRouter.NodeRecord[A]](KRouter.NodeRecord[A](BitVector(idArray.decoded),routingAddress.decoded,messagingAddress.decoded,sec_number.decoded,(new BigInteger(signed_1.decoded),new BigInteger(signed_2.decoded) )),signed_2.nextIndex))
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}