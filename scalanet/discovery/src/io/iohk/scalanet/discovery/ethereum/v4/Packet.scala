package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.ethereum.Node
import io.iohk.scalanet.discovery.ethereum.EthereumNodeRecord
import scodec.bits.{ByteVector}
import scodec.{Codec, Attempt}

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
      pingHash: ByteVector,
      expiration: Long,
      // Current ENR of the sender of Pong.
      enrSeq: Option[Long]
  ) extends Response

  case class FindNode(
      // 65-byte secp256k1 public key
      target: ByteVector,
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
      requestHash: ByteVector,
      enr: EthereumNodeRecord
  ) extends Response
}

/** Data as it goes over the wire. The packet type is included in the data. */
case class Packet(
    hash: ByteVector,
    signature: ByteVector,
    data: ByteVector
) {
  def toBytes: ByteVector =
    hash ++ signature ++ data

  def decodePayload(implicit codec: Codec[Payload]): Attempt[Payload] =
    codec.decodeValue(data.toBitVector)
}

object Packet {
  // TODO: Crypto
  type PrivateKey = ByteVector
  type PublicKey = ByteVector

  /** Serialize the payload, sign the data and compute the hash. */
  def pack(payload: Payload, privateKey: PrivateKey)(implicit codec: Codec[Payload]): Attempt[Packet] = ???

  /** Deserialize data, validate the hash, validate the signature and recover the public key. */
  def unpack(bytes: ByteVector): Attempt[(Packet, PublicKey)] = ???
}
