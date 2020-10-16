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
    kademliaBucketSize: Int,
    // Concurrencly parameter 'alpha' for recursive Kademlia lookups.
    kademliaAlpha: Int,
    // Maximum time we consider a peer bonded without receiving a Pong response to a Ping.
    bondExpiration: FiniteDuration,
    // How often to look for new peers.
    discoveryPeriod: FiniteDuration
)

object DiscoveryConfig {
  val default = DiscoveryConfig(
    messageExpiration = 60.seconds,
    maxClockDrift = Duration.Zero,
    requestTimeout = 3.seconds,
    kademliaTimeout = 7.seconds,
    kademliaBucketSize = 16,
    kademliaAlpha = 3,
    bondExpiration = 12.hours,
    discoveryPeriod = 15.minutes
  )
}
