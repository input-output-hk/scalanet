package io.iohk.scalanet.codec

import monix.execution.atomic.AtomicAny
import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound}
import scodec.codecs.{ulong, variableSizeBitsLong}

// NB: Uses scodec specific encoding to delineate frames.
class FramingCodec[T](val messageCodec: Codec[T], lengthFieldLength: Int = 32) extends StreamCodec[T] {

  private val lengthFieldCodec = ulong(lengthFieldLength)

  private val codec = variableSizeBitsLong(lengthFieldCodec, messageCodec)

  private val atomicBuffer = AtomicAny[BitVector](BitVector.empty)

  override def streamDecode(newBits: BitVector): Either[String, Seq[T]] = {
    val stateFn: BitVector => (Either[String, Seq[T]], BitVector) = state => {
      val newBuffer = state ++ newBits
      val (resultingBuffer, decodedMessages) = processBuffer(newBuffer, codec)
      (decodedMessages, resultingBuffer)
    }
    val result = atomicBuffer.transformAndExtract(s => stateFn(s))
    result
  }

  override def cleanSlate: StreamCodec[T] = new FramingCodec[T](messageCodec, lengthFieldLength)

  override def encode(value: T): Attempt[BitVector] = codec.encode(value)

  override def decode(bits: BitVector): Attempt[DecodeResult[T]] = {
    codec.decode(bits)
  }

  override def sizeBound: SizeBound = codec.sizeBound

  private def processBuffer[M](buf: BitVector, c: Codec[M]): (BitVector, Either[String, Seq[M]]) = {
    @scala.annotation.tailrec
    def go(left: BitVector, processed: Seq[M]): (BitVector, Either[String, Seq[M]]) = {
      if (left.isEmpty) {
        (left, Right(processed))
      } else {
        c.decode(left) match {
          case Attempt.Successful(v) =>
            go(v.remainder, processed :+ v.value)
          case Attempt.Failure(x) =>
            x match {
              case Err.InsufficientBits(needed, have, _)
                  if (isReadingCorrectSize(left, have, needed) || isReadingCorrectValue(left, have, needed)) =>
                (left, Right(processed))

              case e =>
                // Unexpected error, it can  be that messages were in wrong format, or we got some malicious peers.
                // Reset the buffer
                (BitVector.empty, Left(e.message))
            }
        }
      }
    }
    go(buf, Seq())
  }

  private def isReadingCorrectSize(bufferRead: BitVector, have: Long, needed: Long) = {
    (bufferRead.size == have) && (needed == lengthFieldLength)
  }

  private def isReadingCorrectValue(bufferRead: BitVector, have: Long, needed: Long) = {
    val l = bufferRead.size - lengthFieldLength
    (l == have) && have < needed
  }
}
