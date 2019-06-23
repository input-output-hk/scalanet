package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import io.iohk.decco.BufferInstantiator
import io.iohk.scalanet.NetUtils

object CodecTestUtils {

  def split(buffer: ByteBuffer, packetSize: Int): Seq[ByteBuffer] = {
    buffer.array().grouped(packetSize).map(chunk => ByteBuffer.wrap(chunk)).toSeq
  }

  def subset(start: Int, end: Int, b: ByteBuffer): ByteBuffer = {
    b.position(0)
    val bytes = NetUtils.toArray(b)
    val slice = bytes.slice(start, end)
    ByteBuffer.wrap(slice)
  }

  def generateMessage(
      messageLength: Int,
      streamCodec: StreamCodec[String],
      bi: BufferInstantiator[ByteBuffer]
  ): ByteBuffer = {
    streamCodec.encode[ByteBuffer](new String(NetUtils.randomBytes(messageLength)))(bi)
  }

  def generateMessage(messageLength: Int): ByteBuffer = {
    val bb = ByteBuffer.allocate(4 + messageLength)
    bb.putInt(messageLength)
    bb.put(NetUtils.randomBytes(messageLength))
    bb.clear()
    bb
  }
}
