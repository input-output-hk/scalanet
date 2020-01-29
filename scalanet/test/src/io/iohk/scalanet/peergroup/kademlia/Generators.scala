package io.iohk.scalanet.peergroup.kademlia

import java.security.SecureRandom
import java.security.spec.ECPrivateKeySpec
import java.util.UUID

import io.iohk.scalanet.codec.{StreamCodecFromContract, StringCodecContract}
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import scodec.bits.BitVector

import scala.collection.mutable.ListBuffer
import scala.util.Random
import io.iohk.scalanet.crypto
import org.spongycastle.crypto.params.{ECPrivateKeyParameters, ECPublicKeyParameters}

object Generators {

  val random = new SecureRandom()

  val defaultBitLength = 264

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

  def genBitVectorTripsExhaustive(
      bitLength: Int
  ): List[(BitVector, BitVector, BitVector)] = {
    for {
      x <- genBitVectorExhaustive(bitLength)
      y <- genBitVectorExhaustive(bitLength)
      z <- genBitVectorExhaustive(bitLength)
    } yield (x, y, z)
  }

  def aRandomBitVector(bitLength: Int = defaultBitLength): BitVector =
    BitVector.bits(Range(0, bitLength).map(_ => Random.nextBoolean()))

  def aRandomNodeRecord(
      bitLength: Int = defaultBitLength,keyPair:Option[(ECPrivateKeyParameters,ECPublicKeyParameters)] = None
  ): NodeRecord[String] = {
    val pair = if(keyPair.isEmpty) crypto.generateKeyPair(random) else keyPair.get
    NodeRecord.create[String](
      id = BitVector(crypto.encodeKey(pair._2)),
      routingAddress = Random.alphanumeric.take(4).mkString,
      messagingAddress = Random.alphanumeric.take(4).mkString,
      sec_number = random.nextLong(),
      key = pair._1
    )(new StreamCodecFromContract[String](StringCodecContract))
  }
}
