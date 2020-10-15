package io.iohk.scalanet.discovery.ethereum.v4

import scala.concurrent.duration._

case class DiscoveryConfig(
    // How long in the future to set message expiration.
    messageExpiration: FiniteDuration,
    // Timeout for individual requests.
    requestTimeout: FiniteDuration,
    // Timeout for collecting multiple potential Neighbors responses.
    kademliaTimeout: FiniteDuration,
    // Max number of neighbours to expect.
    kademliaBucketSize: Int
)

object DiscoveryConfig {
  val default = DiscoveryConfig(
    messageExpiration = 60.seconds,
    requestTimeout = 3.seconds,
    kademliaTimeout = 7.seconds,
    kademliaBucketSize = 16
  )
}
