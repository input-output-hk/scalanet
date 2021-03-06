package io.iohk.scalanet.kconsole

import io.iohk.scalanet.kconsole.Utils.parseRecord
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.kademlia.KRouter
import io.iohk.scalanet.kademlia.KRouter.NodeRecord
import scodec.bits.BitVector

import scala.concurrent.{Await, Promise}
import scala.util.parsing.combinator._
import scala.concurrent.duration._
import scala.language.postfixOps

trait CommandParser extends RegexParsers {

  sealed trait Command {
    def applyTo(kRouter: KRouter[InetMultiAddress]): String
  }

  object Command {
    import monix.execution.Scheduler.Implicits.global
    case class GetCommand(nodeId: BitVector) extends Command {
      override def applyTo(kRouter: KRouter[InetMultiAddress]): String = {
        val p = Promise[String]()
        kRouter.get(nodeId).runToFuture.onComplete {
          case util.Failure(exception) =>
            p.success(exception.getMessage)
          case util.Success(nodeRecord) =>
            p.success(Utils.recordToStr(nodeRecord))
        }
        Await.result(p.future, 1 second)
      }
    }

    case class AddCommand(nodeRecord: NodeRecord[InetMultiAddress]) extends Command {
      val dumpCommand = DumpCommand()
      override def applyTo(kRouter: KRouter[InetMultiAddress]): String = {
        kRouter.add(nodeRecord).runToFuture
        dumpCommand.applyTo(kRouter)
      }
    }

    case class RemoveCommand(nodeId: BitVector) extends Command {
      override def applyTo(kRouter: KRouter[InetMultiAddress]): String = {
        kRouter.remove(nodeId)
        s"Node id ${nodeId.toHex} removed from local kBuckets"
      }
    }

    case class DumpCommand() extends Command {
      override def applyTo(kRouter: KRouter[InetMultiAddress]): String = {
        kRouter.nodeRecords.runSyncUnsafe().map { case (_, record) => Utils.recordToStr(record) }.mkString("\n")
      }
    }

    case class ExitCommand() extends Command {
      override def applyTo(kRouter: KRouter[InetMultiAddress]): String = {
        System.exit(0)
        ""
      }
    }

    case class HelpCommand() extends Command {
      override def applyTo(kRouter: KRouter[InetMultiAddress]): String = help
    }

    val help: String =
      """
        | Command summary:
        | get    <nodeId hex>  perform a lookup for the given nodeId and prints the record returned (if any).
        | add    <node record> adds the given node record to this node. The record format should be the same as that returned by get.
        | remove <nodeId hex>  remove the given nodeId from this nodes kbuckets.
        | dump                 dump the contents of this nodes kbuckets to the console.
        | help                 print this message.
        | exit                 shutdown the node and quit the application.
        |""".stripMargin
  }

  import Command._

  def command: Parser[Command] = getCommand | addCommand | removeCommand | dumpCommand | helpCommand | exitCommand

  def getCommand: Parser[GetCommand] = "get" ~> nodeId ^^ GetCommand

  def addCommand: Parser[AddCommand] = "add" ~> nodeRecord ^^ AddCommand

  def removeCommand: Parser[RemoveCommand] = "remove" ~> nodeId ^^ RemoveCommand

  def dumpCommand: Parser[DumpCommand] = "dump" ^^ (_ => DumpCommand())

  def helpCommand: Parser[HelpCommand] = "help" ^^ (_ => HelpCommand())

  def exitCommand: Parser[ExitCommand] = "exit" ^^ (_ => ExitCommand())

  def nodeId: Parser[BitVector] = "^[a-fA-F0-9]+$".r ^^ (BitVector.fromValidHex(_))

  def nodeRecord: Parser[NodeRecord[InetMultiAddress]] = ".+".r ^^ parseRecord
}

object CommandParser extends CommandParser
