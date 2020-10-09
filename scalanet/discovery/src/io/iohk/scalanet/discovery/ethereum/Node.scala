package io.iohk.scalanet.discovery.ethereum

import scodec.bits.BitVector
import io.iohk.scalanet.discovery.crypto.PublicKey

case class Node(id: PublicKey, address: Node.Address)

object Node {
  case class Address(
      ip: BitVector,
      udpPort: Int,
      tcpPort: Int
  )
}
