package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.XOR._

import org.scalacheck.Gen
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.prop.GeneratorDrivenPropertyChecks._

class XORSpec extends FlatSpec {

  val keys: Gen[Array[Byte]] = Gen.listOfN(8, Gen.posNum[Byte]).map(_.toArray)

  val keyPairs: Gen[(Array[Byte], Array[Byte])] = for {
    k1 <- keys
    k2 <- keys
  } yield (k1, k2)

  val keyTriples: Gen[(Array[Byte], Array[Byte], Array[Byte])] = for {
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
        if (!(x sameElements y))
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

  it should "provide the correct maximal distance" in {
    val zero = Array.fill(8)(0.toByte)
    val max = Array.fill(8)(255.toByte)

    d(zero, max) shouldBe 64
  }
}
