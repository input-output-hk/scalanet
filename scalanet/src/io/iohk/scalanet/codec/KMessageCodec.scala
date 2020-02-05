package io.iohk.scalanet.codec

import java.nio.ByteBuffer
import java.util.UUID

import io.iohk.scalanet.peergroup.kademlia.{KMessage, KRouter}
import scodec.Attempt.{Failure, Successful}
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}
import scodec.bits.BitVector
import scodec.codecs.implicits._

class KMessageCodec[A](codec: Codec[KRouter.NodeRecord[A]])(implicit longCodec:Codec[Long],uuidCodec:Codec[UUID],byteCodec:Codec[Byte],bitVectorCodec:Codec[BitVector]) extends Codec[KMessage[A]]{
  val nodesSeq =  new SeqCodec[KRouter.NodeRecord[A]]()(codec,implicitIntCodec)

  override def decode(bits: BitVector): Attempt[DecodeResult[KMessage[A]]] = {
    val firstByte = byteCodec.decode(bits)
    if(firstByte.isFailure) Failure(firstByte.toEither.left.get)
    else {
      if (firstByte.toOption.get.value == 0.toByte) {
        uuidCodec.decode(firstByte.toOption.get.remainder) match{
          case Failure(f) => Failure(f)
          case Successful(uuid) => codec.decode(uuid.remainder) match{
            case Failure(f) => Failure(f)
            case Successful(nodeRecord) => Successful(new DecodeResult[KMessage[A]](KMessage.KRequest.Ping(uuid.value,nodeRecord.value),nodeRecord.remainder))
          }
        }
      }
      else if (firstByte.toOption.get.value == 1.toByte) {
        uuidCodec.decode(firstByte.toOption.get.remainder) match{
          case Failure(f) => Failure(f)
          case Successful(uuid) => codec.decode(uuid.remainder) match{
            case Failure(f) => Failure(f)
            case Successful(nodeRecord) =>bitVectorCodec.decode(nodeRecord.remainder) match{
              case Failure(f) => Failure(f)
              case Successful(targetId) => Successful(new DecodeResult[KMessage[A]](KMessage.KRequest.FindNodes(uuid.value,nodeRecord.value,targetId.value),targetId.remainder))
            }
          }
        }
      }
      else if (firstByte.toOption.get.value == 2.toByte) {
        uuidCodec.decode(firstByte.toOption.get.remainder) match{
          case Failure(f) => Failure(f)
          case Successful(uuid) => codec.decode(uuid.remainder) match{
            case Failure(f) => Failure(f)
            case Successful(nodeRecord) => Successful(new DecodeResult[KMessage[A]](KMessage.KResponse.Pong(uuid.value,nodeRecord.value),nodeRecord.remainder))
          }
        }
      }
      else if (firstByte.toOption.get.value == 3.toByte) {
        uuidCodec.decode(firstByte.toOption.get.remainder) match{
          case Failure(f) => Failure(f)
          case Successful(uuid) => codec.decode(uuid.remainder) match{
            case Failure(f) => Failure(f)
            case Successful(nodeRecord) => nodesSeq.decode(nodeRecord.remainder) match{
              case Failure(f) => Failure(f)
              case Successful(nodes) => Successful(new DecodeResult[KMessage[A]](KMessage.KResponse.Nodes(uuid.value,nodeRecord.value,nodes.value),nodes.remainder))
            }
          }
        }
      }
      else Failure(Err("KMessage encoded invalid"))
    }
  }

  override def encode(value: KMessage[A]): Attempt[BitVector] = value match {
    case KMessage.KRequest.Ping(uuid,nodeRecord) =>{
      val encodedUUID = uuidCodec.encode(uuid)
      val encodedNodeRecord = codec.encode(nodeRecord)
      if(encodedUUID.isFailure) Failure(encodedUUID.toEither.left.get)
      else if(encodedNodeRecord.isFailure) Failure(encodedNodeRecord.toEither.left.get)
      else Successful(byteCodec.encode(0).toOption.get ++ encodedUUID.toOption.get ++ encodedNodeRecord.toOption.get)
    }
    case KMessage.KRequest.FindNodes(uuid,nodeRecord,targetNodeId) => {
      val encodedUUID = uuidCodec.encode(uuid)
      val encodedNodeRecord = codec.encode(nodeRecord)
      val encodedTargetId = bitVectorCodec.encode(targetNodeId)
      if(encodedUUID.isFailure) Failure(encodedUUID.toEither.left.get)
      else if(encodedNodeRecord.isFailure) Failure(encodedNodeRecord.toEither.left.get)
      else if(encodedNodeRecord.isFailure) Failure(encodedTargetId.toEither.left.get)
      else Successful(byteCodec.encode(1).toOption.get ++ encodedUUID.toOption.get ++ encodedNodeRecord.toOption.get ++ encodedTargetId.toOption.get)
    }
    case KMessage.KResponse.Pong(uuid,nodeRecord) => {
      val encodedUUID = uuidCodec.encode(uuid)
      val encodedNodeRecord = codec.encode(nodeRecord)
      if(encodedUUID.isFailure) Failure(encodedUUID.toEither.left.get)
      else if(encodedNodeRecord.isFailure) Failure(encodedNodeRecord.toEither.left.get)
      else Successful(byteCodec.encode(2).toOption.get ++ encodedUUID.toOption.get ++ encodedNodeRecord.toOption.get)
    }
    case KMessage.KResponse.Nodes(uuid,nodeRecord,nodes) => {
      val encodedUUID = uuidCodec.encode(uuid)
      val encodedNodeRecord = codec.encode(nodeRecord)
      val encodedNodes = nodesSeq.encode(nodes)
      if(encodedUUID.isFailure) Failure(encodedUUID.toEither.left.get)
      else if(encodedNodeRecord.isFailure) Failure(encodedNodeRecord.toEither.left.get)
      else if(encodedNodes.isFailure) Failure(encodedNodes.toEither.left.get)
      else Successful(byteCodec.encode(3).toOption.get ++ encodedUUID.toOption.get ++ encodedNodeRecord.toOption.get ++ encodedNodes.toOption.get)
    }
  }

  override def sizeBound: SizeBound = SizeBound(codec.sizeBound.lowerBound+16,if(nodesSeq.sizeBound.upperBound.isEmpty) None else Some(16 + nodesSeq.sizeBound.upperBound.get) )
}