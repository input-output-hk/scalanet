package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.Generators._
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.prop.GeneratorDrivenPropertyChecks._
import scodec.bits.BitVector

import scala.util.Random

class KBucketsSpec extends FlatSpec {

  behavior of "KBuckets"

  val kb = new KBuckets(aRandomBitVector(16))

  they should "retrieve any node added via put" in forAll(genBitVector(16)) { v =>
    kb.add(v)
    kb.contains(v) shouldBe true
  }

  they should "not retrieve any node removed via remove" in forAll(genBitVector(16)) { v =>
    kb.add(v)

    kb.remove(v)

    kb.contains(v) shouldBe false
  }

  they should "reject addition of nodeIds with inconsistent length" in {
    an[IllegalArgumentException] should be thrownBy kb.add(
      aRandomBitVector(bitLength = 24)
    )
  }

  they should "return the n closest nodes when N are available" in {

    val ids: Seq[BitVector] = genBitVectorExhaustive(3)
    val arbitraryId: BitVector = ids(Random.nextInt(ids.length))
    val kBuckets = new KBuckets(arbitraryId)

    val exptectedRecords =
      ids.sortBy(nodeId => Xor.d(nodeId, arbitraryId))

    ids.foreach(nodeId => kBuckets.add(nodeId))
    val closestNodes = kBuckets.closestNodes(arbitraryId, ids.length)

    closestNodes shouldBe exptectedRecords
  }
}