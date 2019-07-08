package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.Generators._
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.prop.GeneratorDrivenPropertyChecks._
import scodec.bits.BitVector

import scala.util.Random

class KBucketsSpec extends FlatSpec {

  behavior of "KBuckets"

  val kb = new KBuckets(aRandomBitVector())

  they should "retrieve the base node id" in {
    val id = aRandomBitVector()
    val kBuckets = new KBuckets(id)

    kBuckets.contains(id) shouldBe true
    kBuckets.closestNodes(id, Int.MaxValue) shouldBe List(id)
  }

  they should "retrieve any node added via put" in forAll(genBitVector()) { v =>
    kb.add(v)
    kb.contains(v) shouldBe true
  }

  they should "not retrieve any node removed via remove" in forAll(genBitVector()) { v =>
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

    val ids: Seq[BitVector] = genBitVectorExhaustive(4)
    val arbitraryId: BitVector = ids(Random.nextInt(ids.length))
    val kBuckets = new KBuckets(arbitraryId)

    val exptectedRecords =
      ids.sortBy(nodeId => Xor.d(nodeId, arbitraryId))

    ids.foreach(nodeId => kBuckets.add(nodeId))
    val closestNodes = kBuckets.closestNodes(arbitraryId, ids.length)

    closestNodes shouldBe exptectedRecords
  }

  they should "require the closest single node is the node itself" in {

    val ids: Seq[BitVector] = genBitVectorExhaustive(4)
    val arbitraryId: BitVector = ids(Random.nextInt(ids.length))
    val kBuckets = new KBuckets(arbitraryId)

    ids.foreach(nodeId => kBuckets.add(nodeId))

    ids.foreach { nodeId =>
      kBuckets.closestNodes(nodeId, 1) shouldBe List(nodeId)
    }
  }
}
