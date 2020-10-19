package io.iohk.scalanet.discovery.ethereum

import io.iohk.scalanet.discovery.crypto.PublicKey
import io.iohk.scalanet.discovery.hash.{Hash, Keccak256}
import java.net.InetAddress
import scodec.bits.ByteVector
import scala.util.Try

case class Node(id: Node.Id, address: Node.Address) {
  protected[discovery] lazy val kademliaId: Hash = Node.kademliaId(id)
}

object Node {

  /** 64 bit uncompressed Secp256k1 public key. */
  type Id = PublicKey

  /** The ID of the node is the 64 bit public key, but for the XOR distance we use its hash. */
  protected[discovery] def kademliaId(id: PublicKey): Hash =
    Keccak256(id)

  case class Address(
      ip: InetAddress,
      udpPort: Int,
      tcpPort: Int
  )
  object Address {
    def fromEnr(enr: EthereumNodeRecord): Option[Node.Address] = {
      import EthereumNodeRecord.Keys

      def tryParse[T](key: ByteVector)(f: ByteVector => T): Option[T] =
        enr.content.attrs.get(key).flatMap { value =>
          Try(f(value)).toOption
        }

      def tryParseIP(key: ByteVector): Option[InetAddress] =
        tryParse[InetAddress](key)(bytes => InetAddress.getByAddress(bytes.toArray))

      def tryParsePort(key: ByteVector): Option[Int] =
        tryParse[Int](key)(bytes => bytes.toInt())

      for {
        ip <- tryParseIP(Keys.ip6) orElse tryParseIP(Keys.ip)
        udp <- tryParsePort(Keys.udp6) orElse tryParsePort(Keys.udp)
        tcp <- tryParsePort(Keys.tcp6) orElse tryParsePort(Keys.tcp)
      } yield Node.Address(ip, udpPort = udp, tcpPort = tcp)
    }
  }
}
