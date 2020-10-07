package io.iohk.scalanet.discovery.ethereum

import scodec.bits.ByteVector
import scala.collection.SortedMap
import java.nio.charset.StandardCharsets

/** ENR corresponding to https://github.com/ethereum/devp2p/blob/master/enr.md */
case class EthereumNodeRecord(
    // Nodes should increment this number whenever their properties change, like their address, and re-publish.
    seq: Long,
    signature: ByteVector,
    attrs: SortedMap[ByteVector, ByteVector]
)

object EthereumNodeRecord {
  object Keys {
    private def key(k: String): ByteVector =
      ByteVector(k.getBytes(StandardCharsets.UTF_8))

    /** name of identity scheme, e.g. "v4" */
    val id = key("id")

    /** compressed secp256k1 public key, 33 bytes */
    val secp256k1 = key("secp256k1")

    /** IPv4 address, 4 bytes */
    val ip = key("ip")

    /** TCP port, big endian integer */
    val tcp = key("tcp")

    /** UDP port, big endian integer */
    val udp = key("udp")

    /** IPv6 address, 16 bytes */
    val ip6 = key("ip6")

    /** IPv6-specific TCP port, big endian integer */
    val tcp6 = key("tcp6")

    /** IPv6-specific UDP port, big endian integer */
    val udp6 = key("udp6")
  }
}
