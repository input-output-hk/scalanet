package io.iohk.scalanet.peergroup.kademlia

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
