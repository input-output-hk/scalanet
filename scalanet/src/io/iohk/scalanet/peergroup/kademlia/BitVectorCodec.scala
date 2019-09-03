package io.iohk.scalanet.peergroup.kademlia

import io.iohk.decco.Codec
import io.iohk.decco.auto.instances.NativeInstances.ByteArrayCodec
import scodec.bits.BitVector

object BitVectorCodec {

  implicit def bitVectorInstance(implicit iCodec: Codec[Int]): Codec[BitVector] =
    ByteArrayCodec.map(array => BitVector(array), bitVector => bitVector.toByteArray)
}
