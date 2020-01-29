package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import io.iohk.decco.Codec.{DecodeResult, Failure}
import io.iohk.decco.CodecContract

class EitherCodecContract[A,B](a:CodecContract[A],b:CodecContract[B]) extends CodecContract[Either[A,B]]{
  override def size(t: Either[A, B]): Int = t match{
    case Left(elemA) => a.size(elemA) + 1
    case Right(elemB) => b.size(elemB) + 1
  }

  override def encodeImpl(t: Either[A, B], start: Int, destination: ByteBuffer): Unit = t match{
    case Left(elemA) =>{
      destination.put(start,0)
      a.encodeImpl(elemA,start+1,destination)
    }
    case Right(elemB) => {
      destination.put(start,1)
      b.encodeImpl(elemB,start+1,destination)
    }
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[Either[A, B]]] = {
    if(source.get(start)==0){
      val rec = a.decodeImpl(start+1,source)
      rec match {
        case Left(f) => Left(f)
        case Right(dec) => Right( new DecodeResult[Either[A, B]](Left(dec.decoded),dec.nextIndex))
      }
    }else if(source.get(start)==1){
      val rec = b.decodeImpl(start+1,source)
      rec match {
        case Left(f) => Left(f)
        case Right(dec) => Right( new DecodeResult[Either[A, B]](Right(dec.decoded),dec.nextIndex))
      }
    }else{
      Left(Failure)
    }
  }
}