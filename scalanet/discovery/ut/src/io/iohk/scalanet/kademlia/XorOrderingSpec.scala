package io.iohk.scalanet.kademlia

import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import scodec.bits.BitVector
import io.iohk.scalanet.kademlia.KRouter.NodeRecord
import scala.collection.SortedSet

class XorOrderingSpec extends FlatSpec {

  val id0 = BitVector.fromValidBin("0000")

  val ids: List[BitVector] = Generators.genBitVectorExhaustive(4)

  "NodeIdOrdering" should "return correct comparable values" in {
    ids.foreach { base =>
      val ordering = new XorOrdering(base)
      ids.foreach { a =>
        ids.foreach { b =>
          val result = ordering.compare(a, b)
          if (Xor.d(a, base) < Xor.d(b, base))
            result shouldBe -1
          else if (Xor.d(a, base) > Xor.d(b, base))
            result shouldBe 1
          else
            result shouldBe 0
        }
      }
    }
  }

  it should "throw if the lhs argument does not match the base bit length" in {
    val ordering = new XorOrdering(id0)
    val lhs = BitVector.fromValidBin("0000000000000000")
    val rhs = BitVector.fromValidBin("0000")

    an[IllegalArgumentException] should be thrownBy ordering.compare(lhs, rhs)
  }

  it should "throw if the rhs argument does not match the base bit length" in {
    val ordering = new XorOrdering(id0)
    val lhs = BitVector.fromValidBin("0000")
    val rhs = BitVector.fromValidBin("0000000000000000")

    an[IllegalArgumentException] should be thrownBy ordering.compare(lhs, rhs)
  }

  "XorNodeOrdering" should "work with SortedSet" in {
    implicit val ordering = XorNodeOrdering[Int](id0)
    val node0 = NodeRecord[Int](BitVector.fromValidBin("0000"), 1, 2)
    val node1 = NodeRecord[Int](BitVector.fromValidBin("0000"), 3, 4)
    val nodes = SortedSet(node0, node1)
    nodes should have size 2
  }
}
