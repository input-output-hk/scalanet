package io.iohk.scalanet.discovery.ethereum.codecs

import io.iohk.scalanet.discovery.hash.Hash
import io.iohk.scalanet.discovery.crypto.{PublicKey, Signature}
import io.iohk.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import io.iohk.scalanet.discovery.ethereum.v4.Payload
import io.iohk.scalanet.discovery.ethereum.v4.Payload._
import scodec.Codec
import scodec.codecs.{Discriminated, Discriminator, uint4}
import scodec.codecs.implicits._
import scodec.bits.{BitVector, ByteVector}
import scala.collection.SortedMap
import scala.math.Ordering.Implicits._
import java.net.InetAddress


object DefaultCodecs {

  implicit val publicKeyCodec: Codec[PublicKey] =
    implicitly[Codec[BitVector]].xmap(PublicKey(_), identity)

  implicit val signatureCodec: Codec[Signature] =
    implicitly[Codec[BitVector]].xmap(Signature(_), identity)

  implicit val hashCodec: Codec[Hash] =
    implicitly[Codec[BitVector]].xmap(Hash(_), identity)

  implicit val inetAddressCodec: Codec[InetAddress] =
    implicitly[Codec[BitVector]]
      .xmap(bits => InetAddress.getByAddress(bits.toByteArray), ip => BitVector(ip.getAddress))

  implicit val addressCodec: Codec[Node.Address] =
    Codec.deriveLabelledGeneric

  implicit val nodeCodec: Codec[Node] =
    Codec.deriveLabelledGeneric

  implicit def sortedMapCodec[K: Codec: Ordering, V: Codec] =
    implicitly[Codec[List[(K, V)]]].xmap(
      (kvs: List[(K, V)]) => SortedMap(kvs: _*),
      (sm: SortedMap[K, V]) => sm.toList
    )

  implicit val byteVectorOrdering: Ordering[ByteVector] =
    Ordering.by[ByteVector, Iterable[Byte]](_.toIterable)

  implicit val attrCodec: Codec[SortedMap[ByteVector, ByteVector]] =
    sortedMapCodec[ByteVector, ByteVector]

  implicit val enrContentCodec: Codec[EthereumNodeRecord.Content] =
    Codec.deriveLabelledGeneric

  implicit val enrCodec: Codec[EthereumNodeRecord] =
    Codec.deriveLabelledGeneric

  implicit val pingCodec: Codec[Ping] =
    Codec.deriveLabelledGeneric

  implicit val pongCodec: Codec[Pong] =
    Codec.deriveLabelledGeneric

  implicit val findNodeCodec: Codec[FindNode] =
    Codec.deriveLabelledGeneric

  implicit val neigbhorsCodec: Codec[Neighbors] =
    Codec.deriveLabelledGeneric

  implicit val enrRequestCodec: Codec[ENRRequest] =
    Codec.deriveLabelledGeneric

  implicit val enrResponseCodec: Codec[ENRResponse] =
    Codec.deriveLabelledGeneric

  implicit val payloadDiscriminated =
    Discriminated[Payload, Int](uint4)

  implicit val pingDiscriminator =
    Discriminator[Payload, Ping, Int](1)

  implicit val pongDiscriminator =
    Discriminator[Payload, Pong, Int](2)

  implicit val findNodeDiscriminator =
    Discriminator[Payload, FindNode, Int](3)

  implicit val neighborsDiscriminator =
    Discriminator[Payload, Neighbors, Int](4)

  implicit val enrRequestDiscriminator =
    Discriminator[Payload, ENRRequest, Int](5)

  implicit val enrResponseDiscriminator =
    Discriminator[Payload, ENRResponse, Int](6)

  implicit val payloadCodec: Codec[Payload] =
    Codec.deriveCoproduct
}
