package io.iohk.scalanet.peergroup.kademlia

import scodec.bits.BitVector

object Xor {

  def d(a: BitVector, b: BitVector): BigInt = {
    assert(a.length == b.length)
    BigInt(1, alignRight(a xor b).toByteArray)
  }

  private def alignRight(b: BitVector): BitVector = {
    BitVector.low(roundUp(b.length) - b.length) ++ b
  }

  private def roundUp(i: Long): Long = {
    i + (8 - i % 8) % 8
  }
}
