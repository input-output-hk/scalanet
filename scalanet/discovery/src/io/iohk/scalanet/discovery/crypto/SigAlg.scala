package io.iohk.scalanet.discovery.crypto

import scodec.bits.BitVector
import scodec.Attempt

trait SigAlg {
  def name: String

  def PrivateKeyBytesSize: Int
  def PublicKeyBytesSize: Int
  def SignatureBytesSize: Int

  def newKeyPair: (PublicKey, PrivateKey)

  /** In the context of Secp256k1, produce a 65 byte signature
    * as the concatenation of `r`, `s` and the recovery ID `v`. */
  def sign(privateKey: PrivateKey, data: BitVector): Signature

  /** In the context of Secp256k1, remove the `v` recovery ID. */
  def removeRecoveryId(signature: Signature): Signature

  /** Verify that a signature is correct. It may or may not have a recovery ID. */
  def verify(publicKey: PublicKey, signature: Signature, data: BitVector): Boolean

  /** Reverse engineer the public key from a signature, given the data that was signed.
    * It can fail if the signature is incorrect.
    */
  def recoverPublicKey(signature: Signature, data: BitVector): Attempt[PublicKey]

  /** Produce the public key based on the private key. */
  def toPublicKey(privateKey: PrivateKey): PublicKey

  /** In the context of Secp256k1, the signature consists of a prefix byte
    * followed by an `x` and `y` coordinate. Remove `y` and adjust the prefix
    * to compress.
    *
    * See https://davidederosa.com/basic-blockchain-programming/elliptic-curve-keys
    */
  def compressPublicKey(publicKey: PublicKey): PublicKey
}
