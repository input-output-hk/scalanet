package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.Codec
import io.iohk.decco.auto._
import io.iohk.scalanet.codec.CodecTestUtils.split
import monix.reactive.Observable
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.codec.FramingCodec.State.LengthExpected
import monix.execution.Scheduler.Implicits.global

import scala.util.Random

class FramingCodecSpec extends FlatSpec {

  behavior of "FramingCodec"

  it should "not reserve a buffer for a length field greater than max length" in {
    val maxBufferLength = 1
    val fc = new FramingCodec(Codec[String], maxBufferLength)
    val sourceMessage = lengthFieldSection(maxBufferLength + 1)

    a[IllegalArgumentException] should be thrownBy fc.streamDecode(sourceMessage)

    fc.length shouldBe 0
    fc.db.capacity() shouldBe 0
    fc.state shouldBe LengthExpected
    fc.nlb.position shouldBe 0
  }

  private def lengthFieldSection(length: Int): ByteBuffer = {
    val b = ByteBuffer.allocate(4)
    b.putInt(length)
    b.position(0)
    b
  }

  it should "handle a message split over several packets" in {
    val fc = new FramingCodec(Codec[String])
    val sourceMessage = Random.nextString(12)
    val packets: Seq[ByteBuffer] = split(fc.encode(sourceMessage), 4)

    packets.flatMap(packet => fc.streamDecode(packet)) shouldBe Seq(sourceMessage)
  }

  it should "handle message decoding via explicit monix lifting" in {
    val fc = new FramingCodec(Codec[String])
    val sourceMessage = Random.nextString(12)
    val packets: Seq[ByteBuffer] = split(fc.encode(sourceMessage), 4)
    val sourceObservable = Observable.fromIterable(packets)

    sourceObservable.liftByOperator(fc.monixOperator).headL.evaluated shouldBe sourceMessage
  }

  it should "handle message decoding via implicit monix lifting" in {
    import StreamCodec._
    implicit val fc = new FramingCodec(Codec[String])
    val sourceMessage = Random.nextString(12)
    val packets: Seq[ByteBuffer] = split(fc.encode(sourceMessage), 4)
    val sourceObservable = Observable.fromIterable(packets)

    sourceObservable.voodooLift.headL.evaluated shouldBe sourceMessage
  }

  it should "decode like a normal codec for a complete message" in {
    val fc = new FramingCodec(Codec[String])
    val sourceMessage = Random.nextString(12)

    fc.decode(fc.encode(sourceMessage)) shouldBe Right(sourceMessage)
  }
}
