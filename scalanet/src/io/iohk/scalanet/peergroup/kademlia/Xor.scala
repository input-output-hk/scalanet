package io.iohk.scalanet.peergroup.kademlia

import scodec.bits.BitVector

object Xor {

  def d(a: BitVector, b: BitVector): Int = {
    assert(a.length == b.length)
    a.length.toInt - leadingZeros(a.xor(b))
  }

  private def leadingZeros(b: BitVector): Int = {
    @annotation.tailrec
    def loop(count: Int): Int = {
      if (count != b.length && !b.get(count)) {
        loop(count + 1)
      } else {
        count
      }
    }
    loop(0)
  }
}
