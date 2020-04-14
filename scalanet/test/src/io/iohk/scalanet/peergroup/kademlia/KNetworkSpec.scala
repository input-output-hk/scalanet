package io.iohk.scalanet.peergroup.kademlia

import java.util.UUID

import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse
import java.util.concurrent.TimeoutException

import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.peergroup.{Channel, PeerGroup}
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import monix.eval.Task
import monix.reactive.Observable
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.mockito.MockitoSugar._
import org.mockito.Mockito.{never, verify, when}

import scala.concurrent.duration._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures._
import io.iohk.scalanet.TaskValues._
import KNetworkSpec._
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.Channel.MessageReceived
import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest
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

  forAll(rpcs) { (label, request, response, requestExtractor, clientRpc) =>
    s"Server $label" should "not close server channels (it is the responsibility of the response handler)" in {
      val (network, peerGroup) = createKNetwork
      val channel = mock[Channel[String, KMessage[String]]]
      when(peerGroup.server())
        .thenReturn(ConnectableSubject(Observable.eval(ChannelCreated(channel))))
      when(channel.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(request))))
      when(channel.close()).thenReturn(Task.unit)

      val actualRequest = requestExtractor(network).evaluated

      actualRequest shouldBe request
      verify(channel, never()).close()
    }

    s"Server $label" should "close server channels when a request does not arrive before a timeout" in {
      val (network, peerGroup) = createKNetwork
      val channel = mock[Channel[String, KMessage[String]]]
      when(peerGroup.server())
        .thenReturn(ConnectableSubject(Observable.eval(ChannelCreated(channel))))
      when(channel.in).thenReturn(ConnectableSubject(Observable.never))
      when(channel.close()).thenReturn(Task.unit)

      val t = requestExtractor(network).runToFuture.failed.futureValue

      t shouldBe a[NoSuchElementException]
      verify(channel).close()
    }

    s"Server $label" should "close server channel in the response task" in {
      val (network, peerGroup) = createKNetwork
      val channel = mock[Channel[String, KMessage[String]]]
      when(peerGroup.server())
        .thenReturn(ConnectableSubject(Observable.eval(ChannelCreated(channel))))
      when(channel.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(request))))
      when(channel.sendMessage(response)).thenReturn(Task.unit)
      when(channel.close()).thenReturn(Task.unit)

      sendResponse(network, response).evaluated

      verify(channel).close()
    }

    s"Server $label" should "close server channel in timed out response task" in {
      val (network, peerGroup) = createKNetwork
      val channel = mock[Channel[String, KMessage[String]]]
      when(peerGroup.server())
        .thenReturn(ConnectableSubject(Observable.eval(ChannelCreated(channel))))
      when(channel.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(request))))
      when(channel.sendMessage(response)).thenReturn(Task.never)
      when(channel.close()).thenReturn(Task.unit)

      sendResponse(network, response).evaluatedFailure shouldBe a[TimeoutException]
      verify(channel).close()
    }

    s"Server $label" should "keep working even if there is an error" in {
      val (network: KNetwork[String], peerGroup) = createKNetwork
      val channel1 = mock[Channel[String, KMessage[String]]]
      val channel2 = mock[Channel[String, KMessage[String]]]
      when(peerGroup.server())
        .thenReturn(ConnectableSubject(Observable(ChannelCreated(channel1), ChannelCreated(channel2))))

      when(channel1.in).thenReturn(ConnectableSubject(Observable.never))
      when(channel2.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(request))))

      when(channel1.close()).thenReturn(Task.unit)
      when(channel2.close()).thenReturn(Task.unit)

      val actualRequest = requestExtractor(network).evaluated

      actualRequest shouldBe request
      verify(channel1).close()
      verify(channel2, never()).close()
    }

    s"Client $label" should "close client channels when requests are successful" in {
      val (network, peerGroup) = createKNetwork
      val client = mock[Channel[String, KMessage[String]]]
      when(peerGroup.client(targetRecord.routingAddress)).thenReturn(Task(client))
      when(client.sendMessage(request)).thenReturn(Task.unit)
      when(client.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(response))))
      when(client.close()).thenReturn(Task.unit)

      val actualResponse = clientRpc(network).evaluated

      actualResponse shouldBe response
      verify(client).close()
    }

    s"Client $label" should "pass exception when client call fails" in {
      val (network, peerGroup) = createKNetwork
      val client = mock[Channel[String, KMessage[String]]]
      val exception = new Exception("failed")
      when(peerGroup.client(targetRecord.routingAddress))
        .thenReturn(Task.raiseError(exception))
      when(client.close()).thenReturn(Task.unit)

      clientRpc(network).evaluatedFailure shouldBe exception
    }

    s"Client $label" should "close client channels when sendMessage calls fail" in {
      val (network, peerGroup) = createKNetwork
      val client = mock[Channel[String, KMessage[String]]]
      val exception = new Exception("failed")
      when(peerGroup.client(targetRecord.routingAddress)).thenReturn(Task(client))
      when(client.sendMessage(request)).thenReturn(Task.raiseError(exception))
      when(client.close()).thenReturn(Task.unit)

      clientRpc(network).evaluatedFailure shouldBe exception
      verify(client).close()
    }

    s"Client $label" should "close client channels when response fails to arrive" in {
      val (network, peerGroup) = createKNetwork
      val client = mock[Channel[String, KMessage[String]]]
      when(peerGroup.client(targetRecord.routingAddress)).thenReturn(Task(client))
      when(client.sendMessage(request)).thenReturn(Task.unit)
      when(client.in).thenReturn(ConnectableSubject(Observable.fromTask(Task.never)))
      when(client.close()).thenReturn(Task.unit)

      clientRpc(network).evaluatedFailure shouldBe a[TimeoutException]
      verify(client).close()
    }
  }

  s"In consuming only PING" should "channels should be closed for unhandled FIND_NODES requests" in {
    val (network: KNetwork[String], peerGroup) = createKNetwork
    val channel1 = mock[Channel[String, KMessage[String]]]
    val channel2 = mock[Channel[String, KMessage[String]]]
    when(peerGroup.server())
      .thenReturn(ConnectableSubject(Observable(ChannelCreated(channel1), ChannelCreated(channel2))))

    when(channel1.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(findNodes))))
    when(channel2.in).thenReturn(ConnectableSubject(Observable.eval(MessageReceived(ping))))

    when(channel2.sendMessage(pong)).thenReturn(Task.unit)

    when(channel1.close()).thenReturn(Task.unit)
    when(channel2.close()).thenReturn(Task.unit)

    val (actualRequest, handler) = network.pingRequests().headL.evaluated

    actualRequest shouldBe ping
    verify(channel1).close()
    verify(channel2, never()).close()

    handler(Some(pong)).runToFuture.futureValue
    verify(channel2).close()
  }
}

object KNetworkSpec {

  private val nodeRecord: NodeRecord[String] = Generators.aRandomNodeRecord()
  private val targetRecord: NodeRecord[String] = Generators.aRandomNodeRecord()
  private val uuid: UUID = UUID.randomUUID()
  private val findNodes = FindNodes(uuid, nodeRecord, targetRecord.id)
  private val nodes = Nodes(uuid, targetRecord, Seq.empty)

  private val ping = Ping(uuid, nodeRecord)
  private val pong = Pong(uuid, targetRecord)

  private def createKNetwork: (KNetwork[String], PeerGroup[String, KMessage[String]]) = {
    val peerGroup = mock[PeerGroup[String, KMessage[String]]]
    when(peerGroup.server()).thenReturn(ConnectableSubject(Observable.empty))
    (new KNetworkScalanetImpl(peerGroup, 50 millis), peerGroup)
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
