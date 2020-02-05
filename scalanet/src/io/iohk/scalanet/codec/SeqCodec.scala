package io.iohk.scalanet.codec

import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult, SizeBound}

import scala.annotation.tailrec

class SeqCodec[A](implicit subcodec:Codec[A], intCodec:Codec[Int]) extends Codec[Seq[A]] {
  override def encode(value: Seq[A]): Attempt[BitVector] = {
    val lengthEncoder = intCodec.encode(value.size)
    if(lengthEncoder.isFailure) scodec.Attempt.Failure(lengthEncoder.toEither.left.get)
    else {
      val base: scodec.Attempt[BitVector] = Attempt.Successful(lengthEncoder.toOption.get)
      value.foldLeft(base)((rec, x) => {
        val enc = subcodec.encode(x)
        if (enc.isFailure) scodec.Attempt.Failure(enc.toEither.left.get)
        else Attempt.Successful(rec.toOption.get ++ enc.toOption.get)
      })
    }
  }

  override def sizeBound: SizeBound = SizeBound(intCodec.sizeBound.lowerBound,{
    if(subcodec.sizeBound.upperBound.isEmpty || intCodec.sizeBound.upperBound.isEmpty) None
    else Some(intCodec.sizeBound.upperBound.get + subcodec.sizeBound.upperBound.get * Int.MaxValue)
  })

  override def decode(bits: BitVector): Attempt[DecodeResult[Seq[A]]] = {
    val length = intCodec.decode(bits)
    if(length.isFailure) scodec.Attempt.Failure(length.toEither.left.get)
    else{
      @tailrec
      def aux(elems:Int,res:List[A],b:BitVector):scodec.Attempt[DecodeResult[List[A]]] = {
        if(elems==length.toOption.get.value) scodec.Attempt.Successful(DecodeResult[List[A]](res,b))
        else {
          val dec = subcodec.decode(b)
          if (dec.isFailure) scodec.Attempt.Failure(dec.toEither.left.get)
          else aux(elems + 1, res ++ List(dec.toOption.get.value), dec.toOption.get.remainder)
        }
      }
      aux(0,Nil,length.toOption.get.remainder)
    }
  }
}