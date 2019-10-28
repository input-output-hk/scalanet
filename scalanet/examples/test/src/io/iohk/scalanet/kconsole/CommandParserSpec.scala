package io.iohk.scalanet.kconsole

import java.net.InetSocketAddress

import io.iohk.scalanet.kconsole.CommandParser.Command.{
  AddCommand,
  DumpCommand,
  ExitCommand,
  GetCommand,
  HelpCommand,
  RemoveCommand
}
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.prop.TableDrivenPropertyChecks._
import scodec.bits._

class CommandParserSpec extends FlatSpec {

  val t = Table(
    ("command", "result"),
    (
      "add {id:\"a0fd10a54e202b7d9a4948b4890d14447bf93a08\", routing-address:\"127.0.0.1:1034\", messaging-address:\"127.0.0.1:1035\"}",
      AddCommand(
        NodeRecord(
          hex"a0fd10a54e202b7d9a4948b4890d14447bf93a08".bits,
          InetMultiAddress(new InetSocketAddress("127.0.0.1", 1034)),
          InetMultiAddress(new InetSocketAddress("127.0.0.1", 1035))
        )
      )
    ),
    (
      "get a0fd10a54e202b7d9a4948b4890d14447bf93a08",
      GetCommand(hex"a0fd10a54e202b7d9a4948b4890d14447bf93a08".bits)
    ),
    (
      "remove a0fd10a54e202b7d9a4948b4890d14447bf93a08",
      RemoveCommand(hex"a0fd10a54e202b7d9a4948b4890d14447bf93a08".bits)
    ),
    ("dump", DumpCommand()),
    ("exit", ExitCommand()),
    ("help", HelpCommand())
  )

  "CommandParser" should "parse commands" in {
    forAll(t) { (command, expectedResult) =>
      CommandParser.parse(CommandParser.command, command).get shouldBe expectedResult
    }
  }
}
