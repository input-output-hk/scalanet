package io.iohk.scalanet.format

import java.nio.ByteBuffer

import io.iohk.scalanet.format.StreamDecoder1.State._
import io.iohk.scalanet.format.StreamDecoder1._

import scala.collection.mutable

class StreamDecoder1 {

  var state: State = LengthExpected
  var length: Int = 0
  var db: ByteBuffer = null

  val nlb = ByteBuffer.allocate(4)

  def streamDecode(b: ByteBuffer): Seq[ByteBuffer] = {

    val s = mutable.ListBuffer[ByteBuffer]()
    while (b.remaining() > 0) {
      state match {

        case LengthExpected =>
          while (b.remaining() > 0 && nlb.position() < 4) {
            nlb.put(b.get())
          }
          if (nlb.position() == 4) {
            length = nlb.getInt(0)
            nlb.clear()
            db = ByteBuffer.allocate(length)
            state = BytesExpected
          }

        case BytesExpected =>
          val remainingBytes = length - db.position()
          if (b.remaining() >= remainingBytes) {
            read(remainingBytes, b, db)
            db.position(0)
            s += db
            state = LengthExpected
          } else { // (b.remaining() < remainingBytes)
            read(b.remaining(), b, db)
          }
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

  private[StreamDecoder1] def read(n: Int, from: ByteBuffer, to: ByteBuffer): Unit = {
    var m: Int = 0
    while (m < n) {
      to.put(from.get())
      m += 1
    }
  }
}
