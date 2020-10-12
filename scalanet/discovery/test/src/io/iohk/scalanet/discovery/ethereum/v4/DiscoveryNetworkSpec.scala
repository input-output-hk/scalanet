package io.iohk.scalanet.discovery.ethereum.v4

import io.iohk.scalanet.discovery.ethereum.codecs.DefaultCodecs._
import io.iohk.scalanet.discovery.ethereum.v4.mocks.MockSigAlg
import org.scalatest._

class DiscoveryNetworkSpec extends FlatSpec with Matchers {

  implicit val sigalg = new MockSigAlg

  behavior of "ping"
  it should "send an unexpired correctly versioned Ping Packet with the the local and remote addresses" in (pending)
  it should "return None if the peer times out" in (pending)
  it should "return Some ENRSEQ if the peer responds" in (pending)

  behavior of "findNode"
  it should "send an unexpired FindNode Packet with the given target" in (pending)
  it should "return None if the peer times out" in (pending)
  it should "return Some Nodes if the peer responds" in (pending)
  it should "collect responses up to the timeout" in (pending)
  it should "collect responses up to the bucket size" in (pending)

  behavior of "enrRequest"
  it should "send an unexpired ENRRequest Packet" in (pending)
  it should "return None if the peer times out" in (pending)
  it should "return Some ENR if the peer responds" in (pending)

  behavior of "startHandling"
  it should "start handling requests in the background" in (pending)
  it should "handle multiple channels in parallel" in (pending)
  it should "stop handling when canceled" in (pending)
  it should "close idle channels" in (pending)
  it should "ignore incoming response messages" in (pending)
  it should "not respond to expired Ping" in (pending)
  it should "not respond with a Pong if the handler returns None" in (pending)
  it should "respond with an unexpired Pong with the correct hash if the handler returns Some ENRSEQ" in (pending)
  it should "not respond to expired FindNode" in (pending)
  it should "not respond with Neighbors if the handler returns None" in (pending)
  it should "respond with multiple unexpired Neighbors each within the packet size limit if the handler returns Some Nodes" in (pending)
  it should "not respond to expired ENRRequest" in (pending)
  it should "not respond with ENRResponse if the handler returns None" in (pending)
  it should "respond with an ENRResponse with the correct hash if the handler returns Some ENR" in (pending)

  behavior of "getMaxNeighborsPerPacket"
  it should "correctly estimate the maximum number" in {
    val maxNeighborsPerPacket = DiscoveryNetwork.getMaxNeighborsPerPacket
    // We're using scodec encoding here, so it's not exactly the same as RLP,
    // but it should be less than the default Kademlia bucket size of 16.
    maxNeighborsPerPacket should be > 1
    maxNeighborsPerPacket should be < 16
  }
}
