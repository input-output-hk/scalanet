package io.iohk.scalanet.kconsole

import com.typesafe.config.{ConfigFactory, ConfigRenderOptions, ConfigValue}
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.kademlia.KRouter
import io.iohk.scalanet.kademlia.KRouter.NodeRecord
import pureconfig.ConfigWriter
import scodec.bits.BitVector

import java.net.{InetSocketAddress, ServerSocket}
import scala.util.Random

object Utils {
  private def aRandomAddress(): InetSocketAddress = {
    val s = new ServerSocket(0)
    try {
      new InetSocketAddress("localhost", s.getLocalPort)
    } finally {
      s.close()
    }
  }

  def generateRandomConfig: KRouter.Config[InetMultiAddress] = {

    def randomNodeId: BitVector =
      BitVector.bits(Range(0, 160).map(_ => Random.nextBoolean()))

    def aRandomNodeRecord: NodeRecord[InetMultiAddress] = {
      NodeRecord(
        id = randomNodeId,
        routingAddress = InetMultiAddress(aRandomAddress()),
        messagingAddress = InetMultiAddress(aRandomAddress())
      )
    }
    KRouter.Config(aRandomNodeRecord, Set.empty)
  }

  def configToStr(config: KRouter.Config[InetMultiAddress]): String = {
    import pureconfig.generic.auto._
    import PureConfigReadersAndWriters._
    val configValue: ConfigValue =
      ConfigWriter[KRouter.Config[InetMultiAddress]].to(generateRandomConfig)

    configValue.render(ConfigRenderOptions.defaults().setComments(false))
  }

  def recordToStr(nodeRecord: NodeRecord[InetMultiAddress]): String = {
    import pureconfig.generic.auto._
    import PureConfigReadersAndWriters._
    val configValue: ConfigValue =
      ConfigWriter[NodeRecord[InetMultiAddress]].to(nodeRecord)

    configValue.render(ConfigRenderOptions.concise())
  }

  def parseRecord(nodeRecordStr: String): NodeRecord[InetMultiAddress] = {
    import pureconfig.generic.auto._
    import PureConfigReadersAndWriters._

    pureconfig.loadConfigOrThrow[NodeRecord[InetMultiAddress]](
      ConfigFactory.parseString(nodeRecordStr)
    )
  }
}
