package io.iohk.network

import akka.util.ByteString
import io.iohk.network.utils.HexStringCodec
import org.scalacheck.Arbitrary._
import org.scalacheck.Gen.listOf
import org.scalatest.FlatSpec
import org.scalatest.prop.GeneratorDrivenPropertyChecks._
import org.scalatest.Matchers._

class HexStringCodecSpec extends FlatSpec {

  private val byteStrings = listOf(arbitrary[Byte]).map(bytes => ByteString(bytes: _*))

  behavior of "HexCodec"

  it should "encode and decode a ByteString as Hex" in forAll(byteStrings) { b =>
    HexStringCodec.fromHexString(HexStringCodec.toHexString(b)) shouldBe b
  }
}
