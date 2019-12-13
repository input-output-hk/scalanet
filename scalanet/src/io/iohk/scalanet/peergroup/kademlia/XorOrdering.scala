package io.iohk.scalanet.peergroup.kademlia

import cats.Order
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
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

class XorNodeOrdering[A](val base: BitVector) extends Ordering[NodeRecord[A]] {

  override def compare(lhs: NodeRecord[A], rhs: NodeRecord[A]): Int = {
    if (lhs.id.length != base.length || rhs.id.length != base.length)
      throw new IllegalArgumentException(
        s"Unmatched bit lengths for bit vectors in XorOrdering. (base, lhs, rhs) = ($base, $lhs, $rhs)"
      )
    val lb = Xor.d(lhs.id, base)
    val rb = Xor.d(rhs.id, base)
    if (lb < rb)
      -1
    else if (lb > rb)
      1
    else
      0
  }
}

class XorOrder[A](val base: BitVector) extends Order[NodeRecord[A]] {
  val xorNodeOrder = new XorNodeOrdering[A](base)
  override def compare(lhs: NodeRecord[A], rhs: NodeRecord[A]): Int = xorNodeOrder.compare(lhs, rhs)
}
