package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import io.iohk.decco.Codec.{DecodeResult, Failure}
import io.iohk.decco.auto.ArrayCharCodec
import io.iohk.decco.{Codec, CodecContract}

object StringCodecContract extends CodecContract[String]{
  override def size(t: String): Int = ArrayCharCodec.size(t.toCharArray)

  override def encodeImpl(t: String, start: Int, destination: ByteBuffer): Unit = ArrayCharCodec.encodeImpl(t.toCharArray,start,destination)

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, Codec.DecodeResult[String]] = ArrayCharCodec.decodeImpl(start,source) match{
    case Left(f) => Left(f)
    case Right(res) => Right(new DecodeResult[String](new String(res.decoded),res.nextIndex))
  }
}
