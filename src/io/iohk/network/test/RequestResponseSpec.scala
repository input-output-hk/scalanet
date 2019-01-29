package io.iohk.network
import io.iohk.network.transport.tcp.NetUtils
import io.iohk.network.transport.tcp.NetUtils.{nodesArePeers, randomNetworkFixture}
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import io.iohk.codecs.nio.auto._

class RequestResponseSpec extends FlatSpec {

  private implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 1 seconds, interval = 50 millis)

  behavior of "RequestResponse"

  case class Request(content: String)
  case class Response(content: String)

  it should "send a request and receive a response" in
    NetUtils.networkFixtures(randomNetworkFixture(), randomNetworkFixture()) { networkFixtures =>
      val alicesRequest = Request("Hi! I'm Alice. What's your name?")
      val bobsResponse = Response("Hi, Alice!. I'm Bob.")

      val alicesNetwork = networkFixtures(0)
      val bobsNetwork = networkFixtures(1)

      nodesArePeers(alicesNetwork, bobsNetwork)

      val alicesSide = new RequestResponse[Request, Response](alicesNetwork.networkDiscovery, alicesNetwork.transports)
      val bobsSide = new RequestResponse[Request, Response](bobsNetwork.networkDiscovery, bobsNetwork.transports)

      bobsSide.handleRequest(request => {
        if (request == alicesRequest) {
          bobsResponse
        } else
          fail("Received an invalid request")
      })

      val response: Response = alicesSide.sendRequest(bobsNetwork.nodeId, alicesRequest).futureValue

      response shouldBe bobsResponse
    }
}
