package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import io.iohk.scalanet.NetUtils
import org.scalatest.FlatSpec
import org.scalatest.Matchers._

import scala.util.Random

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer

// TODO this could all be a single property test
//      just need to simulate TCP's fixed size write
//      buffer (poss for arbitrary write buffer size)
//      whose contents are sent whenever full.
class FramingCodecSpec extends FlatSpec {

  behavior of "StreamDecoder1"

  it should "handle an empty buffer" in {
    val fc = new FramingCodec

    val buffers: Seq[ByteBuffer] = fc.streamDecode(buffFromBytes())

    buffers shouldBe Seq.empty
    fc.state shouldBe FramingCodec.State.LengthExpected
  }

  it should "handle a one byte buffer" in {
    val fc = new FramingCodec

    val buffers: Seq[ByteBuffer] = fc.streamDecode(buffFromBytes(0.toByte))

    buffers shouldBe Seq.empty
    fc.nlb.get(0) shouldBe 0.toByte
    fc.state shouldBe FramingCodec.State.LengthExpected
  }

  it should "handle a two byte buffer" in {
    val fc = new FramingCodec

    val buffers: Seq[ByteBuffer] = fc.streamDecode(buffFromBytes(0.toByte, 1.toByte))

    buffers shouldBe Seq.empty
    fc.nlb.get(0) shouldBe 0.toByte
    fc.nlb.get(1) shouldBe 1.toByte
    fc.state shouldBe FramingCodec.State.LengthExpected
  }

  it should "handle a four byte buffer" in {
    val fc = new FramingCodec

    val buffers: Seq[ByteBuffer] = fc.streamDecode(buffFrom(1))

    buffers shouldBe Seq.empty
    fc.length shouldBe 1
    fc.state shouldBe FramingCodec.State.BytesExpected
  }

  it should "handle a buffer with less than a complete message" in {
    val fc = new FramingCodec

    val buffers: Seq[ByteBuffer] = fc.streamDecode(generatePartMessage(1024))

    buffers shouldBe Seq.empty
    fc.length shouldBe 1024
  }

  it should "handle a buffer with a complete message" in {
    val fc = new FramingCodec
    val message = generateMessage(1024)

    val buffers: Seq[ByteBuffer] = fc.streamDecode(message)

    buffers shouldBe Seq(subset(4, 1028, message))
  }

  it should "handle a buffer with a complete message plus a bit" in {
    val fc = new FramingCodec
    val message = generateMessagePlus(1024, 512)

    val buffers: Seq[ByteBuffer] = fc.streamDecode(message)

    buffers shouldBe Seq(subset(4, 1028, message))
  }

  it should "handle a message split over several packets" in {
    val fc = new FramingCodec
    val sourceMessage = generateMessage(12)

    val packets = split(sourceMessage, 4)
    val decode0 = fc.streamDecode(packets(0))
    val decode1 = fc.streamDecode(packets(1))
    val decode2 = fc.streamDecode(packets(2))
    val decode3 = fc.streamDecode(packets(3))
    val decode4 = fc.streamDecode(ByteBuffer.allocate(0))

    decode0 shouldBe Seq.empty
    decode1 shouldBe Seq.empty
    decode2 shouldBe Seq.empty
    decode3 shouldBe Seq(subset(4, 16, sourceMessage))
    decode4 shouldBe Seq.empty
  }

  private def subset(start: Int, end: Int, b: ByteBuffer): ByteBuffer = {
    b.position(0)
    val bytes = NetUtils.toArray(b)
    val slice = bytes.slice(start, end)
    ByteBuffer.wrap(slice)
  }

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

  private def generateMessage(messageLength: Int): ByteBuffer = {
    val bb = ByteBuffer.allocate(4 + messageLength)
    bb.putInt(messageLength)
    bb.put(NetUtils.randomBytes(messageLength))
    bb.clear()
    bb
  }

  private def generateMessagePlus(messageLength: Int, garbageLength: Int): ByteBuffer = {
    val bb = ByteBuffer.allocate(4 + messageLength + 4 + garbageLength - 1)
    bb.putInt(messageLength)
    bb.put(NetUtils.randomBytes(messageLength))
    bb.putInt(garbageLength)
    bb.put(NetUtils.randomBytes(garbageLength - 1))
    bb.clear()
    bb
  }

  private def split(buffer: ByteBuffer, packetSize: Int): Seq[ByteBuffer] = {
    buffer.array().grouped(packetSize).map(chunk => ByteBuffer.wrap(chunk)).toSeq
  }
}
