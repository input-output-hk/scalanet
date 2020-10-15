package io.iohk.scalanet.discovery.ethereum

import io.iohk.scalanet.discovery.crypto.PublicKey
import java.net.InetAddress
import scodec.bits.ByteVector
import scala.util.Try

case class Node(id: PublicKey, address: Node.Address)

object Node {
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
        tryParse[Int](key)(_.toInt())

      for {
        ip <- tryParseIP(Keys.ip6) orElse tryParseIP(Keys.ip)
        udp <- tryParsePort(Keys.udp6) orElse tryParsePort(Keys.udp)
        tcp <- tryParsePort(Keys.tcp6) orElse tryParsePort(Keys.tcp)
      } yield Node.Address(ip, udpPort = udp, tcpPort = tcp)
    }
  }
}
