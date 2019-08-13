package io.iohk.scalanet.kconsole
import java.io.File
import java.nio.file.Path

import ch.qos.logback.classic.util.ContextInitializer
import io.iohk.scalanet.kconsole.Utils.{configToStr, generateRandomConfig}
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.kademlia.KRouter
import monix.execution.Scheduler.Implicits.global

object App extends App with CommandParser {

  case class CommandLineOptions(
      configFile: Option[Path] = None,
      generateConfig: Boolean = false,
      bootstrapRecord: Option[String] = None
  )

  val optionsParser = new scopt.OptionParser[CommandLineOptions]("kconsole") {

    head("kconsole", "0.1")

    opt[File]('c', "config")
      .action((p, c) => c.copy(configFile = Some(p.toPath)))
      .text("read config for the node")

    opt[String]('b', "bootstrap")
      .action((b, c) => c.copy(bootstrapRecord = Some(b)))
      .text(
        "Bootstrap node record in the format '{\"id\":\"<id hex>\",\"messaging-address\":\"<host>:<port>\",\"routing-address\":\"<host>:<port>\"}"
      )

    opt[Unit]('g', "generate")
      .action((_, c) => c.copy(generateConfig = true))
      .text("generate a config and exit")

    help('h', "help").text("print usage and exit")

    checkConfig((c: CommandLineOptions) => success)
  }

  val commandLineOptions = getCommandLineOptions

  if (commandLineOptions.generateConfig) {
    generateAndWriteConfigAndExit()
  }

  val nodeConfig = commandLineOptions.bootstrapRecord
    .map(configFromBootstrapOption)
    .orElse(commandLineOptions.configFile.map(configFromConfigFile))
    .getOrElse(generateRandomConfig)

  val kRouter = new AppContext(nodeConfig).kRouter

  ConsoleLoop.run(kRouter)

  private def getCommandLineOptions: CommandLineOptions = {
    optionsParser.parse(args, CommandLineOptions()) match {
      case Some(config) =>
        config
      case _ =>
        System.exit(1)
        null
    }
  }

  private def configFromBootstrapOption(
      bootstrapRecordStr: String
  ): KRouter.Config[InetMultiAddress] = {

    val bootstrapRecord = Utils.parseRecord(bootstrapRecordStr)
    generateRandomConfig.copy(knownPeers = Set(bootstrapRecord))
  }

  private def configFromConfigFile(
      configFile: Path
  ): KRouter.Config[InetMultiAddress] = {
    import pureconfig.generic.auto._
    import PureConfigReadersAndWriters._

    System.setProperty(
      ContextInitializer.CONFIG_FILE_PROPERTY,
      configFile.getFileName.toString.replace(".conf", "-logback.xml")
    )

    pureconfig.loadConfigOrThrow[KRouter.Config[InetMultiAddress]](configFile)
  }

  private def generateAndWriteConfigAndExit(): Unit = {
    println(configToStr(generateRandomConfig))
    System.exit(0)
  }

}
