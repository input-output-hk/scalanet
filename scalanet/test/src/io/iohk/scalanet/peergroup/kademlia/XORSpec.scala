package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.XOR.d

import org.scalacheck.Gen
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.prop.GeneratorDrivenPropertyChecks._

class XORSpec extends FlatSpec {

  val keys: Gen[BigInt] = Gen.listOfN(16, Gen.posNum[Byte]).map(_.toArray).map(BigInt(_))

  val keyPairs: Gen[(BigInt, BigInt)] = for {
    k1 <- keys
    k2 <- keys
  } yield (k1, k2)

  val keyTriples: Gen[(BigInt, BigInt, BigInt)] = for {
    k1 <- keys
    k2 <- keys
    k3 <- keys
  } yield (k1, k2, k3)

  it should "satisfy d(x,x) = 0" in {
    forAll(keys) { x =>
      d(x, x) shouldBe 0
    }
  }

  it should "satisfy d(x,y) > 0 when x != y" in {
    forAll(keyPairs) {
      case (x, y) =>
        if (x != y)
          d(x, y) > 0 shouldBe true
    }
  }

  it should "satisfy the symmetry condition" in {
    forAll(keyPairs) {
      case (x, y) =>
        d(x, y) shouldBe d(y, x)
    }
  }

  it should "satisfy the triangle equality" in {
    forAll(keyTriples) {
      case (x, y, z) =>
        d(x, z) <= d(x, y) + d(y, z) shouldBe true
    }
  }
}
