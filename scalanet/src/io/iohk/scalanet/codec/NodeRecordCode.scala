package io.iohk.scalanet.codec

import io.iohk.scalanet.peergroup.kademlia.KRouter
import scodec.{Attempt, Codec, DecodeResult, SizeBound}
import scodec.bits.BitVector

class NodeRecordCode[A](implicit codec: Codec[A], longCodec: Codec[Long], bitVectorCodec: Codec[BitVector])
    extends Codec[KRouter.NodeRecord[A]] {
  override def decode(bits: BitVector): Attempt[DecodeResult[KRouter.NodeRecord[A]]] = {
    for {
      seq <- longCodec.decode(bits)
      sign <- ECDSASignatureCodec.decode(seq.remainder)
      routingAddress <- codec.decode(sign.remainder)
      messagingAddress <- codec.decode(routingAddress.remainder)
      id <- bitVectorCodec.decode(messagingAddress.remainder)
    } yield
      new DecodeResult[KRouter.NodeRecord[A]](
        KRouter.NodeRecord(id.value, routingAddress.value, messagingAddress.value, seq.value, sign.value),
        id.remainder)
  }

  override def encode(value: KRouter.NodeRecord[A]): Attempt[BitVector] = {
    for {
      seq <- longCodec.encode(value.seq)
      sign <- ECDSASignatureCodec.encode(value.sign)
      routingAddress <- codec.encode(value.routingAddress)
      messagingAddress <- codec.encode(value.messagingAddress)
      id <- bitVectorCodec.encode(value.id)
    } yield seq ++ sign ++ routingAddress ++ messagingAddress ++ id
  }

  override def sizeBound: SizeBound =
    SizeBound(
      bitVectorCodec.sizeBound.lowerBound + (longCodec.sizeBound.lowerBound
        + codec.sizeBound.lowerBound + ECDSASignatureCodec.sizeBound.lowerBound),
      for {
        signatureUpperBound <- ECDSASignatureCodec.sizeBound.upperBound
        bitVectorUppeBound <- bitVectorCodec.sizeBound.upperBound
        longUpperBound <- longCodec.sizeBound.upperBound
        codecUpperBound <- codec.sizeBound.upperBound
      } yield (signatureUpperBound + bitVectorUppeBound + longUpperBound + 2 * codecUpperBound)
    )
}
