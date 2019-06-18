package io.iohk.scalanet.peergroup.kademlia

object XOR {
  def d(a: BigInt, b: BigInt): BigInt = a.bigInteger.xor(b.bigInteger)
}
