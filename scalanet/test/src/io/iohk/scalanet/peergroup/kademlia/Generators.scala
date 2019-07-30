package io.iohk.scalanet.peergroup.kademlia

import java.net.InetAddress

import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.NetUtils.aRandomAddress
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import scodec.bits.BitVector

import scala.collection.mutable.ListBuffer
import scala.util.Random

object Generators {

  val defaultBitLength = 16

  def genBitVector(bitLength: Int = defaultBitLength): Gen[BitVector] =
    for {
      bools <- Gen.listOfN(bitLength, arbitrary[Boolean])
    } yield BitVector.bits(bools)

  def genBitVectorPairs(
      bitLength: Int = defaultBitLength
  ): Gen[(BitVector, BitVector)] =
    for {
      v1 <- genBitVector(bitLength)
      v2 <- genBitVector(bitLength)
    } yield (v1, v2)

  def genBitVectorTrips(
      bitLength: Int = defaultBitLength
  ): Gen[(BitVector, BitVector, BitVector)] =
    for {
      v1 <- genBitVector(bitLength)
      v2 <- genBitVector(bitLength)
      v3 <- genBitVector(bitLength)
    } yield (v1, v2, v3)

  def genBitVectorExhaustive(
      bitLength: Int = defaultBitLength
  ): List[BitVector] = {
    def loop(acc: ListBuffer[BitVector], b: BitVector, i: Int, n: Int): Unit = {
      if (i == n) {
        acc.append(b)
      } else {
        loop(acc, b.clear(i), i + 1, n)
        loop(acc, b.set(i), i + 1, n)
      }
    }

    val l = ListBuffer[BitVector]()
    loop(l, BitVector.low(bitLength), 0, bitLength)
    l.toList
  }

  def aRandomBitVector(bitLength: Int = defaultBitLength): BitVector =
    BitVector.bits(Range(0, bitLength).map(_ => Random.nextBoolean()))

  def aRandomNodeRecord(bitLength: Int = defaultBitLength): NodeRecord = {
    NodeRecord(
      id = aRandomBitVector(bitLength),
      ip = randomNonlocalAddress,
      tcp = aRandomAddress().getPort,
      udp = aRandomAddress().getPort
    )
  }

  def randomNonlocalAddress: InetAddress = {
    InetAddress.getByAddress(NetUtils.randomBytes(4))
  }
}
