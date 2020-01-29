package io.iohk.scalanet.peergroup.kademlia

import java.nio.ByteBuffer

import io.iohk.decco.Codec
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.scalactic.Equivalence
import org.scalatest.FlatSpec
import scodec.bits.BitVector

import scala.util.Random

class BitVectorCodecSpec extends FlatSpec {

  import BitVectorCodec._
  import BitVectorCodecSpec._
  import io.iohk.decco.auto._

  implicit val arbitraryBitVector = Arbitrary(Generators.genBitVector())

  "BitVectorCodec" should "work" in testCodec[BitVector]
}

object BitVectorCodecSpec {
  import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
  import org.scalatest.EitherValues._
  import org.scalatest.Matchers._
  import org.scalatest.prop.GeneratorDrivenPropertyChecks._

  private def encodeDecodeTest[T](implicit codec: Codec[T], a: Arbitrary[T], eq: Equivalence[T]): Unit = {
    forAll(arbitrary[T]) { t =>
      val e = codec.encode(t)
      System.out.println(t)
      System.out.println(codec.decode(e).right.get)
      codec.decode(e).right.value should equal(t)
    }
  }

  private def variableLengthTest[T](implicit codec: Codec[T], a: Arbitrary[T], eq: Equivalence[T]): Unit = {
    forAll(arbitrary[T]) { t =>
      // create a buffer with one half full of real data
      // and the second half full of rubbish.
      // codecs should not be fooled by this.
      val b = codec.encode(t).toArray
      val newB = ByteBuffer
        .allocate(b.length * 2)
        .put(b)
        .put(randomBytes(b.length))
      (newB: java.nio.Buffer).position(0)

      codec.decode(newB).right.value shouldBe t
    }
  }

  private def testCodec[T: Codec: Arbitrary]: Unit = {
    encodeDecodeTest[T]
    variableLengthTest[T]
  }

  private def randomBytes(n: Int): Array[Byte] = {
    val a = new Array[Byte](n)
    Random.nextBytes(a)
    a
  }

  implicit class ByteBufferConversionOps(val byteBuffer: ByteBuffer) {
    def toArray: Array[Byte] = {
      if (byteBuffer.hasArray)
        byteBuffer.array
      else {
        (byteBuffer: java.nio.Buffer).position(0)
        val arr = new Array[Byte](byteBuffer.remaining())
        byteBuffer.get(arr)
        arr
      }
    }
  }

}
