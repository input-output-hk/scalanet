package io.iohk.scalanet.discovery.hash

import org.bouncycastle.crypto.digests.KeccakDigest
import scodec.bits.BitVector

object Keccak256 {
  def apply(data: BitVector): Hash = {
    val input = data.toByteArray
    val output = new Array[Byte](32)
    val digest = new KeccakDigest(256)
    digest.update(input, 0, input.length)
    digest.doFinal(output, 0)
    Hash(BitVector(output))
  }
}
