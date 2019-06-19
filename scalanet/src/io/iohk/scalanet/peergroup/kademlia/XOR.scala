package io.iohk.scalanet.peergroup.kademlia

object XOR {

  def d(a: Array[Byte], b: Array[Byte]): Int = {
    assert(a.length == b.length)
    a.length * 8 - commonPrefixCount(a, b)
  }

  private def commonPrefixCount(a: Array[Byte], b: Array[Byte]): Int = {

    @annotation.tailrec
    def loop(count: Int): Int = {

      if (count != a.length) {

        val xorAB: Byte = xor(a(count), b(count))

        @annotation.tailrec
        def loopInner(bit: Int): Int = {
          if (bit < 8 && !testBit(xorAB, bit))
            loopInner(bit + 1)
          else
            bit
        }

        val remainderBits = loopInner(0)

        if (remainderBits == 8)
          loop(count + 1)
        else
          count * 8 + remainderBits
      } else {
        count * 8
      }

    }

    loop(0)
  }

  private def xor(a: Byte, b: Byte): Byte = (a ^ b).asInstanceOf[Byte]

  private def testBit(value: Byte, bitNumber: Int): Boolean = ((value >>> bitNumber) & 1) != 0

}
