package io.iohk.scalanet.discovery.ethereum

import scodec.bits.ByteVector
import java.nio.charset.StandardCharsets.UTF_8

object NetworkId {
  private val key =
    EthereumNodeRecord.Keys.key("network-id")

  private def toBytes(networkId: String) =
    ByteVector(networkId.getBytes(UTF_8))

  def enrAttr(networkId: String): (ByteVector, ByteVector) =
    key -> toBytes(networkId)

  def enrFilter(networkId: String): EthereumNodeRecord => Either[String, Unit] = {
    val bytes = toBytes(networkId)
    enr =>
      enr.content.attrs.get(key) match {
        case Some(`bytes`) =>
          Right(())

        case Some(other) =>
          val otherNetworkId = new String(other.toArray, UTF_8)
          Left(s"network-id mismatch; $otherNetworkId != $networkId")

        case None =>
          Left(s"network-id is missing; expected $networkId")
      }
  }
}
