package io.iohk.scalanet.discovery.ethereum

import io.iohk.scalanet.discovery.crypto.PublicKey
import java.net.InetAddress

case class Node(id: PublicKey, address: Node.Address)

object Node {
  case class Address(
      ip: InetAddress,
      udpPort: Int,
      tcpPort: Int
  )
}
