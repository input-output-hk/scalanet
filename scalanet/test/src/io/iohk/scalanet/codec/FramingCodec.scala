package io.iohk.scalanet.codec

import monix.execution.atomic.AtomicAny
import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}
import scodec.codecs._

class FramingCodec[T](val messageCodec: Codec[T], maxMessageLength: Int = 32) extends StreamCodec[T] {

  private val longCodec = ulong(maxMessageLength)

  private val codec = variableSizeBitsLong(longCodec, messageCodec)

  private val atomicBuffer = AtomicAny[BitVector](BitVector.empty)

  override def streamDecode(newBits: BitVector): Seq[T] = {
    val result = atomicBuffer.transformAndExtract { state =>
      val newBuffer = state ++ newBits
      val (resultingBuffer, decodedMessages) = processBuffer(newBuffer, codec)
      (decodedMessages, resultingBuffer)
    }
    result
  }

  override def cleanSlate: StreamCodec[T] = new FramingCodec[T](messageCodec, maxMessageLength)

  override def encode(value: T): Attempt[BitVector] = codec.encode(value)

  override def decode(bits: BitVector): Attempt[DecodeResult[T]] = {
    codec.decode(bits)
  }

  override def sizeBound: SizeBound = codec.sizeBound

  private def processBuffer[M](buf: BitVector, c: Codec[M]): (BitVector, Seq[M]) = {
    @scala.annotation.tailrec
    def go(left: BitVector, processed: Seq[M]): (BitVector, Seq[M]) = {
      if (left.isEmpty) {
        (left, processed)
      } else {
        c.decode(left) match {
          case Attempt.Successful(v) =>
            go(v.remainder, processed :+ v.value)
          case Attempt.Failure(x) =>
            x match {
              case Err.InsufficientBits(needed, have, _)
                  if (isReadingCorrectSize(left, have, needed) || isReadingCorrectValue(left, have, needed)) =>
                (left, processed)

              case e =>
                // Unexpected error, it can  be that messages were in wrong format, or we got some malicious peers.
                // Reset the buffer
                (BitVector.empty, processed)
            }
        }
      }
    }
    go(buf, Seq())
  }

  private def isReadingCorrectSize(bufferRead: BitVector, have: Long, needed: Long) = {
    (bufferRead.size == have) && (needed == maxMessageLength)
  }

  private def isReadingCorrectValue(bufferRead: BitVector, have: Long, needed: Long) = {
    val l = bufferRead.size - maxMessageLength
    (l == have) && have < needed
  }
}
