package io.iohk.scalanet.kconsole
import java.io.File
import java.nio.file.Path
import cats.effect.ExitCode
import cats.implicits._
import io.iohk.scalanet.kconsole.Utils.{configToStr, generateRandomConfig}
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.kademlia.KRouter
import monix.execution.Scheduler.Implicits.global
import monix.eval.{Task, TaskApp}
import scopt.OptionParser
import scala.util.control.NonFatal

object App extends TaskApp with CommandParser {

  case class CommandLineOptions(
      configFile: Option[Path] = None,
      generateConfig: Boolean = false,
      bootstrapRecord: Option[String] = None,
      k: Int = 20,
      alpha: Int = 3
  )

  val optionsParser = new OptionParser[CommandLineOptions]("kconsole") {

    head("kconsole", "0.1")

    opt[File]('c', "config")
      .action((p, c) => c.copy(configFile = Some(p.toPath)))
      .text("""
          | Read config for the node from the given file.
          | The format is the same as that created using the -g option.
          |""".stripMargin)

    opt[String]('b', "bootstrap")
      .action((b, c) => c.copy(bootstrapRecord = Some(b)))
      .text("""
          | Specify a bootstrap node record in the format
          | {"id":"<id hex>","messaging-address":"<host>:<port>","routing-address":"<host>:<port>"}
          |""".stripMargin)

    opt[Unit]('g', "generate")
      .action((_, c) => c.copy(generateConfig = true))
      .text("""
          | Generate a config, print it to stdout and exit.
          | You may then copy-and-paste this config into a file for later use as an argument to -c.
          |""".stripMargin)

    opt[Int]('k', "K")
      .action((k, c) => c.copy(k = k))
      .text("""
            | Override the node's k value.
            |""".stripMargin)

    opt[Int]('a', "alpha")
      .action((a, c) => c.copy(alpha = a))
      .text("""
          | Override the node's alpha value.
          |""".stripMargin)

    help('h', "help").text("print usage and exit")

    note("""
        | It is simplest to run the app with no arguments.
        | In this scenario, it will generate a node configuration and use that.
        | The -b option can be helpful if you wish to tell the node how
        | to join an existing network. Alternatively, you can use the 'add'
        | console command, to add a known node, after this node has started.
        | The default value of k (kademlia's connectivity parameter) is 20,
        | which is a very large value for a small, manually created network.
        | A better value is 2. You can set this with the -k parameter.
        | The default value of alpha (the node's concurrency parameter) is 3.
        | It is easier to understand what the node is doing if you set this
        | to 1 using the -a parameter.
        |""".stripMargin)
    checkConfig((c: CommandLineOptions) => success)
  }

  override def run(args: List[String]): Task[ExitCode] = {
    Task(optionsParser.parse(args, CommandLineOptions())) flatMap {
      case None =>
        Task.pure(ExitCode.Error)

      case Some(options) =>
        if (options.generateConfig) {
          generateAndWriteConfigAndExit
        } else {
          val nodeConfig = configFromBootstrapOption(options)
            .orElse(configFromConfigFile(options))
            .getOrElse(randomConfig(options))

          AppContext(nodeConfig)
            .use { kRouter =>
              Task(ConsoleLoop.run(kRouter)).as(ExitCode.Success)
            }
            .onErrorRecover {
              case NonFatal(ex) =>
                System.err.println(s"Error running Kademlia: $ex")
                ExitCode.Error
            }
        }
    }
  }

  private def configFromBootstrapOption(
      options: CommandLineOptions
  ): Option[KRouter.Config[InetMultiAddress]] = {
    options.bootstrapRecord
      .map(
        bootstrapRecordStr => configFromBootstrapOption(bootstrapRecordStr).copy(k = options.k, alpha = options.alpha)
      )
  }

  private def configFromBootstrapOption(
      bootstrapRecordStr: String
  ): KRouter.Config[InetMultiAddress] = {

    val bootstrapRecord = Utils.parseRecord(bootstrapRecordStr)
    generateRandomConfig.copy(knownPeers = Set(bootstrapRecord))
  }

  private def configFromConfigFile(options: CommandLineOptions): Option[KRouter.Config[InetMultiAddress]] = {
    options.configFile.map(configFile => configFromConfigFile(configFile).copy(k = options.k, alpha = options.alpha))
  }

  private def configFromConfigFile(
      configFile: Path
  ): KRouter.Config[InetMultiAddress] = {
    import pureconfig.generic.auto._
    import PureConfigReadersAndWriters._

    pureconfig.loadConfigOrThrow[KRouter.Config[InetMultiAddress]](configFile)
  }

  private def randomConfig(options: CommandLineOptions): KRouter.Config[InetMultiAddress] = {
    generateRandomConfig.copy(k = options.k, alpha = options.alpha)
  }

  private def generateAndWriteConfigAndExit: Task[ExitCode] = {
    Task(println(configToStr(generateRandomConfig))).as(ExitCode.Success)
  }
}
