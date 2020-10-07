package io.iohk.scalanet.discovery.ethereum

import scodec.bits.BitVector
import scodec.Attempt

trait Crypto {
  import Crypto._

  def sign(privateKey: PrivateKey, data: BitVector): Signature
  def recoverPublicKey(signature: Signature, data: BitVector): Attempt[PublicKey]
}

object Crypto {
  // TODO: shapeless tags
  type PrivateKey = BitVector
  type PublicKey = BitVector
  type Signature = BitVector
}
