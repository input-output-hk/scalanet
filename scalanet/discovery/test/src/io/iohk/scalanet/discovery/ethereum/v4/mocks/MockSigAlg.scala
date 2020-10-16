package io.iohk.scalanet.discovery.ethereum.v4.mocks

import io.iohk.scalanet.discovery.crypto.{Signature, PublicKey, PrivateKey, SigAlg}
import scodec.bits.BitVector
import scodec.Attempt
import scala.util.Random

class MockSigAlg extends SigAlg {
  override val name = "MockSignature"

  override val PrivateKeyBytesSize = 65
  override val PublicKeyBytesSize = 65
  override val SignatureBytesSize = 65

  // For testing I'll use the same key for public and private,
  // so that I can recover the public key from the signature.
  override def newKeyPair: (PublicKey, PrivateKey) = {
    val bytes = Array.ofDim[Byte](PrivateKeyBytesSize)
    Random.nextBytes(bytes)
    val privateKey = PrivateKey(BitVector(bytes))
    val publicKey = PublicKey(privateKey)
    publicKey -> privateKey
  }

  override def sign(privateKey: PrivateKey, data: BitVector): Signature =
    Signature(xor(privateKey, data))

  override def verify(publicKey: PublicKey, signature: Signature, data: BitVector): Boolean =
    publicKey == recoverPublicKey(signature, data).require

  override def recoverPublicKey(signature: Signature, data: BitVector): Attempt[PublicKey] = {
    Attempt.successful(PublicKey(xor(signature, data)))
  }

  // Using XOR twice recovers the original data.
  // Pad the data so we don't lose the key if the data is shorter.
  private def xor(key: BitVector, data: BitVector): BitVector = {
    val padded = if (data.length < key.length) data.padTo(key.length) else data
    (key ^ padded).take(key.length)
  }
}
