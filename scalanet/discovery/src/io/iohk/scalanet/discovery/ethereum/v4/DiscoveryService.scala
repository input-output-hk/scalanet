package io.iohk.scalanet.discovery.ethereum.v4

/** Implement the stateful discovery logic:
  * - maintain the state of K-buckets
  * - return node candidates for the rest of the system
  * - bond with the other nodes
  * - respond to incoming requests
  */
trait DiscoveryService[A] {}
