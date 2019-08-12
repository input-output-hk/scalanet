package io.iohk.scalanet.kchat

import io.iohk.scalanet.kchat.CommandParser.Command.{GetCommand, RemoveCommand, ExitCommand}
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.prop.TableDrivenPropertyChecks._
import scodec.bits.BitVector.fromValidHex

class CommandParserSpec extends FlatSpec {

  val t = Table(
    ("command", "result"),
    (
      "get a0fd10a54e202b7d9a4948b4890d14447bf93a08",
      GetCommand(fromValidHex("a0fd10a54e202b7d9a4948b4890d14447bf93a08"))
    ),
    (
      "remove a0fd10a54e202b7d9a4948b4890d14447bf93a08",
      RemoveCommand(fromValidHex("a0fd10a54e202b7d9a4948b4890d14447bf93a08"))
    ),
    ("exit", ExitCommand())
  )

  "CommandParser" should "parse commands" in {
    forAll(t) { (command, expectedResult) =>
      CommandParser.parse(CommandParser.command, command).get shouldBe expectedResult
    }
  }
}