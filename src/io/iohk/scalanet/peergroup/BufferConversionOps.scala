package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

trait BufferConversionOps {

  implicit class ByteBufferConversionOps(val byteBuffer: ByteBuffer) {
    def toArray: Array[Byte] = {
      if (byteBuffer.hasArray)
        byteBuffer.array
      else {
        (byteBuffer: java.nio.Buffer).position(0)
        val arr = new Array[Byte](byteBuffer.remaining())
        byteBuffer.get(arr)
        arr
      }
    }
  }
}

object BufferConversionOps extends BufferConversionOps
