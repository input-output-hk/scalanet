package io.iohk.scalanet.discovery.crypto

import scodec.bits.BitVector
import scodec.Attempt

trait SigAlg {
  def name: String

  def sign(privateKey: PrivateKey, data: BitVector): Signature
  def recoverPublicKey(signature: Signature, data: BitVector): Attempt[PublicKey]
}
