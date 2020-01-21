package io.iohk.scalanet.peergroup.kademlia

import io.iohk.decco.CodecContract
import io.iohk.decco.auto.instances.NativeInstances.ByteArrayCodec
import scodec.bits.BitVector

object BitVectorCodec {

  implicit def bitVectorInstance(implicit iCodec: CodecContract[Int]): CodecContract[BitVector] =
    ByteArrayCodec.map(array => BitVector(array), bitVector => bitVector.toByteArray)
}
