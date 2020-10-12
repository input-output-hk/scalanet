package io.iohk.scalanet.discovery.ethereum.v4.mocks

import io.iohk.scalanet.discovery.crypto.{Signature, PublicKey, PrivateKey, SigAlg}
import scodec.bits.BitVector
import scodec.Attempt

class MockSigAlg extends SigAlg {
  override val name = "MockSignature"

  // For testing I'll use the same key for public and private,
  // so that I can recover the public key from the signature.
  override val PrivateKeyBytesSize = 65
  override val PublicKeyBytesSize = 65
  override val SignatureBytesSize = 65

  override def sign(privateKey: PrivateKey, data: BitVector): Signature =
    Signature(xor(privateKey, data))

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
