package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import io.iohk.decco.Codec.{DecodeResult, Failure}
import io.iohk.decco.CodecContract
import io.iohk.decco.auto.instances.NativeInstances

import scala.annotation.tailrec

class SeqCodecContract[A](codecContract: CodecContract[A]) extends CodecContract[Seq[A]]{

  override def size(t: Seq[A]): Int = t.foldLeft(4)((rec,x) => codecContract.size(x) + rec)

  override def encodeImpl(t: Seq[A], start: Int, destination: ByteBuffer): Unit = {
    NativeInstances.IntCodec.encodeImpl(t.size,start,destination)
    var ind = start + 4
    for(el <- t ){
      codecContract.encodeImpl(el,ind,destination)
      ind = ind + codecContract.size(el)
    }
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[Seq[A]]] = {
    NativeInstances.IntCodec.decodeImpl(start,source) match {
      case Left(f) => Left(f)
      case Right(_dec) => {
        @tailrec
        def aux(pos:Int,number:Int,acum:List[A]): Either[Failure, DecodeResult[List[A]]] = {
          if(number>=_dec.decoded) Right(new DecodeResult[List[A]](acum.reverse,pos))
          else{
            val dec = codecContract.decodeImpl(pos,source)
            dec match {
              case Left(f) => Left(f)
              case Right(v) => aux(v.nextIndex,number+1,v.decoded :: acum)
            }
          }
        }
        aux(_dec.nextIndex,0,Nil)
      }
    }
  }
}