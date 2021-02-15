package io.iohk.scalanet.discovery.ethereum

import scodec.bits.ByteVector
import java.nio.charset.StandardCharsets

object NetworkId {
  private val key =
    EthereumNodeRecord.Keys.key("network-id")

  private def toBytes(networkId: String) =
    ByteVector(networkId.getBytes(StandardCharsets.UTF_8))

  def enrAttr(networkId: String): (ByteVector, ByteVector) =
    key -> toBytes(networkId)

  def enrFilter(networkId: String): EthereumNodeRecord => Either[String, Unit] = {
    val bytes = toBytes(networkId)
    enr => Either.cond(enr.content.attrs.get(key).contains(bytes), (), "network mismatch")
  }
}
