package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.hash.Hash
import io.iohk.scalanet.discovery.crypto.PublicKey
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}

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
    def isExpired(now: Long): Boolean =
      expiration < now
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
      pingHash: Hash,
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
      nodes: List[Node],
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
