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
import monix.execution.Scheduler.Implicits.global

import scala.util.Random

class FramingCodecSpec extends FlatSpec {

  behavior of "FramingCodec"

  it should "skip over length frames" in {
    val messageCodec = Codec[String]
    val sourceMessage1 = Random.nextString(4)
    val sourceMessage2 = Random.nextString(2)
    val maxFrameLength = messageCodec.encode(sourceMessage2).capacity() + 1

    val framingCodec = new FramingCodec(messageCodec, maxFrameLength)

    val frame1: ByteBuffer = framingCodec.encode(sourceMessage1)
    val frame2: ByteBuffer = framingCodec.encode(sourceMessage2)

    framingCodec.streamDecode(frame1) shouldBe Seq.empty
    framingCodec.streamDecode(frame2) shouldBe Seq(sourceMessage2)
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
