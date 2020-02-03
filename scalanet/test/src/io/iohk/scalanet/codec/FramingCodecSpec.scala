package io.iohk.scalanet.codec

import scodec.Codec
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import scodec.bits.BitVector
import scodec.codecs.implicits._
import scodec.codecs._
import scala.util.Random
import org.scalatest.prop.GeneratorDrivenPropertyChecks._
import FramingCodecSpec._
import scodec.codecs.Discriminated

class FramingCodecSpec extends FlatSpec {

  behavior of "FramingCodec"

  it should "deal with correct messages" in {
    val encoder = new FramingCodec[String](Codec[String])

    forAll(genString()) { listOfString =>
      val enc = listOfString.map(encoder.encode(_).require).foldLeft(BitVector.empty)((acc, bit) => acc ++ bit)
      val decoded = encoder.streamDecode(enc)
      decoded.toList shouldEqual listOfString
    }
  }

  it should "deal with correct simple messages fragmented over several packets" in {
    val encoder = new FramingCodec[String](Codec[String])

    forAll(genString(), Gen.choose(minPacketSize, maxPacketSize)) { (listOfString, packetSize) =>
      val enc = listOfString.map(encoder.encode(_).require).foldLeft(BitVector.empty)((acc, bit) => acc ++ bit)
      val encPacketed = enc.grouped(packetSize).toList
      val decoded = encPacketed.foldLeft(List(): List[String])((acc, vec) => acc ++ encoder.streamDecode(vec))
      decoded shouldEqual listOfString
    }
  }

  it should "deal with correct complex messages fragmented over several packets" in {
    val encoder = new FramingCodec[RandomMessage](c)

    forAll(randomMessages(), Gen.choose(minPacketSize, maxPacketSize)) {
      (listOfMessages: List[RandomMessage], packetSize) =>
        val enc = listOfMessages.map(encoder.encode(_).require).foldLeft(BitVector.empty)((acc, bit) => acc ++ bit)
        val encPacketed = enc.grouped(packetSize).toList
        val decoded = encPacketed.foldLeft(List(): List[RandomMessage])((acc, vec) => acc ++ encoder.streamDecode(vec))
        decoded shouldEqual listOfMessages
    }
  }

  it should "handle to long message" in {
    val longCodec = new FramingCodec(Codec[String], 8)
    val shortCodec = new FramingCodec(Codec[String], 6)
    val sourceMessage = Random.nextString(9)
    val enc = longCodec.encode(sourceMessage).require
    val dec = shortCodec.streamDecode(enc)
    dec shouldEqual Seq()
    val sourceMessage1 = Random.nextString(9)
    val enc1 = longCodec.encode(sourceMessage1).require
    val dec1 = longCodec.streamDecode(enc1)
    dec1 shouldEqual List(sourceMessage1)
  }

}

object FramingCodecSpec {
  val minMessages = 20
  val maxMessages = 50
  val minPacketSize = 8
  val maxPacketSize = 256

  def genString(): Gen[List[String]] =
    for {
      length <- Gen.choose(minMessages, maxMessages)
      strings <- Gen.listOfN(length, arbitrary[String])
    } yield strings

  sealed trait RandomMessage
  implicit val discMessage = Discriminated[RandomMessage, Int](uint16)
  case class MessageA(num: Int, string: String) extends RandomMessage
  implicit val discMessageA = Discriminator[RandomMessage, MessageA, Int](0)
  case class MessageB(num: Long) extends RandomMessage
  implicit val discMessageB = Discriminator[RandomMessage, MessageB, Int](1)
  case class MessageC(string: String, num: Long, num1: Long) extends RandomMessage
  implicit val discMessagec = Discriminator[RandomMessage, MessageC, Int](2)

  val c = Codec[RandomMessage]

  def genMessageA(): Gen[MessageA] =
    for {
      i <- Gen.chooseNum(1, 100)
      s <- arbitrary[String]
    } yield MessageA(i, s)

  def genMessageB(): Gen[MessageB] =
    for {
      i <- Gen.chooseNum(1L, 10000L)
    } yield MessageB(i)

  def genMessageC(): Gen[MessageC] =
    for {
      i <- Gen.chooseNum(1L, 10000L)
      j <- Gen.chooseNum(1L, 100000L)
      s <- arbitrary[String]
    } yield MessageC(s, i, j)

  def genMessage(): Gen[RandomMessage] =
    for {
      i <- Gen.chooseNum(0, 2)
      m <- {
        if (i == 0)
          genMessageA()
        else if (i == 1)
          genMessageB()
        else
          genMessageC()
      }

    } yield m

  def randomMessages(): Gen[List[RandomMessage]] =
    for {
      length <- Gen.choose(minMessages, maxMessages)
      list <- Gen.listOfN(length, genMessage())
    } yield list

}
