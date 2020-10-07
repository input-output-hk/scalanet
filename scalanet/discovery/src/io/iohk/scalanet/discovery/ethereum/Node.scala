package io.iohk.scalanet.discovery.ethereum

import java.net.InetAddress
import scodec.bits.ByteVector

case class Node(id: ByteVector, address: Node.Address)

object Node {
  case class Address(
      ip: InetAddress,
      udpPort: Int,
      tcpPort: Int
  )
}
