package io.iohk.scalanet.krouterconsole
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Path

import ch.qos.logback.classic.util.ContextInitializer
import com.typesafe.config.ConfigValue
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.kademlia.KRouter
import pureconfig.ConfigWriter
import pureconfig.generic.auto._
import scodec.bits.BitVector
import monix.execution.Scheduler.Implicits.global

import scala.io.StdIn
import scala.util.Random

object App extends App with CommandParser {

  case class CommandLineOptions(configFile: Option[Path] = None, generateConfig: Boolean = false)

  val optionsParser = new scopt.OptionParser[CommandLineOptions]("kchat") {

    head("krouterconsole", "0.1")

    opt[File]('c', "config")
      .action((p, c) => c.copy(configFile = Some(p.toPath)))
      .text("read config for the node")

    opt[Unit]('g', "generate")
      .action((_, c) => c.copy(generateConfig = true))
      .text("generate a config and exit")

    help('h', "help").text("print usage and exit")
  }

  val commandLineOptions = getCommandLineOptions

  commandLineOptions.configFile.foreach { configFile: Path =>
    System.setProperty(
      ContextInitializer.CONFIG_FILE_PROPERTY,
      configFile.getFileName.toString.replace(".conf", "-logback.xml")
    )

    val kRouter = new AppContext(configFile).kRouter

    import Console.{GREEN, RED, RESET, YELLOW, CYAN}

    Console.println(s"${RESET}${CYAN}Router is initialized with node record ${kRouter.config.nodeRecord}")
    Console.println(s"${CommandParser.Command.help}${RESET}")
    while (true) {
      val commandStr: String = StdIn.readLine("> ")
      if (commandStr != null && commandStr.replaceAll("\\s+", "").nonEmpty) {
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

  if (commandLineOptions.generateConfig) {
    import PureConfigReadersAndWriters._
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
    optionsParser.parse(args, CommandLineOptions()) match {
      case Some(config) =>
        config
      case _ =>
        System.exit(1)
        null
    }
  }
}
