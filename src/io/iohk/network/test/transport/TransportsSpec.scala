package io.iohk.network.transport

import io.iohk.codecs.nio._
import io.iohk.network.transport.tcp.NetUtils.{aRandomAddress, aRandomNodeId}
import io.iohk.network.transport.tcp.TcpTransportConfig
import io.iohk.network.{NetworkConfig, PeerConfig}
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.mockito.MockitoSugar._

class TransportsSpec extends FlatSpec {

  behavior of "Transports"

  it should "say usesTcp = true if tcp is configured" in {
    val peerConfig =
      PeerConfig(aRandomNodeId(), NetworkConfig(Some(TcpTransportConfig(aRandomAddress()))))

    Transports.usesTcp(peerConfig) shouldBe true
  }

  it should "say usesTcp = false if tcp is not configured" in {
    val peerConfig = PeerConfig(aRandomNodeId(), NetworkConfig(None))

    Transports.usesTcp(peerConfig) shouldBe false
  }

  it should "initialize netty if tcp is configured" in {
    val peerConfig =
      PeerConfig(aRandomNodeId(), NetworkConfig(Some(TcpTransportConfig(aRandomAddress()))))

    val transports = new Transports(peerConfig)

    transports.netty() shouldBe defined
  }

  it should "not initialize netty if tcp is not configured" in {
    val peerConfig = PeerConfig(aRandomNodeId(), NetworkConfig(None))

    val transports = new Transports(peerConfig)

    transports.netty() shouldBe None
  }

  it should "not initialize netty twice" in {
    val peerConfig =
      PeerConfig(aRandomNodeId(), NetworkConfig(Some(TcpTransportConfig(aRandomAddress()))))

    val transports = new Transports(peerConfig)

    transports.netty() shouldBe transports.netty()
  }

  it should "return tcp if tcp is configured" in {
    val peerConfig =
      PeerConfig(aRandomNodeId(), NetworkConfig(Some(TcpTransportConfig(aRandomAddress()))))

    val transports = new Transports(peerConfig)

    transports.tcp(mock[NioCodec[String]]) shouldBe defined
  }

  it should "not return tcp if tcp is not configured" in {
    val peerConfig = PeerConfig(aRandomNodeId(), NetworkConfig(None))

    val transports = new Transports(peerConfig)

    transports.tcp(mock[NioCodec[String]]) shouldBe None
  }
}
