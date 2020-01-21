import java.nio.ByteBuffer
import java.util.UUID

import io.iohk.decco.Codec.{DecodeResult, Failure}
import io.iohk.decco.CodecContract
import io.iohk.scalanet.{ByteArrayCodecContract, LongerCodecContract}
import io.iohk.scalanet.peergroup.kademlia.KRouterBeta
import scodec.bits.BitVector

class BroadCastMessageCodec[M](codecContract: CodecContract[M]) extends CodecContract[BroadcastNet.Message[M]] {
  override def size(t: BroadcastNet.Message[M]): Int = t match{
    case BroadcastNet.Message(uuid,sender,message) => 16 + ByteArrayCodecContract.size(sender.toByteArray) + codecContract.size(message)
  }

  override def encodeImpl(t: BroadcastNet.Message[M], start: Int, destination: ByteBuffer): Unit = t match{
    case BroadcastNet.Message(uuid,sender,message) => {
      LongerCodecContract.encodeImpl(uuid.getMostSignificantBits,start,destination)
      LongerCodecContract.encodeImpl(uuid.getLeastSignificantBits,start+8,destination)
      ByteArrayCodecContract.encodeImpl(sender.toByteArray,start+16,destination)
      codecContract.encodeImpl(message,start+16+ByteArrayCodecContract.size(sender.toByteArray),destination)
    }
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[BroadcastNet.Message[M]]] = {
    LongerCodecContract.decodeImpl(start,source) match{
      case Left(f) => Left(f)
      case Right(uuid_ms) => {
        LongerCodecContract.decodeImpl(uuid_ms.nextIndex,source) match{
          case Left(f) => Left(f)
          case Right(uuid_ls) => ByteArrayCodecContract.decodeImpl(uuid_ls.nextIndex,source) match {
            case Left(f) => Left(f)
            case Right(sender) => {
              codecContract.decodeImpl(sender.nextIndex,source) match {
                case Left(f) => Left(f)
                case Right(message) => Right (new DecodeResult[BroadcastNet.Message[M]](BroadcastNet.Message(new UUID(uuid_ms.decoded,uuid_ls.decoded),BitVector(sender.decoded),message.decoded),message.nextIndex))
              }
            }
          }
        }
      }
    }
  }
}