package io.iohk.scalanet.kchat

import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.kademlia.KRouter
import scodec.bits.BitVector

import scala.concurrent.{Await, Promise}
import scala.util.parsing.combinator._
import scala.concurrent.duration._

trait CommandParser extends RegexParsers {

  sealed trait Command {
    def applyTo(kRouter: KRouter[InetMultiAddress]): String
  }

  object Command {
    import scala.concurrent.ExecutionContext.Implicits.global
    case class GetCommand(nodeId: BitVector) extends Command {
      override def applyTo(kRouter: KRouter[InetMultiAddress]): String = {
        val p = Promise[String]()
        kRouter.get(nodeId).onComplete {
          case util.Failure(exception) =>
            p.success(exception.getMessage)
          case util.Success(nodeRecord) =>
            p.success(nodeRecord.toString)
        }
        Await.result(p.future, 1 second)
      }
    }

    case class RemoveCommand(nodeId: BitVector) extends Command {
      override def applyTo(kRouter: KRouter[InetMultiAddress]): String = {
        kRouter.kBuckets.remove(nodeId)
        s"Node id ${nodeId.toHex} removed from local kBuckets"
      }
    }

    case class ExitCommand() extends Command {
      override def applyTo(kRouter: KRouter[InetMultiAddress]): String = {
        System.exit(0)
        ""
      }
    }

    case class InvalidCommand(invalidToken: String) extends Command {
      override def applyTo(kRouter: KRouter[InetMultiAddress]): String =
        s"Unable to process invalid command '$invalidToken'"
    }
  }

  import Command._

  def command: Parser[Command] = getCommand | removeCommand | exitCommand

  def getCommand: Parser[GetCommand] = "get" ~> nodeId ^^ { GetCommand }

  def removeCommand: Parser[RemoveCommand] = "remove" ~> nodeId ^^ { RemoveCommand }

  def exitCommand: Parser[ExitCommand] = "exit" ^^ { _ =>
    ExitCommand()
  }

  def nodeId: Parser[BitVector] = """^[a-fA-F0-9]+$""".r ^^ { BitVector.fromValidHex(_) }
}

object CommandParser extends CommandParser
