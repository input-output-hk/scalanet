package io.iohk.scalanet.codec

import io.iohk.scalanet.NetUtils
import scodec.bits.BitVector

object CodecTestUtils {

  def split(buffer: BitVector, packetSize: Int): Seq[BitVector] = {
    buffer.grouped(packetSize).toList
  }

  def generateMessage(
      messageLength: Int,
      streamCodec: StreamCodec[String]
  ): BitVector = {
    streamCodec.encode(new String(NetUtils.randomBytes(messageLength))).require
  }

  def generateMessage(messageLength: Int): BitVector = {
    BitVector(NetUtils.randomBytes(messageLength))
  }
}
