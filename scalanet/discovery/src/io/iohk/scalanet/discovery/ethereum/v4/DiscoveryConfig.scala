package io.iohk.scalanet.discovery.ethereum.v4

import scala.concurrent.duration._

case class DiscoveryConfig(
    // How long in the future to set message expiration.
    messageExpiration: FiniteDuration,
    // Allow incoming messages to be expired by this amount, accounting for the fact
    // the the senders clock might run late (or ours is early) and may have sent the
    // expiry to what already seems like the past.
    maxClockDrift: FiniteDuration,
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
    maxClockDrift = Duration.Zero,
    requestTimeout = 3.seconds,
    kademliaTimeout = 7.seconds,
    kademliaBucketSize = 16
  )
}
