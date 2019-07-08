package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.Generators._
import io.iohk.scalanet.peergroup.kademlia.Xor._
import org.scalacheck.Gen.posNum
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.prop.GeneratorDrivenPropertyChecks._
import scodec.bits.BitVector

class XorSpec extends FlatSpec {

  it should "satisfy d(x,x) = 0" in {
    forAll(genBitVector(8)) { x =>
      d(x, x) shouldBe 0
    }
  }

  it should "satisfy d(x,y) > 0 when x != y" in {
    forAll(genBitVectorPairs(8)) {
      case (x, y) =>
        if (x != y)
          d(x, y) > 0 shouldBe true
    }
  }

  it should "satisfy the symmetry condition" in {
    forAll(genBitVectorPairs(8)) {
      case (x, y) =>
        d(x, y) shouldBe d(y, x)
    }
  }

  it should "satisfy the triangle equality" in {
    forAll(genBitVectorTrips(8)) {
      case (x, y, z) =>
        d(x, z) <= d(x, y) + d(y, z) shouldBe true
    }
  }

  it should "provide the correct maximal distance" in forAll(posNum[Byte]) { bitCount =>
    val zero = BitVector.low(bitCount)
    val max = BitVector.high(bitCount)

    d(zero, max) shouldBe bitCount
  }
}
