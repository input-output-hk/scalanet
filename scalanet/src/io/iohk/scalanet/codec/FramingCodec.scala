package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import io.iohk.decco.{BufferInstantiator, Codec}
import io.iohk.decco.Codec.{DecodeResult, Failure}
import io.iohk.scalanet.codec.FramingCodec.State._
import io.iohk.scalanet.codec.FramingCodec._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class FramingCodec[T](val messageCodec: Codec[T], val maxFrameLength: Int = Int.MaxValue) extends StreamCodec[T] {

  private var state: State = ReadingLength
  private var length: Int = 0
  private var db: ByteBuffer = ByteBuffer.allocate(0)
  private val nlb = ByteBuffer.allocate(4)
  private var skipped: Int = 0

  override def streamDecode[B](source: B)(implicit bi: BufferInstantiator[B]): Seq[T] = this.synchronized {
    val b = bi.asByteBuffer(source)
    val s = mutable.ListBuffer[T]()
    while (b.hasRemaining) {
      state match {
        case ReadingLength =>
          readLength(b)
        case ReadingBytes =>
          readBytes(b, s)
        case SkippingBytes =>
          skipBytes(b)
      }
    }
    s
  }

  override def size(t: T): Int = 4 + messageCodec.size(t)

  override def encodeImpl(t: T, start: Int, destination: ByteBuffer): Unit = {
    destination.putInt(start, destination.capacity() - 4)
    messageCodec.encodeImpl(t, 4, destination)
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[T]] = {
    messageCodec.decodeImpl(start + 4, source)
  }

  override def cleanSlate: FramingCodec[T] = new FramingCodec[T](messageCodec)

  private def readLength[B](b: ByteBuffer): Unit = {
    while (b.remaining() > 0 && nlb.position() < 4) {
      nlb.put(b.get())
    }
    if (nlb.position() == 4) {
      val l = nlb.getInt(0)
      if (l > maxFrameLength) {
        nlb.clear()
        length = l
        state = SkippingBytes
      } else {
        length = nlb.getInt(0)
        nlb.clear()
        db = ByteBuffer.allocate(length)
        state = ReadingBytes
      }
    }
  }

  private def readBytes[B](b: ByteBuffer, s: ListBuffer[T])(implicit bi: BufferInstantiator[B]): Unit = {
    val remainingBytes = length - db.position()
    if (b.remaining() >= remainingBytes) {
      readUnchecked(remainingBytes, b, db)
      db.position(0)
      messageCodec.decode(bi.asB(db)).foreach(message => s += message)
      state = ReadingLength
    } else { // (b.remaining() < remainingBytes)
      readUnchecked(b.remaining(), b, db)
    }
  }

  private def skipBytes[B](b: ByteBuffer): Unit = {
    val remainingBytes = length - skipped
    if (b.remaining() >= remainingBytes) {
      skipUnchecked(remainingBytes, b)
      skipped = 0
      state = ReadingLength
    } else {
      skipped += b.remaining()
      skipUnchecked(b.remaining(), b)
      assert(b.remaining() == 0, "didn't read the buffer")
    }
  }
}

object FramingCodec {

  trait State

  object State {
    case object ReadingLength extends State
    case object ReadingBytes extends State
    case object SkippingBytes extends State
  }

  private def readUnchecked(n: Int, from: ByteBuffer, to: ByteBuffer): Unit = {
    var m: Int = 0
    while (m < n) {
      to.put(from.get())
      m += 1
    }
  }

  private def skipUnchecked(n: Int, from: ByteBuffer): Unit =
    from.position(from.position() + n)
}
