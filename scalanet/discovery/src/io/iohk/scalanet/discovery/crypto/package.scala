package io.iohk.scalanet.discovery

import scodec.bits.BitVector

package object crypto {

  sealed trait PrivateKeyTag
  sealed trait PublicKeyTag
  sealed trait SignatureTag

  object PrivateKey extends Tagger[BitVector, PrivateKeyTag]
  type PrivateKey = PrivateKey.Tagged

  object PublicKey extends Tagger[BitVector, PublicKeyTag]
  type PublicKey = PublicKey.Tagged

  object Signature extends Tagger[BitVector, SignatureTag]
  type Signature = Signature.Tagged
}
