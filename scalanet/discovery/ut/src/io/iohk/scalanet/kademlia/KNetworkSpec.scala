package io.iohk.scalanet.kademlia

import java.util.UUID
import cats.effect.Resource
import io.iohk.scalanet.kademlia.KMessage.KResponse
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import io.iohk.scalanet.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.peergroup.{Channel, PeerGroup}
import io.iohk.scalanet.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.kademlia.KRouter.NodeRecord
import monix.eval.Task
import monix.reactive.Observable
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.mockito.MockitoSugar._
import org.mockito.Mockito.{when}

import scala.concurrent.duration._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures._
import io.iohk.scalanet.TaskValues._
import KNetworkSpec._
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.Channel.MessageReceived
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.kademlia.KMessage.KRequest
import org.scalatest.prop.TableDrivenPropertyChecks._

class KNetworkSpec extends FlatSpec {
  import KNetworkRequestProcessing._

  implicit val patienceConfig = PatienceConfig(1 second)

  private val getFindNodesRequest: KNetwork[String] => Task[KRequest[String]] = getActualRequest(_.findNodesRequests())
  private val getPingRequest: KNetwork[String] => Task[KRequest[String]] = getActualRequest(_.pingRequests())

  private val sendFindNodesRequest: (NodeRecord[String], FindNodes[String]) => KNetwork[String] => Task[Nodes[String]] =
    (to, request) => network => network.findNodes(to, request)

  private val sendPingRequest: (NodeRecord[String], Ping[String]) => KNetwork[String] => Task[Pong[String]] =
    (to, request) => network => network.ping(to, request)

  private val rpcs = Table(
    ("Label", "Request", "Response", "Request extractor", "Client RPC"),
    ("FIND_NODES", findNodes, nodes, getFindNodesRequest, sendFindNodesRequest(targetRecord, findNodes)),
    ("PING", ping, pong, getPingRequest, sendPingRequest(targetRecord, ping))
  )

  trait Fixture {
    class MockChannel {
      val channel = mock[Channel[String, KMessage[String]]]
      val closed = new AtomicBoolean(false)
      val created = ChannelCreated(channel, Task { closed.set(true) })
      val resource = Resource.make(Task.pure(channel))(_ => Task { closed.set(true) })
    }

    val (network, peerGroup) = createKNetwork
    val (channel, channelCreated, channelClosed, channelResource) = {
      val mc = new MockChannel
      (mc.channel, mc.created, mc.closed, mc.resource)
    }
  }

  forAll(rpcs) { (label, request, response, requestExtractor, clientRpc) =>
    s"Server $label" should "not close server channels while yielding requests (it is the responsibility of the response handler)" in new Fixture {
      when(peerGroup.server)
        .thenReturn(ConnectableSubject(Observable.eval(channelCreated)))
      when(channel.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(request))))

      val actualRequest = requestExtractor(network).evaluated

      actualRequest shouldBe request
      channelClosed.get shouldBe false
    }

    s"Server $label" should "close server channels when a request does not arrive before a timeout" in new Fixture {
      when(peerGroup.server)
        .thenReturn(ConnectableSubject(Observable.eval(channelCreated)))
      when(channel.in).thenReturn(ConnectableSubject(Observable.never))

      val t = requestExtractor(network).runToFuture.failed.futureValue

      t shouldBe a[NoSuchElementException]
      channelClosed.get shouldBe true
    }

    s"Server $label" should "close server channel in the response task" in new Fixture {
      when(peerGroup.server)
        .thenReturn(ConnectableSubject(Observable.eval(channelCreated)))
      when(channel.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(request))))
      when(channel.sendMessage(response)).thenReturn(Task.unit)

      sendResponse(network, response).evaluated

      channelClosed.get shouldBe true
    }

    s"Server $label" should "close server channel in timed out response task" in new Fixture {
      when(peerGroup.server)
        .thenReturn(ConnectableSubject(Observable.eval(channelCreated)))
      when(channel.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(request))))
      when(channel.sendMessage(response)).thenReturn(Task.never)

      sendResponse(network, response).evaluatedFailure shouldBe a[TimeoutException]
      channelClosed.get shouldBe true
    }

    s"Server $label" should "keep working even if there is an error" in new Fixture {
      val channel1 = new MockChannel
      val channel2 = new MockChannel

      when(peerGroup.server).thenReturn(ConnectableSubject(Observable(channel1.created, channel2.created)))
      when(channel1.channel.in).thenReturn(ConnectableSubject(Observable.never)) // Should be closed after a timeout.
      when(channel2.channel.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(request))))

      // Process incoming channels and requests. Need to wait a little to allow channel1 to time out.
      val actualRequest = requestExtractor(network).delayResult(requestTimeout).evaluated

      actualRequest shouldBe request
      channel1.closed.get shouldBe true
      channel2.closed.get shouldBe false
    }

    s"Client $label" should "close client channels when requests are successful" in new Fixture {
      when(peerGroup.client(targetRecord.routingAddress)).thenReturn(channelResource)
      when(channel.sendMessage(request)).thenReturn(Task.unit)
      when(channel.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(response))))

      val actualResponse = clientRpc(network).evaluated

      actualResponse shouldBe response
      channelClosed.get shouldBe true
    }

    s"Client $label" should "pass exception when client call fails" in new Fixture {
      val exception = new Exception("failed")

      when(peerGroup.client(targetRecord.routingAddress))
        .thenReturn(Resource.liftF(Task.raiseError[Channel[String, KMessage[String]]](exception)))

      clientRpc(network).evaluatedFailure shouldBe exception
    }

    s"Client $label" should "close client channels when sendMessage calls fail" in new Fixture {
      val exception = new Exception("failed")
      when(peerGroup.client(targetRecord.routingAddress)).thenReturn(channelResource)
      when(channel.sendMessage(request)).thenReturn(Task.raiseError(exception))

      clientRpc(network).evaluatedFailure shouldBe exception
      channelClosed.get shouldBe true
    }

    s"Client $label" should "close client channels when response fails to arrive" in new Fixture {
      when(peerGroup.client(targetRecord.routingAddress)).thenReturn(channelResource)
      when(channel.sendMessage(request)).thenReturn(Task.unit)
      when(channel.in).thenReturn(ConnectableSubject(Observable.fromTask(Task.never)))

      clientRpc(network).evaluatedFailure shouldBe a[TimeoutException]
      channelClosed.get shouldBe true
    }
  }

  s"In consuming only PING" should "channels should be closed for unhandled FIND_NODES requests" in new Fixture {
    val channel1 = new MockChannel
    val channel2 = new MockChannel
    when(peerGroup.server)
      .thenReturn(ConnectableSubject(Observable(channel1.created, channel2.created)))

    when(channel1.channel.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(findNodes))))
    when(channel2.channel.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(ping))))

    when(channel2.channel.sendMessage(pong)).thenReturn(Task.unit)

    // `pingRequests` consumes all requests and call `ignore` on the FindNodes, passing None which should close the channel.
    val (actualRequest, handler) = network.pingRequests().headL.evaluated

    actualRequest shouldBe ping
    channel1.closed.get shouldBe true
    channel2.closed.get shouldBe false

    handler(Some(pong)).runToFuture.futureValue
    channel2.closed.get shouldBe true
  }
}

object KNetworkSpec {

  val requestTimeout = 50.millis

  private val nodeRecord: NodeRecord[String] = Generators.aRandomNodeRecord()
  private val targetRecord: NodeRecord[String] = Generators.aRandomNodeRecord()
  private val uuid: UUID = UUID.randomUUID()
  private val findNodes = FindNodes(uuid, nodeRecord, targetRecord.id)
  private val nodes = Nodes(uuid, targetRecord, Seq.empty)

  private val ping = Ping(uuid, nodeRecord)
  private val pong = Pong(uuid, targetRecord)

  private def createKNetwork: (KNetwork[String], PeerGroup[String, KMessage[String]]) = {
    val peerGroup = mock[PeerGroup[String, KMessage[String]]]
    when(peerGroup.server).thenReturn(ConnectableSubject(Observable.empty))
    (new KNetworkScalanetImpl(peerGroup, requestTimeout), peerGroup)
  }

  private def getActualRequest[Request <: KRequest[String]](rpc: KNetwork[String] => Observable[(Request, _)])(
      network: KNetwork[String]
  ): Task[Request] = {
    rpc(network).headL.map(_._1)
  }

  def sendResponse(network: KNetwork[String], response: KResponse[String]): Task[Unit] = {
    network.kRequests.headL.flatMap { case (_, handler) => handler(Some(response)) }
  }
}
