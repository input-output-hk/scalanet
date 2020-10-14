package io.iohk.scalanet.discovery.crypto

import scodec.bits.BitVector
import scodec.Attempt

trait SigAlg {
  def name: String

  def PrivateKeyBytesSize: Int
  def PublicKeyBytesSize: Int
  def SignatureBytesSize: Int

  def sign(privateKey: PrivateKey, data: BitVector): Signature
  def verify(publicKey: PublicKey, signature: Signature, data: BitVector): Boolean
  def recoverPublicKey(signature: Signature, data: BitVector): Attempt[PublicKey]
}
