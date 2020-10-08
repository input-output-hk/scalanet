package io.iohk.scalanet.discovery.ethereum

import java.net.InetAddress
import scodec.bits.BitVector

case class Node(id: BitVector, address: Node.Address)

object Node {
  case class Address(
      ip: InetAddress,
      udpPort: Int,
      tcpPort: Int
  )
}
