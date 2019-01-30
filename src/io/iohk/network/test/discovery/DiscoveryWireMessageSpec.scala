package io.iohk.network.discovery

import java.net.{InetAddress, InetSocketAddress}

import akka.util.ByteString
import io.iohk.network.{Capabilities, NodeInfo}
import org.scalatest.{FlatSpec, MustMatchers}
import io.iohk.codecs.nio._
import io.iohk.codecs.nio.auto._
import org.scalatest.OptionValues._

class DiscoveryWireMessageSpec extends FlatSpec with MustMatchers {

  behavior of "DiscoveryWireMessage"

  it should "not lose info when encode/decode the messages" in {
    val addr1 = new InetSocketAddress(InetAddress.getByAddress(Array(1, 2, 3, 4)), 5)
    val addr2 = new InetSocketAddress(InetAddress.getByAddress(Array(6, 7, 8, 9)), 10)
    val node = NodeInfo(ByteString("node"), addr1, addr2, Capabilities(2))

    val ping = Ping(1, node, 3, ByteString("2"))
    val pingCodec = NioCodec[Ping]
    pingCodec.decode(pingCodec.encode(ping)).value mustBe ping

    val pong = Pong(node, ByteString("1"), 2)
    val pongCodec = NioCodec[Pong]

    pongCodec.decode(pongCodec.encode(pong)).value mustBe pong

    val neighbors = Neighbors(Capabilities(1), ByteString("token"), 2, Seq(node, node), 3)
    val neighborsCodec = NioCodec[Neighbors]
    neighborsCodec.decode(neighborsCodec.encode(neighbors)).value mustBe neighbors

    val seek = Seek(Capabilities(2), 3, 4, ByteString("nonce"))
    val seekCodec = NioCodec[Seek]
    seekCodec.decode(seekCodec.encode(seek)).value mustBe seek

    val wireMessageCodec = NioCodec[DiscoveryWireMessage]
    Seq(ping, pong, neighbors, seek).foreach { m =>
      wireMessageCodec.decode(wireMessageCodec.encode(m)).value mustBe m
    }
  }
}
