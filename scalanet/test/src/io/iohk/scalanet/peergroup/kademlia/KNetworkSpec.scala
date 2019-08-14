package io.iohk.scalanet.peergroup.kademlia

import java.util.UUID
import java.util.concurrent.TimeoutException

import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.FindNodes
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.Nodes
import io.iohk.scalanet.peergroup.{Channel, PeerGroup}
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import monix.eval.Task
import monix.reactive.Observable
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.mockito.MockitoSugar._
import org.mockito.Mockito.{verify, when}

import scala.concurrent.duration._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures._
import KNetworkSpec._

class KNetworkSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(1 second)

  "KNetwork" should "close client channels when requests are successful" in {
    val (network, peerGroup) = createKNetwork
    val client = mock[Channel[String, KMessage[String]]]
    when(peerGroup.client(targetRecord.routingAddress)).thenReturn(Task(client))
    when(client.sendMessage(findNodes)).thenReturn(Task.unit)
    when(client.in).thenReturn(Observable.eval(nodes))
    when(client.close()).thenReturn(Task.unit)

    val response: Nodes[String] =
      network.findNodes(targetRecord, findNodes).futureValue

    response shouldBe nodes
    verify(client).close()
  }

  "KNetwork" should "pass exception when client call fails" in {
    val (network, peerGroup) = createKNetwork
    val client = mock[Channel[String, KMessage[String]]]
    val exception = new Exception("failed")
    when(peerGroup.client(targetRecord.routingAddress))
      .thenReturn(Task.raiseError(exception))
    when(client.close()).thenReturn(Task.unit)

    val t: Throwable =
      network.findNodes(targetRecord, findNodes).failed.futureValue

    t shouldBe exception
  }

  "KNetwork" should "close client channels when sendMessage calls fail" in {
    val (network, peerGroup) = createKNetwork
    val client = mock[Channel[String, KMessage[String]]]
    val exception = new Exception("failed")
    when(peerGroup.client(targetRecord.routingAddress)).thenReturn(Task(client))
    when(client.sendMessage(findNodes)).thenReturn(Task.raiseError(exception))
    when(client.close()).thenReturn(Task.unit)

    val t: Throwable =
      network.findNodes(targetRecord, findNodes).failed.futureValue

    t shouldBe exception
    verify(client).close()
  }

  "KNetwork" should "close client channels when response fails to arrive" in {
    val (network, peerGroup) = createKNetwork
    val client = mock[Channel[String, KMessage[String]]]
    when(peerGroup.client(targetRecord.routingAddress)).thenReturn(Task(client))
    when(client.sendMessage(findNodes)).thenReturn(Task.unit)
    when(client.in).thenReturn(Observable.fromTask(Task.never))
    when(client.close()).thenReturn(Task.unit)

    val t: Throwable =
      network.findNodes(targetRecord, findNodes).failed.futureValue

    t shouldBe a[TimeoutException]
    verify(client).close()
  }
}

object KNetworkSpec {

  private val nodeRecord: NodeRecord[String] = Generators.aRandomNodeRecord()
  private val targetRecord: NodeRecord[String] = Generators.aRandomNodeRecord()
  private val uuid: UUID = UUID.randomUUID()
  private val findNodes = FindNodes(uuid, nodeRecord, targetRecord.id)
  private val nodes = Nodes(uuid, targetRecord, Seq.empty)

  private def createKNetwork: (KNetwork[String], PeerGroup[String, KMessage[String]]) = {
    val peerGroup = mock[PeerGroup[String, KMessage[String]]]
    (new KNetworkScalanetImpl(peerGroup, 50 millis), peerGroup)
  }
}
