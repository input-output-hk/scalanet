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
    // Using XOR twice recovers the original data.
    Signature((privateKey ^ data).take(SignatureBytesSize * 8))

  override def recoverPublicKey(signature: Signature, data: BitVector): Attempt[PublicKey] =
    Attempt.successful(PublicKey((signature ^ data).take(PublicKeyBytesSize * 8)))
}
