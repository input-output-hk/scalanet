package io.iohk.scalanet.format

import java.nio.ByteBuffer

import io.iohk.scalanet.NetUtils
import org.scalatest.FlatSpec
import org.scalatest.Matchers._

import scala.util.Random

class StreamDecoder1Spec extends FlatSpec {

  behavior of "StreamDecoder1"

  it should "handle an empty buffer" in {
    val d = new StreamDecoder1

    val buffers: Seq[ByteBuffer] = d.fmfn(buffFromBytes())

    buffers shouldBe Seq.empty
    d.state shouldBe StreamDecoder1.State.LengthExpected
  }

  it should "handle a one byte buffer" in {
    val d = new StreamDecoder1

    val buffers: Seq[ByteBuffer] = d.fmfn(buffFromBytes(0.toByte))

    buffers shouldBe Seq.empty
    d.nlb.get(0) shouldBe 0.toByte
    d.state shouldBe StreamDecoder1.State.LengthExpected
  }

  it should "handle a two byte buffer" in {
    val d = new StreamDecoder1

    val buffers: Seq[ByteBuffer] = d.fmfn(buffFromBytes(0.toByte, 1.toByte))

    buffers shouldBe Seq.empty
    d.nlb.get(0) shouldBe 0.toByte
    d.nlb.get(1) shouldBe 1.toByte
    d.state shouldBe StreamDecoder1.State.LengthExpected
  }

  it should "handle a four byte buffer" in {
    val d = new StreamDecoder1

    val buffers: Seq[ByteBuffer] = d.fmfn(buffFrom(1))

    buffers shouldBe Seq.empty
    d.length shouldBe 1
    d.state shouldBe StreamDecoder1.State.BytesExpected
  }

  it should "handle a buffer with less than a complete message" in {
    val d = new StreamDecoder1

    val buffers: Seq[ByteBuffer] = d.fmfn(generatePartMessage(1024))

    buffers shouldBe Seq.empty
    d.length shouldBe 1024

  }

  it should "handle a buffer with a complete message and a bit more" in {}

  it should "handle a buffer with two complete messages and a bit more" in {}

  private def buffFrom(i: Int): ByteBuffer = {
    val bb = ByteBuffer.allocate(4)
    bb.putInt(i)
    bb.clear()
    bb
  }

  private def buffFromBytes(bytes: Byte*): ByteBuffer = {
    val bb = ByteBuffer.allocate(bytes.length)
    bb.put(bytes.toArray)
    bb.clear()
    bb
  }

  private def generatePartMessage(messageLength: Int): ByteBuffer = {
    val lengthToWrite = Random.nextInt(messageLength - 1)
    val bb = ByteBuffer.allocate(4 + lengthToWrite)
    bb.putInt(messageLength)
    bb.put(NetUtils.randomBytes(lengthToWrite))
    bb.clear()
    bb
  }

  private def generateMessage(messageLength: Int, garbageLength: Int): ByteBuffer = {
    val bb = ByteBuffer.allocate(4 + messageLength + garbageLength)
    bb.putInt(messageLength)
    bb.put(NetUtils.randomBytes(messageLength))
    bb.putInt(garbageLength)
    bb.put(NetUtils.randomBytes(garbageLength))
    bb.clear()
    bb
  }
}
