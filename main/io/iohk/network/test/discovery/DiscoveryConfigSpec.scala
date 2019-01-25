package io.iohk.network.discovery

import com.typesafe.config.ConfigFactory
import io.iohk.network.Capabilities
import io.iohk.network.utils.HexStringCodec._
import org.scalatest.{FlatSpec, MustMatchers}

class DiscoveryConfigSpec extends FlatSpec with MustMatchers {

  behavior of "DiscoveryConfig"

  it should "read the config file correctly" in {
    val enabled = true
    val interface = "127.0.0.1"
    val port = 8090
    val bootstrapDiscUri = "udp://127.0.0.2:3020"
    val nodeId = "abcd"
    val enode = s"enode://${nodeId}@127.0.0.1:3000"
    val capabilities = "01"
    val nodesLimit = 1
    val scanNodesLimit = 2
    val concurrencyDegree = 7
    val scanInitDel = 6
    val scanInterval = 3
    val messageExpiration = 4
    val maxSeekResults = 5
    val multipleConn = false
    val blacklistDuration = 6
    val config = s"""discovery {
                   |  enabled = $enabled
                   |  interface = "$interface"
                   |  port = $port
                   |  bootstrapNodes = [
                   |    {
                   |      discoveryUri = "$bootstrapDiscUri"
                   |      p2pUri = "$enode"
                   |      capabilities = "$capabilities"
                   |    }
                   |  ]
                   |  discoveredNodesLimit = $nodesLimit
                   |  scanNodesLimit = $scanNodesLimit
                   |  concurrencyDegree = $concurrencyDegree
                   |  scanInitialDelay = $scanInitDel
                   |  scanInterval = $scanInterval
                   |  messageExpiration = $messageExpiration
                   |  maxSeekResults = $maxSeekResults
                   |  multipleConnectionsPerAddress = $multipleConn
                   |  blacklistDefaultDuration = $blacklistDuration
                   |}""".stripMargin

    val dc = DiscoveryConfig(ConfigFactory.parseString(config).getConfig("discovery"))
    dc.discoveryEnabled mustBe enabled
    dc.interface mustBe interface
    dc.port mustBe port
    dc.bootstrapNodes.size mustBe 1
    dc.bootstrapNodes.head.capabilities mustBe Capabilities(1)
    dc.bootstrapNodes.head.id mustBe fromHexString(nodeId)
    dc.bootstrapNodes.head.discoveryAddress.getAddress.getAddress.toSeq mustBe Seq[Byte](127, 0, 0, 2)
    dc.bootstrapNodes.head.discoveryAddress.getPort mustBe 3020
    dc.bootstrapNodes.head.serverAddress.getAddress.getAddress.toSeq mustBe Seq[Byte](127, 0, 0, 1)
    dc.bootstrapNodes.head.serverAddress.getPort mustBe 3000
    dc.discoveredNodesLimit mustBe nodesLimit
    dc.scanNodesLimit mustBe scanNodesLimit
    dc.scanInterval.toMillis mustBe scanInterval
    dc.scanInitialDelay.toMillis mustBe scanInitDel
    dc.messageExpiration.toMillis mustBe messageExpiration
    dc.maxSeekResults mustBe maxSeekResults
    dc.multipleConnectionsPerAddress mustBe multipleConn
    dc.blacklistDefaultDuration.toMillis mustBe blacklistDuration
  }
}
