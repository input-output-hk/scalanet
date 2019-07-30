package io.iohk.scalanet.kchat
import java.io.File
import java.net.{InetAddress}
import java.nio.file.Path

import com.typesafe.config.ConfigValue
import io.iohk.scalanet.peergroup.{PeerGroup, UDPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.kademlia.{KMessage, KPeerGroup, KRouter}
import monix.reactive.subjects.PublishSubject
import pureconfig.ConfigWriter
import pureconfig.generic.auto._
import scodec.bits.BitVector

import scala.util.Random

object KChatApp extends App {
  import PureConfigReadersAndWriters._

  case class CommandLineOptions(configFile: Option[Path] = None, generateConfig: Boolean = false)

  val commandLineOptions = getCommandLineOptions

  commandLineOptions.configFile.foreach { configFile =>
    val nodeConfig: KRouter.Config =
      pureconfig.loadConfigOrThrow[KRouter.Config](configFile)

    import monix.execution.Scheduler.Implicits.global
    import io.iohk.scalanet.peergroup.kademlia.BitVectorCodec._
    import io.iohk.decco.auto._
    import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer

    try {
      println(s"Initializing chat for ${nodeConfig.nodeRecord}")

      val routingPeerGroup = PeerGroup.createOrThrow(
        new UDPPeerGroup[KMessage](UDPPeerGroup.Config(nodeConfig.nodeRecord.udpSocketAddress)),
        "Routing"
      )
      val kNetwork = new KNetworkScalanetImpl(routingPeerGroup)
      val kRouter = new KRouter(nodeConfig, kNetwork)

      // TODO abstract the address types in NodeRecord
      val messagingPeerGroup = PeerGroup.createOrThrow(
        new UDPPeerGroup[Either[NodeRecord, String]](UDPPeerGroup.Config(nodeConfig.nodeRecord.tcpSocketAddress)),
        "Messaging"
      )

      val kPeerGroup =
        PeerGroup.createOrThrow(new KPeerGroup[String](kRouter, PublishSubject(), messagingPeerGroup), "KPeerGroup")

      println("Chat initialized.")
    } catch {
      case e: Exception =>
        System.err.println(s"Exiting due to initialization error: $e, ${e.getCause}")
        System.exit(1)
    }
  }

  if (commandLineOptions.generateConfig) {
    val value: ConfigValue =
      ConfigWriter[KRouter.Config].to(generateRandomConfig)

    println(value.render())
  }

  def generateRandomConfig: KRouter.Config = {

    def randomNodeId: BitVector =
      BitVector.bits(Range(0, 160).map(_ => Random.nextBoolean()))

    def aRandomNodeRecord: NodeRecord = {
      NodeRecord(
        id = randomNodeId,
        ip = InetAddress.getLocalHost,
        tcp = 8080,
        udp = 9090
      )
    }

    KRouter.Config(aRandomNodeRecord, Set.empty)
  }

  def getCommandLineOptions: CommandLineOptions = {
    val parser = new scopt.OptionParser[CommandLineOptions]("kchat") {

      head("kchat", "0.1")

      opt[File]('c', "config")
        .action((p, c) => c.copy(configFile = Some(p.toPath)))
        .text("read config for the node")

      opt[Unit]('g', "generate")
        .action((_, c) => c.copy(generateConfig = true))
        .text("generate a config and exit")
    }

    parser.parse(args, CommandLineOptions()) match {
      case Some(config) =>
        config
      case _ =>
        System.exit(1)
        null
    }
  }
}
