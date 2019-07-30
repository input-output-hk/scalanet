package io.iohk.scalanet.kchat

import java.net.InetAddress

import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import pureconfig.{ConfigReader, ConfigWriter}
import pureconfig.ConvertHelpers.catchReadError
import pureconfig.configurable.{genericMapReader, genericMapWriter}
import pureconfig.generic.auto._
import scodec.bits.BitVector

object PureConfigReadersAndWriters {
  implicit val bitvectorReader: ConfigReader[BitVector] =
    ConfigReader[String].map(BitVector.fromValidHex(_))
  implicit val inetAddressReader: ConfigReader[InetAddress] =
    ConfigReader[String].map(InetAddress.getByName)
  implicit val knownPeerReader: ConfigReader[Map[BitVector, NodeRecord]] =
    genericMapReader[BitVector, NodeRecord](
      catchReadError(BitVector.fromValidHex(_))
    )

  implicit val bitvectorWriter: ConfigWriter[BitVector] =
    ConfigWriter[String].contramap(_.toHex)
  implicit val inetAddressWriter: ConfigWriter[InetAddress] =
    ConfigWriter[String].contramap(_.getHostAddress)
  implicit val knownPeerWriter: ConfigWriter[Map[BitVector, NodeRecord]] =
    genericMapWriter[BitVector, NodeRecord](_.toHex)
}
