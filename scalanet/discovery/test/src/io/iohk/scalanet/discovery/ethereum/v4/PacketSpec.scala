package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.crypto.{Signature, PrivateKey, PublicKey}
import io.iohk.scalanet.discovery.hash.{Hash, Keccak256}
import io.iohk.scalanet.discovery.ethereum.codecs.DefaultCodecs
import io.iohk.scalanet.discovery.ethereum.v4.mocks.MockSigAlg
import org.scalatest._
import scodec.{Attempt, Codec, Err}
import scodec.bits.BitVector
import scala.util.Random

class PacketSpec extends FlatSpec with Matchers {

  import DefaultCodecs._
  implicit val sigalg = new MockSigAlg()

  implicit val packetCodec = Packet.packetCodec(allowDecodeOverMaxPacketSize = false)

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
    expectFailure("Unexpected hash size.") {
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

  it should "optionally allow the data to exceed the maximum size" in {
    val permissiblePacketCodec: Codec[Packet] = Packet.packetCodec(allowDecodeOverMaxPacketSize = true)
    Codec.decode[Packet](nBytesAsBits(MaxPacketBytesSize * 2))(permissiblePacketCodec).isSuccessful shouldBe true
  }

  it should "fail if there's less data than the hash size" in {
    expectFailure(
      s"Hash: cannot acquire ${Packet.MacBitsSize} bits from a vector that contains ${Packet.MacBitsSize - 8} bits"
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

  trait PackFixture {
    val payload = Payload.FindNode(
      target = PublicKey(nBytesAsBits(sigalg.PublicKeyBytesSize)),
      expiration = System.currentTimeMillis
    )
    val privateKey = PrivateKey(nBytesAsBits(sigalg.PrivateKeyBytesSize))
    val publicKey = PublicKey(privateKey) // This is how the MockSignature will recover it.
    val packet = Packet.pack(payload, privateKey).require
  }

  behavior of "pack"

  it should "serialize the payload into the data" in new PackFixture {
    packet.data shouldBe Codec.encode[Payload](payload).require
    Codec[Payload].decodeValue(packet.data).require shouldBe payload
  }

  it should "calculate the signature based on the data" in new PackFixture {
    packet.signature shouldBe sigalg.sign(privateKey, packet.data)
  }

  it should "calculate the hash based on the signature and the data" in new PackFixture {
    packet.hash shouldBe Keccak256(packet.signature ++ packet.data)
  }

  behavior of "unpack"

  it should "deserialize the data into the payload" in new PackFixture {
    Packet.unpack(packet).require._1 shouldBe payload
  }

  it should "recover the public key" in new PackFixture {
    Packet.unpack(packet).require._2 shouldBe publicKey
  }

  it should "fail if the hash is incorrect" in new PackFixture {
    val corrupt = packet.copy(hash = Hash(nBytesAsBits(32)))

    expectFailure("Invalid hash.") {
      Packet.unpack(corrupt)
    }
  }

  it should "fail if the signature is incorrect" in new PackFixture {
    implicit val sigalg = new MockSigAlg {
      override def recoverPublicKey(signature: Signature, data: BitVector): Attempt[PublicKey] =
        Attempt.failure(Err("Invalid signature."))
    }
    val randomSig = Signature(nBytesAsBits(32))
    val corrupt = packet.copy(signature = randomSig, hash = Keccak256(randomSig ++ packet.data))

    expectFailure("Invalid signature.") {
      Packet.unpack(corrupt)
    }
  }
}
