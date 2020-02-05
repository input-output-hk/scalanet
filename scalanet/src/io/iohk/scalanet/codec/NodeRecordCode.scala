package io.iohk.scalanet.codec

import io.iohk.scalanet.peergroup.kademlia.{KMessage, KRouter}
import scodec.Attempt.{Failure, Successful}
import scodec.{Attempt, Codec, DecodeResult, SizeBound}
import scodec.bits.BitVector

class NodeRecordCode[A](implicit codec: Codec[A],longCodec:Codec[Long],bitVectorCodec:Codec[BitVector]) extends Codec[KRouter.NodeRecord[A]] {
  override def decode(bits: BitVector): Attempt[DecodeResult[KRouter.NodeRecord[A]]] = {
    longCodec.decode(bits) match{
      case Failure(f) => Failure(f)
      case Successful(seq) => ECDSASignatureCodec.decode(seq.remainder) match{
        case Failure(f) => Failure(f)
        case Successful(sign) => codec.decode(sign.remainder) match{
          case Failure(f) => Failure(f)
          case Successful(routingAddress) => codec.decode(routingAddress.remainder) match{
            case Failure(f) => Failure(f)
            case Successful(messagingAddress) => bitVectorCodec.decode(messagingAddress.remainder) match{
              case Failure(f) => Failure(f)
              case Successful(id) => Successful(new DecodeResult[KRouter.NodeRecord[A]](KRouter.NodeRecord(id.value,routingAddress.value,messagingAddress.value,seq.value,sign.value),id.remainder))
            }
          }
        }
      }
    }
  }

  override def encode(value: KRouter.NodeRecord[A]): Attempt[BitVector] = {
    longCodec.encode(value.seq) match{
      case Failure(f) => Failure(f)
      case Successful(seq) => ECDSASignatureCodec.encode(value.sign) match{
        case Failure(f) => Failure(f)
        case Successful(sign) => codec.encode(value.routingAddress) match{
          case Failure(f) => Failure(f)
          case Successful(routingAddress) => codec.encode(value.messagingAddress) match{
            case Failure(f) => Failure(f)
            case Successful(messagingAddress) => bitVectorCodec.encode(value.id) match{
              case Failure(f) => Failure(f)
              case Successful(id) => Successful(seq ++ sign ++ routingAddress ++ messagingAddress ++ id)
            }
          }
        }
      }
    }
  }

  override def sizeBound: SizeBound = SizeBound(bitVectorCodec.sizeBound.lowerBound.+(longCodec.sizeBound.lowerBound.+(codec.sizeBound.lowerBound.+(ECDSASignatureCodec.sizeBound.lowerBound))),{
    if(ECDSASignatureCodec.sizeBound.upperBound.isEmpty || bitVectorCodec.sizeBound.upperBound.isEmpty || longCodec.sizeBound.upperBound.isEmpty || codec.sizeBound.upperBound.isEmpty) None
    else Some(bitVectorCodec.sizeBound.lowerBound.+(longCodec.sizeBound.lowerBound.+(codec.sizeBound.lowerBound.+(ECDSASignatureCodec.sizeBound.lowerBound))))
  })
}
