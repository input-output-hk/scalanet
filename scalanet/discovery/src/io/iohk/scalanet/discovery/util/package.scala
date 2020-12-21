package io.iohk.scalanet.discovery

import scodec.bits.ByteVector
import scala.math.Ordering._


package object util { 

  private class ByteIterableOrdering extends Ordering[Iterable[Byte]] {
    final def compare(as: Iterable[Byte], bs: Iterable[Byte]) = {
      val ai = as.iterator
      val bi = bs.iterator
      var result: Option[Int] = None
      while (ai.hasNext && bi.hasNext && result.isEmpty) { 
        val res = Byte.compare(ai.next(), bi.next())
        if (res != 0) result = Some(res)
      }
      result.getOrElse( Boolean.compare(ai.hasNext, bi.hasNext) )
    }
  }

  private implicit val byteIterableOrdering = new ByteIterableOrdering

  val byteVectorOrdering: Ordering[ByteVector] =
    Ordering.by[ByteVector, Iterable[Byte]](_.toIterable)

}
