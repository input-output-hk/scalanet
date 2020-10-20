package io.iohk.scalanet.discovery

import scodec.bits.BitVector

package object hash {

  sealed trait HashTag

  object Hash extends Tagger[BitVector, HashTag]
  type Hash = Hash.Tagged
}
