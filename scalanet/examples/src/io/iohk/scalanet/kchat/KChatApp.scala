package io.iohk.scalanet.kchat
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path

import ch.qos.logback.classic.util.ContextInitializer
import com.typesafe.config.ConfigValue
import io.iohk.scalanet.peergroup.{InetMultiAddress, PeerGroup, UDPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.kademlia.{KMessage, KPeerGroup, KRouter}
import monix.reactive.subjects.PublishSubject
import pureconfig.ConfigWriter
import pureconfig.generic.auto._
import scodec.bits.BitVector
import monix.execution.Scheduler.Implicits.global

import scala.util.Random

object KChatApp extends App {
  import PureConfigReadersAndWriters._

  case class CommandLineOptions(configFile: Option[Path] = None,
                                generateConfig: Boolean = false)

  val commandLineOptions = getCommandLineOptions

  commandLineOptions.configFile.foreach { configFile: Path =>
    System.setProperty(
      ContextInitializer.CONFIG_FILE_PROPERTY,
      configFile.getFileName.toString.replace(".conf", "-logback.xml")
    )
//    StatusPrinter.print(LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext])

    val kPeerGroup: PeerGroup[BitVector, String] = getKPeerGroup(configFile)

    import Console.{GREEN, RED, RESET}
    import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
    kPeerGroup.server().collectChannelCreated.foreach { channel =>
      channel.in.foreach{ message =>
        Console.println(s"> ${RESET}${GREEN}${channel.to}${RESET}: ${RED}$message${RESET}")
      }
    }

    kPeerGroup.client(kPeerGroup.processAddress).map { channel =>
      channel.sendMessage("Hellllooo 1")
      channel.sendMessage("Hellllooo 2")
      channel.sendMessage("Hellllooo 3")
    }.runAsync
  }

  private def getKPeerGroup(configFile: Path): PeerGroup[BitVector, String] = {
    val nodeConfig: KRouter.Config[InetMultiAddress] =
      pureconfig
        .loadConfigOrThrow[KRouter.Config[InetMultiAddress]](configFile)

    import io.iohk.scalanet.peergroup.kademlia.BitVectorCodec._
    import io.iohk.decco.auto._
    import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer

    try {
      println(s"Initializing chat for ${nodeConfig.nodeRecord}")

      val routingConfig =
        UDPPeerGroup.Config(
          nodeConfig.nodeRecord.routingAddress.inetSocketAddress
        )
      val routingPeerGroup = PeerGroup.createOrThrow(
        new UDPPeerGroup[KMessage[InetMultiAddress]](routingConfig),
        routingConfig
      )
      val kNetwork =
        new KNetworkScalanetImpl[InetMultiAddress](routingPeerGroup)
      val kRouter = new KRouter[InetMultiAddress](nodeConfig, kNetwork)

      val messagingConfig =
        UDPPeerGroup.Config(
          nodeConfig.nodeRecord.messagingAddress.inetSocketAddress
        )
      val messagingPeerGroup = PeerGroup.createOrThrow(
        new UDPPeerGroup[Either[NodeRecord[InetMultiAddress], String]](
          messagingConfig
        ),
        messagingConfig
      )

      PeerGroup.createOrThrow(
        new KPeerGroup[InetMultiAddress, String](
          kRouter,
          PublishSubject(),
          messagingPeerGroup
        ),
        "KPeerGroup"
      )

    } catch {
      case e: Exception =>
        System.err.println(
          s"Exiting due to initialization error: $e, ${e.getCause}"
        )
        throw e
    }
  }

  if (commandLineOptions.generateConfig) {
    val value: ConfigValue =
      ConfigWriter[KRouter.Config[InetMultiAddress]].to(generateRandomConfig)

    println(value.render())
  }

  def generateRandomConfig: KRouter.Config[InetMultiAddress] = {

    def randomNodeId: BitVector =
      BitVector.bits(Range(0, 160).map(_ => Random.nextBoolean()))

    def aRandomNodeRecord: NodeRecord[InetMultiAddress] = {
      NodeRecord(
        id = randomNodeId,
        routingAddress =
          InetMultiAddress(new InetSocketAddress("127.0.0.1", 8080)),
        messagingAddress =
          InetMultiAddress(new InetSocketAddress("127.0.0.1", 9090))
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
