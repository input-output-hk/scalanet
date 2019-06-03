package io.iohk.scalanet.format

import java.nio.ByteBuffer

import io.iohk.scalanet.format.StreamDecoder1.State._
import io.iohk.scalanet.format.StreamDecoder1._
import io.netty.buffer.{CompositeByteBuf, Unpooled}

import scala.collection.mutable

class StreamDecoder1 {
  val cb: CompositeByteBuf = Unpooled.compositeBuffer()

  var state: State = LengthExpected
  var length: Int = 0

  val nlb = ByteBuffer.allocate(4)

  def fmfn(b: ByteBuffer): Seq[ByteBuffer] = {

    val s = mutable.ListBuffer[ByteBuffer]()
    while (b.remaining() > 0) {
      state match {

        case LengthExpected =>
          while (b.remaining() > 0 && nlb.position() < 4) {
            nlb.put(b.get())
          }
          if (nlb.position() == 4) {
//            nlb.position(0)
            length = nlb.getInt(0)
            nlb.clear()
            state = BytesExpected
          }

        //      else
        case BytesExpected =>

          // we expect a length field, N and there are 4 or more bytes remaining
          // we expect a length field, N and there are less than 4 bytes remaining

          // we expect N bytes and there are M = N remaining

          // we expect N bytes and there are M < N remaining

          // we expect N bytes and there are M > N remaining
      }
    }
    s
  }
}

object StreamDecoder1 {

  trait State

  object State {

    case object LengthExpected extends State

    case object BytesExpected extends State

  }

}