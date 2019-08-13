package io.iohk.scalanet.peergroup.kademlia

import scodec.bits.BitVector

object Xor {

  def d(a: BitVector, b: BitVector): BigInt = {
    assert(a.length == b.length)
    BigInt((a xor b).toBin, 2)
  }
}
