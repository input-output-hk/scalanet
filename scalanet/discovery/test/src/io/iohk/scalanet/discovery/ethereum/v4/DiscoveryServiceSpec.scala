package io.iohk.scalanet.discovery.ethereum.v4

import org.scalatest._

trait DiscoveryServiceSpec extends FlatSpec {

  behavior of "getNode"
  it should "return the local node" in (pending)
  it should "not return a nodes which is not bonded" in (pending)
  it should "return a bonded node" in (pending)
  it should "return a node from the local cache" in (pending)
  it should "lookup a node remotely if not found locally" in (pending)

  behavior of "getNodes"
  it should "not return the local node" in (pending)
  it should "not return nodes which aren't bonded" in (pending)
  it should "return bonded nodes" in (pending)

  behavior of "addNode"
  it should "try to bond with the node" in (pending)

  behavior of "removeNode"
  it should "remove bonded or unbonded nodes from the cache" in (pending)

  behavior of "updateExternalAddress"
  it should "update the address of the local node" in (pending)
  it should "increment the local ENR sequence" in (pending)

  behavior of "localNode"
  it should "return the latest local node record" in (pending)

  behavior of "enroll"
  it should "perform a self-lookup with the bootstrap nodes" in (pending)

  behavior of "startPeriodicRefresh"
  it should "periodically ping nodes" in (pending)

  behavior of "startPeriodicDiscovery"
  it should "periodically lookup a random node" in (pending)

  behavior of "startRequestHandling"
  it should "respond to pings with its local ENR sequence" in (pending)
  it should "not respond to findNode from unbonded peers" in (pending)
  it should "respond to findNode from bonded peer with the closest bonded peers" in (pending)
  it should "not respond to enrRequest from unbonded peers" in (pending)
  it should "respond to enrRequest from bonded peers with its signed local ENR" in (pending)
  it should "bond with peers that ping it" in (pending)
  it should "update the node record to the latest it connected from" in (pending)

  behavior of "initBond"
  it should "try to bond if past the expiration period" in (pending)
  it should "not try to bond again within the expiration period" in (pending)
  it should "only do one bond with a given peer at a time" in (pending)

  behavior of "completeBond"
  it should "complete all bonds initiated to the peer" in (pending)

  behavior of "bond"
  it should "not try to bond if already bonded" in (pending)
  it should "fetch the ENR once bonded" in (pending)
  it should "remove nodes if the bonding fails" in (pending)
  it should "wait for a ping to arrive from the other party" in (pending)

  behavior of "lookup"
  it should "bond with nodes while doing recursive lookups before contacting them" in (pending)
  it should "return the node seeked or nothing" in (pending)
  it should "fetch the ENR record of the node" in (pending)

  behavior of "fetchEnr"
  it should "validate that the packet sender signed the ENR" in (pending)
}
