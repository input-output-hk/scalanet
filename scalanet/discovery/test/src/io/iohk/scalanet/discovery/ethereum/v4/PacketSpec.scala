package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.crypto.Signature
import io.iohk.scalanet.discovery.hash.Hash
import org.scalatest._
import scodec.{Attempt, Codec}
import scodec.bits.BitVector
import scala.util.Random

class PacketSpec extends FlatSpec with Matchers {

  val MaxPacketBytesSize = Packet.MaxPacketBitsSize / 8
  val MacBytesSize = Packet.MacBitsSize / 8
  val SigBytesSize = Packet.SigBitsSize / 8
  val MaxDataBytesSize = MaxPacketBytesSize - MacBytesSize - SigBytesSize

  def nBytesAsBits(n: Int): BitVector = {
    val bytes = Array.ofDim[Byte](n)
    Random.nextBytes(bytes)
    BitVector(bytes)
  }

  def randomPacket(
      hashBytesSize: Int = MacBytesSize,
      sigBytesSize: Int = SigBytesSize,
      dataBytesSize: Int = MaxDataBytesSize
  ): Packet =
    Packet(
      hash = Hash(nBytesAsBits(hashBytesSize)),
      signature = Signature(nBytesAsBits(sigBytesSize)),
      data = nBytesAsBits(dataBytesSize)
    )

  def expectFailure(msg: String)(attempt: Attempt[_]) = {
    attempt match {
      case Attempt.Successful(_) => fail(s"Expected to fail with $msg; got success.")
      case Attempt.Failure(err) => err.messageWithContext shouldBe msg
    }
  }

  behavior of "encode"

  it should "succeed on a random packet within size limits" in {
    Codec.encode(randomPacket()).isSuccessful shouldBe true
  }

  it should "fail if data exceeds the maximum size" in {
    expectFailure("Encoded packet exceeded maximum size.") {
      Codec.encode(randomPacket(dataBytesSize = MaxDataBytesSize + 1))
    }
  }

  it should "fail if the hash has wrong size" in {
    expectFailure("Unexpected MAC size.") {
      Codec.encode(randomPacket(hashBytesSize = MacBytesSize * 2))
    }
  }

  it should "fail if the signature has wrong size" in {
    expectFailure("Unexpected signature size.") {
      Codec.encode(randomPacket(sigBytesSize = SigBytesSize - 1))
    }
  }

  behavior of "decode"

  it should "succeed with a packet size within the allowed maximum" in {
    Codec.decode[Packet](nBytesAsBits(MaxPacketBytesSize)).isSuccessful shouldBe true
  }

  it should "fail if the data exceeds the maximum size" in {
    expectFailure("Packet to decode exceeds maximum size.") {
      Codec.decode[Packet](nBytesAsBits(MaxPacketBytesSize + 1))
    }
  }
  it should "fail if there's less data than the hash size" in {
    expectFailure(
      s"MAC: cannot acquire ${Packet.MacBitsSize} bits from a vector that contains ${Packet.MacBitsSize - 8} bits"
    ) {
      Codec.decode[Packet](nBytesAsBits(MacBytesSize - 1))
    }
  }

  it should "fail if there's less data than the signature size" in {
    expectFailure(
      s"Signature: cannot acquire ${Packet.SigBitsSize} bits from a vector that contains ${Packet.SigBitsSize - 8} bits"
    ) {
      Codec.decode[Packet](nBytesAsBits(MacBytesSize + SigBytesSize - 1))
    }
  }

  behavior of "unpack"
  it should "fail if the hash is incorrect" in (pending)
  it should "fail if the signature is incorrect" in (pending)

  behavior of "pack"
  it should "calculate the signature based on the data" in (pending)
  it should "calculate the hash based on the signature and the data" in (pending)
}
