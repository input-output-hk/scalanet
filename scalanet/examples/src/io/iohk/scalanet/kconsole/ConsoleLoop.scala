package io.iohk.scalanet.kconsole

import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.kademlia.KRouter

import scala.io.StdIn

object ConsoleLoop extends CommandParser {
  def run(kRouter: KRouter[InetMultiAddress]): Unit = {
    import Console.{GREEN, RED, RESET, YELLOW}

    Console.println(
      s"${RESET}${GREEN}Initialized with node record ${Utils.recordToStr(kRouter.config.nodeRecord)}"
    )
    Console.println(s"${CommandParser.Command.help}${RESET}")

    while (true) {
      val commandStr: String = StdIn.readLine("> ")
      if (commandStr != null && commandStr.replaceAll("\\s+", "").nonEmpty) {
        parse(command, commandStr) match {
          case Success(result, _) =>
            try {
              val output = result.applyTo(kRouter)
              Console.println(s"${RESET}${GREEN}$output${RESET}")
            } catch {
              case e: Exception =>
                Console.println(s"${RESET}${RED}$e${RESET}")
            }
          case Failure(msg, _) =>
            Console.println(s"${RESET}${YELLOW}$msg${RESET}")
          case Error(msg, _) =>
            Console.println(s"${RESET}${RED}$msg${RESET}")
        }
      }
    }
  }
}
