package io.iohk.scalanet.kconsole

import java.net.{InetSocketAddress, URI}

import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import pureconfig.{ConfigReader, ConfigWriter}
import pureconfig.ConvertHelpers.catchReadError
import pureconfig.configurable.{genericMapReader, genericMapWriter}
import pureconfig.generic.auto._
import scodec.bits.BitVector

object PureConfigReadersAndWriters {
  implicit val bitvectorReader: ConfigReader[BitVector] =
    ConfigReader[String].map(BitVector.fromValidHex(_))
//  implicit val inetAddressReader: ConfigReader[InetAddress] =
//    ConfigReader[String].map(InetAddress.getByName)
//  implicit val inetSocketAddressReader: ConfigReader[InetSocketAddress] =
//    ConfigReader[String].map(parseAddressString)
  implicit val inetMultiAddressReader: ConfigReader[InetMultiAddress] =
    ConfigReader[String].map(parseAddressString)
  implicit def knownPeerReader: ConfigReader[Map[BitVector, NodeRecord[InetMultiAddress]]] =
    genericMapReader[BitVector, NodeRecord[InetMultiAddress]](
      catchReadError(BitVector.fromValidHex(_))
    )

  implicit val bitvectorWriter: ConfigWriter[BitVector] =
    ConfigWriter[String].contramap(_.toHex)
//  implicit val inetAddressWriter: ConfigWriter[InetAddress] =
//    ConfigWriter[String].contramap(_.getHostAddress)
//  implicit val inetSocketAddressWriter: ConfigWriter[InetSocketAddress] =
//    ConfigWriter[String].contramap(address => s"${address.getHostString}:${address.getPort}")
  implicit val inetMultiAddressWriter: ConfigWriter[InetMultiAddress] =
    ConfigWriter[String].contramap(
      address => s"${address.inetSocketAddress.getHostString}:${address.inetSocketAddress.getPort}"
    )
  implicit def knownPeerWriter: ConfigWriter[Map[BitVector, NodeRecord[InetMultiAddress]]] =
    genericMapWriter[BitVector, NodeRecord[InetMultiAddress]](_.toHex)

  private def parseAddressString(s: String): InetMultiAddress = {
    val uri: URI = new URI("my://" + s)
    InetMultiAddress(new InetSocketAddress(uri.getHost, uri.getPort))
  }
}
