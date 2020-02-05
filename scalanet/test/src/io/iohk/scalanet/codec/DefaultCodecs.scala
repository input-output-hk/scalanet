package io.iohk.scalanet.codec

import java.net.{InetAddress, InetSocketAddress}

import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.kademlia.KMessage
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.{Nodes, Pong}
import scodec.bits.{BitVector, ByteVector}
import scodec.{Codec, DecodeResult}
import scodec.codecs.{Discriminated, Discriminator}
import scodec.codecs._
import shapeless.Lazy
import scodec.bits._

/**
  *
  * Default encodings for different objects provided by scalanet
  *
  */
object DefaultCodecs {

  object General {

    val ipv4Pad = hex"00 00 00 00 00 00 00 00 00 00 FF FF"

    implicit val inetAddress = Codec[InetAddress](
      (ia: InetAddress) => {
        val bts = ByteVector(ia.getAddress)
        if (bts.length == 4) {
          bytes(16).encode(ipv4Pad ++ bts)
        } else {
          bytes(16).encode(bts)
        }
      },
      (buf: BitVector) =>
        bytes(16).decode(buf).map { b =>
          val bts = if (b.value.take(12) == ipv4Pad) {
            b.value.drop(12)
          } else {
            b.value
          }
          DecodeResult(InetAddress.getByAddress(bts.toArray), b.remainder)
        }
    )

    implicit val inetSocketAddress: Codec[InetSocketAddress] = {
      ("host" | Codec[InetAddress]) ::
        ("port" | uint16)
    }.as[(InetAddress, Int)]
      .xmap({ case (host, port) => new InetSocketAddress(host, port) }, isa => (isa.getAddress, isa.getPort))

    implicit val inetMultiAddressCodec: Codec[InetMultiAddress] = {
      ("inetSocketAddress" | Codec[InetSocketAddress])
    }.as[InetMultiAddress]

    implicit def seqCoded[A](implicit listCodec: Lazy[Codec[List[A]]]): Codec[Seq[A]] = {
      listCodec.value.xmap(l => l.toSeq, seq => seq.toList)
    }
  }

  object KademliaMessages {
    implicit def kMessageDiscriminator[A]: Discriminated[KMessage[A], Int] = Discriminated[KMessage[A], Int](uint4)

    implicit def findNodesDiscriminator[A]: Discriminator[KMessage[A], FindNodes[A], Int] =
      Discriminator[KMessage[A], FindNodes[A], Int](0)

    implicit def pingDiscriminator[A]: Discriminator[KMessage[A], Ping[A], Int] =
      Discriminator[KMessage[A], Ping[A], Int](1)

    implicit def nodesDiscriminator[A]: Discriminator[KMessage[A], Nodes[A], Int] =
      Discriminator[KMessage[A], Nodes[A], Int](2)

    implicit def pongDiscriminator[A]: Discriminator[KMessage[A], Pong[A], Int] =
      Discriminator[KMessage[A], Pong[A], Int](3)
  }

}
