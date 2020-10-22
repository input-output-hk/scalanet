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

  /** Create an ordering that uses the XOR distance as well as a unique
    * secondary index (based on the object hash) so values at the same
    * distance can still be distinguished from each other. This is required
    * for a SortedSet to work correctly, otherwise it just keeps one of the
    * values at any given distance.
    *
    * In practice it shouldn't matter since all keys are unique, therefore
    * they all have a different distance, but in pathological tests it's not
    * intuitive that sets of different nodes with the same ID but different
    * attributes disappear from the set.
    *
    * It could also be an attack vector if a malicious node deliberately
    * fabricates nodes that look like the target but with different ports
    * for example, so the SortedSet would keep a random instance.
    *
    * The method has a `B <: BitVector` generic parameter so the compiler
    * warns us if we're trying to compare different tagged types.
    */
  def apply[A, B <: BitVector](f: A => B)(base: B): Ordering[A] = {
    val xorOrdering = new XorOrdering(base)
    val tupleOrdering = Ordering.Tuple2(xorOrdering, Ordering.Int)
    Ordering.by[A, (BitVector, Int)] { x =>
      // Using hashCode to make them unique form each other within the same distance.
      f(x) -> x.hashCode
    }(tupleOrdering)
  }
}

object XorNodeOrdering {
  def apply[A](base: BitVector): Ordering[NodeRecord[A]] =
    XorOrdering[NodeRecord[A], BitVector](_.id)(base)
}

class XorNodeOrder[A](val base: BitVector) extends Order[NodeRecord[A]] {
  val xorNodeOrdering = XorNodeOrdering[A](base)

  override def compare(lhs: NodeRecord[A], rhs: NodeRecord[A]): Int =
    xorNodeOrdering.compare(lhs, rhs)
}
