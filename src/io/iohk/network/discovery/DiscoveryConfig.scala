package io.iohk.network.discovery

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.TimeUnit

import io.iohk.network.{NodeInfo, NodeParser}

import scala.concurrent.duration.{FiniteDuration, _}

case class DiscoveryConfig(
    discoveryEnabled: Boolean,
    interface: String,
    port: Int,
    bootstrapNodes: Set[NodeInfo],
    discoveredNodesLimit: Int,
    scanNodesLimit: Int,
    concurrencyDegree: Int,
    scanInitialDelay: FiniteDuration,
    scanInterval: FiniteDuration,
    messageExpiration: FiniteDuration,
    maxSeekResults: Int,
    multipleConnectionsPerAddress: Boolean,
    blacklistDefaultDuration: FiniteDuration
) {
  val discoveryAddress = new InetSocketAddress(InetAddress.getByName(interface), port)
}

object DiscoveryConfig {
  def apply(discoveryConfig: com.typesafe.config.Config): DiscoveryConfig = {
    import scala.collection.JavaConverters._
    val bootstrapNodes = NodeParser.parseNodeInfos(discoveryConfig.getConfigList("bootstrapNodes").asScala.toSet)
    val blacklistDuration = {
      val duration = discoveryConfig.getDuration("blacklistDefaultDuration")
      FiniteDuration(duration.toNanos, TimeUnit.NANOSECONDS)
    }

    DiscoveryConfig(
      discoveryEnabled = discoveryConfig.getBoolean("enabled"),
      interface = discoveryConfig.getString("interface"),
      port = discoveryConfig.getInt("port"),
      bootstrapNodes = bootstrapNodes,
      discoveredNodesLimit = discoveryConfig.getInt("discoveredNodesLimit"),
      scanNodesLimit = discoveryConfig.getInt("scanNodesLimit"),
      concurrencyDegree = discoveryConfig.getInt("concurrencyDegree"),
      scanInitialDelay = discoveryConfig.getDuration("scanInitialDelay").toMillis.millis,
      scanInterval = discoveryConfig.getDuration("scanInterval").toMillis.millis,
      messageExpiration = discoveryConfig.getDuration("messageExpiration").toMillis.millis,
      maxSeekResults = discoveryConfig.getInt("maxSeekResults"),
      multipleConnectionsPerAddress = discoveryConfig.getBoolean("multipleConnectionsPerAddress"),
      blacklistDefaultDuration = blacklistDuration
    )
  }

}
