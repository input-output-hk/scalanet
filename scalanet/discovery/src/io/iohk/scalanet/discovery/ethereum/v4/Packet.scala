package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.crypto.{SigAlg, PrivateKey, PublicKey, Signature}
import io.iohk.scalanet.discovery.hash.{Hash, Keccak}
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import scodec.bits.BitVector
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

  trait HasExpiration[T <: Payload] {
    // Absolute UNIX timestamp.
    def expiration: Long
    def withExpiration(e: Long): T
  }

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
      with HasExpiration[Ping] {
    def withExpiration(e: Long) = copy(expiration = e)
  }

  case class Pong(
      // Copy of `to` from the corresponding ping packet.
      to: Node.Address,
      // Hash of the corresponding ping packet.
      pingHash: BitVector,
      expiration: Long,
      // Current ENR of the sender of Pong.
      enrSeq: Option[Long]
  ) extends Response
      with HasExpiration[Pong] {
    def withExpiration(e: Long) = copy(expiration = e)
  }

  case class FindNode(
      // 65-byte secp256k1 public key
      target: PublicKey,
      expiration: Long
  ) extends Request
      with HasExpiration[FindNode] {
    def withExpiration(e: Long) = copy(expiration = e)
  }

  case class Neighbors(
      nodes: Seq[Node],
      expiration: Long
  ) extends Response
      with HasExpiration[Neighbors] {
    def withExpiration(e: Long) = copy(expiration = e)
  }

  case class ENRRequest(
      expiration: Long
  ) extends Request
      with HasExpiration[ENRRequest] {
    def withExpiration(e: Long) = copy(expiration = e)
  }

  case class ENRResponse(
      requestHash: Hash,
      enr: EthereumNodeRecord
  ) extends Response
}

/** Data as it goes over the wire. The packet type is included in the data. */
case class Packet(
    hash: Hash,
    signature: Signature,
    data: BitVector
)

object Packet {
  val MacBitsSize = 256 // 32 bytes; Keccak
  val SigBitsSize = 520 // 65 bytes, Secp256k1
  val MaxPacketSize = 1280

  private def consumeNBits(size: Int) =
    Decoder[BitVector] { (bits: BitVector) =>
      bits.consumeThen(size)(
        err => Attempt.failure(Err.InsufficientBits(size, bits.size, List(err))),
        (range, remainder) => Attempt.successful(DecodeResult(range, remainder))
      )
    }

  private val consumeRemainingBits =
    Decoder[BitVector] { (bits: BitVector) =>
      Attempt.successful(DecodeResult(bits, BitVector.empty))
    }

  private val packetDecoder: Decoder[Packet] =
    for {
      _ <- Decoder { bits =>
        Attempt
          .guard(
            bits.size <= MaxPacketSize,
            "Packet to decode exceeds maximum packet size."
          )
          .map(_ => DecodeResult((), bits))
      }
      hash <- consumeNBits(MacBitsSize).map(Hash(_))
      signature <- consumeNBits(SigBitsSize).map(Signature(_))
      data <- consumeRemainingBits
    } yield Packet(hash, signature, data)

  private val packetEncoder: Encoder[Packet] =
    Encoder[Packet] { (packet: Packet) =>
      for {
        _ <- Attempt.guard(packet.hash.size == MacBitsSize, "Unexpected MAC size.")
        _ <- Attempt.guard(packet.signature.size == SigBitsSize, "Unexpected signature size.")
        bits <- Attempt.successful {
          packet.hash ++ packet.signature ++ packet.data
        }
        _ <- Attempt.guard(bits.size <= MaxPacketSize, "Encoded packet exceeded maximum packet size.")
      } yield bits
    }

  implicit val packetCodec: Codec[Packet] =
    Codec[Packet](packetEncoder, packetDecoder)

  /** Serialize the payload, sign the data and compute the hash. */
  def pack(
      payload: Payload,
      privateKey: PrivateKey
  )(implicit codec: Codec[Payload], sigalg: SigAlg): Attempt[Packet] =
    for {
      data <- codec.encode(payload)
      signature = sigalg.sign(privateKey, data)
      hash = Keccak(signature ++ data)
    } yield Packet(hash, signature, data)

  /** Validate the hash, recover the public key by validating the signature, and deserialize the payload. */
  def unpack(packet: Packet)(implicit codec: Codec[Payload], sigalg: SigAlg): Attempt[(Payload, PublicKey)] =
    for {
      hash <- Attempt.successful(Keccak(packet.signature ++ packet.data))
      _ <- Attempt.guard(hash == packet.hash, "Invalid message hash.")
      publicKey <- sigalg.recoverPublicKey(packet.signature, packet.data)
      payload <- codec.decodeValue(packet.data)
    } yield (payload, publicKey)
}
