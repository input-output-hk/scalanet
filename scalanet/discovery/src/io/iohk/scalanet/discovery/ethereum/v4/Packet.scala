package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.ethereum.{Node, Crypto, Hash}
import io.iohk.scalanet.discovery.ethereum.EthereumNodeRecord
import scodec.bits.BitVectork
import scodec.{Codec, Attempt, Decoder, Err, Encoder}
import scodec.DecodeResult

/** Discovery protocol messages from https://github.com/ethereum/devp2p/blob/master/discv4.md
  *
  * Note that these case classes dont' contain the packet-type, e.g. 0x01 for Ping,
  * because in our case that has to be handled by the Codec, so if it's RLP then
  * it has to correctly prepend the discriminant byte so that it can later deserialize
  * the data as well. Incidentally this works fine with the signing.
  */
sealed trait Payload

object Payload {
  sealed trait Request extends Payload
  sealed trait Response extends Payload

  case class Ping(
      // Must be 4.
      version: Int,
      from: Node.Address,
      to: Node.Address,
      // Absolute UNIX timestamp.
      expiration: Long,
      // Current ENR sequence number of the sender.
      enrSeq: Option[Long]
  ) extends Request

  case class Pong(
      // Copy of `to` from the corresponding ping packet.
      to: Node.Address,
      // Hash of the corresponding ping packet.
      pingHash: BitVector,
      expiration: Long,
      // Current ENR of the sender of Pong.
      enrSeq: Option[Long]
  ) extends Response

  case class FindNode(
      // 65-byte secp256k1 public key
      target: Crypto.PublicKey,
      expiration: Long
  ) extends Request

  case class Neighbors(
      nodes: Seq[Node],
      expiration: Long
  ) extends Response

  case class ENRRequest(
      expiration: Long
  ) extends Request

  case class ENRResponse(
      requestHash: BitVector,
      enr: EthereumNodeRecord
  ) extends Response
}

/** Data as it goes over the wire. The packet type is included in the data. */
case class Packet(
    hash: BitVector,
    signature: BitVector,
    data: BitVector
)

object Packet {
  val MacBitsSize = 256 // 32 bytes
  val SigBitsSize = 520 // 65 bytes

  /** Consume a given number of bits. */
  private def decodeN(size: Int) =
    Decoder[BitVector] { (bits: BitVector) =>
      bits.consumeThen(size)(
        err => Attempt.failure(Err.InsufficientBits(size, bits.size, List(err))),
        (range, remainder) => Attempt.successful(DecodeResult(range, remainder))
      )
    }

  /** Consume the remaining bits. */
  private val decodeRest =
    Decoder[BitVector] { (bits: BitVector) =>
      Attempt.successful(DecodeResult(bits, BitVector.empty))
    }

  private val packetDecoder: Decoder[Packet] =
    for {
      hash <- decodeN(MacBitsSize)
      signature <- decodeN(SigBitsSize)
      data <- decodeRest
    } yield Packet(hash, signature, data)

  private val packetEncoder: Encoder[Packet] =
    Encoder[Packet] { (packet: Packet) =>
      Attempt.successful {
        packet.hash ++ packet.signature ++ packet.data
      }
    }

  implicit val packetCodec: Codec[Packet] =
    Codec[Packet](packetEncoder, packetDecoder)

  /** Serialize the payload, sign the data and compute the hash. */
  def pack(
      payload: Payload,
      privateKey: Crypto.PrivateKey
  )(implicit codec: Codec[Payload], crypto: Crypto): Attempt[Packet] =
    codec.encode(payload).map { data =>
      val signature = crypto.sign(privateKey, data)
      val hash = Hash.keccak(signature ++ data)
      Packet(hash, signature, data)
    }

  /** Validate the hash, recover the public key by validating the signature, and deserialize the payload. */
  def unpack(packet: Packet)(implicit codec: Codec[Payload], crypto: Crypto): Attempt[(Payload, Crypto.PublicKey)] =
    for {
      hash <- Attempt.successful(Hash.keccak(packet.signature ++ packet.data))
      _ <- Attempt.guard(hash == packet.hash, "Invalid message hash.")
      publicKey <- crypto.recoverPublicKey(packet.signature, packet.data)
      payload <- codec.decodeValue(packet.data)
    } yield (payload, publicKey)
}
