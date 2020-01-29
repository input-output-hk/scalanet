package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import io.iohk.decco.Codec.Failure
import io.iohk.decco.{BufferInstantiator, Codec, CodecContract}

class CodecFromContract[A](codecContract: CodecContract[A]) extends Codec[A] {

  override def encode[B](t: A)(implicit bi: BufferInstantiator[B]): B = {
    val res = ByteBuffer.allocate(codecContract.size(t))
    codecContract.encodeImpl(t,0,res)
    bi.asB(res)
  }

  override def decode[B](start: Int, source: B)(implicit bi: BufferInstantiator[B]): Either[Failure, A] = {
    codecContract.decodeImpl(start,bi.asByteBuffer(source)) match{
      case Left(f) => Left(f)
      case Right(res) => Right(res.decoded)
    }
  }

  override def decode[B](source: B)(implicit bi: BufferInstantiator[B]): Either[Failure, A] = decode(0,source)
}
