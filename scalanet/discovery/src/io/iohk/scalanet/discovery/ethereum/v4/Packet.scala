package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.hash.{Hash, Keccak256}
import io.iohk.scalanet.discovery.crypto.{SigAlg, PrivateKey, PublicKey, Signature}
import scodec.bits.BitVector
import scodec.{Codec, Attempt, Decoder, Err, Encoder}
import scodec.DecodeResult

/** Wire format from https://github.com/ethereum/devp2p/blob/master/discv4.md
  *
  * The packet type is included in the data.
  * */
case class Packet(
    hash: Hash,
    signature: Signature,
    data: BitVector
)

object Packet {
  val MacBitsSize = 32 * 8 // Keccak256
  val SigBitsSize = 65 * 8 // Secp256k1
  val MaxPacketBitsSize = 1280 * 8

  private def consumeNBits(context: String, size: Int) =
    Decoder[BitVector] { (bits: BitVector) =>
      bits.consumeThen(size)(
        err => Attempt.failure(Err.InsufficientBits(size, bits.size, List(context))),
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
            bits.size <= MaxPacketBitsSize,
            "Packet to decode exceeds maximum size."
          )
          .map(_ => DecodeResult((), bits))
      }
      hash <- consumeNBits("Hash", MacBitsSize).map(Hash(_))
      signature <- consumeNBits("Signature", SigBitsSize).map(Signature(_))
      data <- consumeRemainingBits
    } yield Packet(hash, signature, data)

  private val packetEncoder: Encoder[Packet] =
    Encoder[Packet] { (packet: Packet) =>
      for {
        _ <- Attempt.guard(packet.hash.size == MacBitsSize, "Unexpected hash size.")
        _ <- Attempt.guard(packet.signature.size == SigBitsSize, "Unexpected signature size.")
        bits <- Attempt.successful {
          packet.hash ++ packet.signature ++ packet.data
        }
        _ <- Attempt.guard(bits.size <= MaxPacketBitsSize, "Encoded packet exceeded maximum size.")
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
      hash = Keccak256(signature ++ data)
    } yield Packet(hash, signature, data)

  /** Validate the hash, recover the public key by validating the signature, and deserialize the payload. */
  def unpack(packet: Packet)(implicit codec: Codec[Payload], sigalg: SigAlg): Attempt[(Payload, PublicKey)] =
    for {
      hash <- Attempt.successful(Keccak256(packet.signature ++ packet.data))
      _ <- Attempt.guard(hash == packet.hash, "Invalid hash.")
      publicKey <- sigalg.recoverPublicKey(packet.signature, packet.data)
      payload <- codec.decodeValue(packet.data)
    } yield (payload, publicKey)
}
