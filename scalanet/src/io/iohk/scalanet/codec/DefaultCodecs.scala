package io.iohk.scalanet.codec

import java.net.{InetAddress, InetSocketAddress}

import io.iohk.scalanet.peergroup.InetMultiAddress
import scodec.bits.{BitVector, ByteVector}
import scodec.{Codec, DecodeResult}
import scodec.codecs._
import shapeless.Lazy
import scodec.bits._

/**
  *
  * Default encodings for different objects provided by scalanet,
  * using scodec specific codecs.
  *
  */
object DefaultCodecs {

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
