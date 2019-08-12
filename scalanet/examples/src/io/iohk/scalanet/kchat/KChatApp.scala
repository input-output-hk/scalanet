package io.iohk.scalanet.kchat
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path

import ch.qos.logback.classic.util.ContextInitializer
import com.typesafe.config.ConfigValue
import io.iohk.scalanet.peergroup.{InetMultiAddress, PeerGroup, UDPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.kademlia.{KMessage, KRouter}
import pureconfig.ConfigWriter
import pureconfig.generic.auto._
import scodec.bits.BitVector
import monix.execution.Scheduler.Implicits.global

import scala.io.StdIn
import scala.util.Random

object KChatApp extends App with CommandParser {
  import PureConfigReadersAndWriters._

  case class CommandLineOptions(configFile: Option[Path] = None, generateConfig: Boolean = false)

  val commandLineOptions = getCommandLineOptions

  commandLineOptions.configFile.foreach { configFile: Path =>
    System.setProperty(
      ContextInitializer.CONFIG_FILE_PROPERTY,
      configFile.getFileName.toString.replace(".conf", "-logback.xml")
    )

    val kRouter = getKRouter(configFile)

    import Console.{GREEN, RED, RESET, YELLOW}

    while (true) {
      val commandStr: String = StdIn.readLine("> ")
      if (commandStr != null) {
        parse(command, commandStr) match {
          case Success(result, _) =>
            Console.println(s"${RESET}${GREEN}${result.applyTo(kRouter)}${RESET}")
          case Failure(msg, _) =>
            Console.println(s"${RESET}${YELLOW}$msg${RESET}")
          case Error(msg, _) =>
            Console.println(s"${RESET}${RED}$msg${RESET}")
        }
      }
    }
  }

  private def getKRouter(configFile: Path): KRouter[InetMultiAddress] = {
    val nodeConfig: KRouter.Config[InetMultiAddress] =
      pureconfig
        .loadConfigOrThrow[KRouter.Config[InetMultiAddress]](configFile)

    import io.iohk.scalanet.peergroup.kademlia.BitVectorCodec._
    import io.iohk.decco.auto._
    import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer

    try {
      println(s"Initializing for ${nodeConfig.nodeRecord}")

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

      new KRouter[InetMultiAddress](nodeConfig, kNetwork)

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
        routingAddress = InetMultiAddress(new InetSocketAddress("127.0.0.1", 8080)),
        messagingAddress = InetMultiAddress(new InetSocketAddress("127.0.0.1", 9090))
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
