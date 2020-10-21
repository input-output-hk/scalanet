package io.iohk.scalanet.discovery.ethereum.v4.mocks

import io.iohk.scalanet.discovery.crypto.{Signature, PublicKey, PrivateKey, SigAlg}
import scodec.bits.BitVector
import scodec.Attempt
import scala.util.Random

class MockSigAlg extends SigAlg {
  override val name = "MockSignature"

  // A Secp256k1 public key is 32 bytes compressed or 64 bytes uncompressed,
  // with a 1 byte prefix showing which version it is.
  // See https://davidederosa.com/basic-blockchain-programming/elliptic-curve-keys
  override val PublicKeyBytesSize = 65
  // Normal Secp256k1 would be 32 bytes, but here we use the same value for
  // both public and private.
  override val PrivateKeyBytesSize = 65
  // A normal Secp256k1 signature consists of 2 bigints followed by a recovery ID,
  // but it can be just 64 bytes if that's omitted, like in the ENR.
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

  override def removeRecoveryId(signature: Signature): Signature =
    signature

  override def verify(publicKey: PublicKey, signature: Signature, data: BitVector): Boolean =
    publicKey == recoverPublicKey(signature, data).require

  override def recoverPublicKey(signature: Signature, data: BitVector): Attempt[PublicKey] = {
    Attempt.successful(PublicKey(xor(signature, data).take(PublicKeyBytesSize * 8)))
  }

  override def toPublicKey(privateKey: PrivateKey): PublicKey =
    PublicKey(privateKey)

  override def compressPublicKey(publicKey: PublicKey): PublicKey =
    publicKey

  // Using XOR twice recovers the original data.
  // Pad the data so we don't lose the key if the data is shorter.
  private def xor(key: BitVector, data: BitVector): BitVector = {
    (pad(key) ^ pad(data)).take(SignatureBytesSize * 8)
  }

  private def pad(bits: BitVector): BitVector =
    if (bits.length < SignatureBytesSize * 8) bits.padTo(SignatureBytesSize * 8) else bits
}
