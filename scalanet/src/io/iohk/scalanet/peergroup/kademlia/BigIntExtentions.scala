package io.iohk.scalanet.peergroup.kademlia

object BigIntExtentions {

  implicit class BigIntMath(bi: BigInt) {
    private val LOG_2 = Math.log(2.0)
    private val MAX_DIGITS_2 = 977 // ~ MAX_DIGITS_10 * LN(10)/LN(2)

    def ln: Double = {
      if (bi.signum < 1) {
        if (bi.signum < 0) {
          Double.NaN
        } else {
          Double.NegativeInfinity
        }
      } else {
        val blex = bi.bitLength - MAX_DIGITS_2;
        val bi2 = if (blex > 0) {
          bi.bigInteger.shiftRight(blex)
        } else {
          bi
        }
        val res = Math.log(bi2.doubleValue())
        if (blex > 0) {
          res + blex * LOG_2
        } else {
          res
        }
      }
    }

    def log(base: Int): Double = {
      bi.ln / Math.log(base)
    }

    def log2: Double = {
      log(base = 2)
    }
  }
}
