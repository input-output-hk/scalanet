package io.iohk.scalanet.kademlia

import cats.Order
import io.iohk.scalanet.kademlia.KRouter.NodeRecord
import scodec.bits.BitVector

class XorOrdering(val base: BitVector) extends Ordering[BitVector] {

  override def compare(lhs: BitVector, rhs: BitVector): Int = {
    if (lhs.length != base.length || rhs.length != base.length)
      throw new IllegalArgumentException(
        s"Unmatched bit lengths for bit vectors in XorOrdering. (base, lhs, rhs) = ($base, $lhs, $rhs)"
      )
    val lb = Xor.d(lhs, base)
    val rb = Xor.d(rhs, base)
    if (lb < rb)
      -1
    else if (lb > rb)
      1
    else
      0
  }
}

object XorOrdering {
  // Ordering for a SortedSet needs to be unique. In practice it shouldn't
  // matter since all keys are unique, therefore they all have a different
  // distance, but in pathological tests it's not intuitive that sets of
  // different nodes with the same ID but different attributes disappear
  // from the set.
  def apply[T](base: BitVector)(f: T => BitVector): Ordering[T] = {
    val xorOrdering = new XorOrdering(base)
    val tupleOrdering = Ordering.Tuple2(xorOrdering, Ordering.Int)
    Ordering.by[T, (BitVector, Int)] { x =>
      f(x) -> x.hashCode
    }(tupleOrdering)
  }
}

object XorNodeOrdering {
  def apply[A](base: BitVector): Ordering[NodeRecord[A]] =
    XorOrdering[NodeRecord[A]](base)(_.id)
}

class XorNodeOrder[A](val base: BitVector) extends Order[NodeRecord[A]] {
  val xorNodeOrdering = XorNodeOrdering[A](base)

  override def compare(lhs: NodeRecord[A], rhs: NodeRecord[A]): Int =
    xorNodeOrdering.compare(lhs, rhs)
}
