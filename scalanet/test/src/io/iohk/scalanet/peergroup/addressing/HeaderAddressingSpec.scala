package io.iohk.scalanet.peergroup.addressing
import io.iohk.scalanet.peergroup.InMemoryPeerGroup
import monix.execution.Scheduler
import org.scalatest.{BeforeAndAfter, FlatSpec}
import io.iohk.scalanet.TaskValues._

import scala.concurrent.Future
import scala.util.Random
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._

class HeaderAddressingSpec extends FlatSpec with BeforeAndAfter {

  import InMemoryPeerGroup.Network

  val n: Network[Int, HeaderAddressing.Header[String, String]] =
    new Network[Int, HeaderAddressing.Header[String, String]]()

  before {
    n.clear()
  }

  def twoRandomPeerGroup()(
      implicit scheduler: Scheduler
  ): (HeaderAddressing[String, Int, String], HeaderAddressing[String, Int, String]) = {
    val rdmInMemoryPG1 = new InMemoryPeerGroup[Int, HeaderAddressing.Header[String, String]](Random.nextInt())(n)
    val rdmInMemoryPG2 = new InMemoryPeerGroup[Int, HeaderAddressing.Header[String, String]](Random.nextInt())(n)
    val peer1 = new HeaderAddressing[String, Int, String]("Ailce", rdmInMemoryPG1) {
      override def underlying(address: String): Int = {
        if (address == "Alice") rdmInMemoryPG1.processAddress
        else rdmInMemoryPG2.processAddress
      }
    }
    val peer2 = new HeaderAddressing[String, Int, String]("Bob", rdmInMemoryPG2) {
      override def underlying(address: String): Int = {
        if (address == "Alice") rdmInMemoryPG1.processAddress
        else rdmInMemoryPG2.processAddress
      }
    }
    (peer1, peer2)
  }

  "Header addressing" should "send and receive messages" in {
    import monix.execution.Scheduler.Implicits.global
    val (alice, bob) = twoRandomPeerGroup()

    println(s"Alice: ${alice.processAddress}")
    println(s"Bob: ${bob.processAddress}")

    alice.initialize().evaluated
    bob.initialize().evaluated

    val alicesMessage = Random.alphanumeric.take(10).mkString
    val bobsMessage = Random.alphanumeric.take(10).mkString

    println(s"Alice's message: $alicesMessage")
    println(s"Bob's message: $bobsMessage")

    val bobReceived: Future[String] =
      bob.server().collectChannelCreated.mergeMap(channel => channel.in).headL.runAsync
    bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).runAsync)

    val aliceClient = alice.client(bob.processAddress).evaluated
    val aliceReceived = aliceClient.in.headL.runAsync
    aliceClient.sendMessage(alicesMessage).runAsync

    bobReceived.futureValue shouldBe alicesMessage
    aliceReceived.futureValue shouldBe bobsMessage

    println(s"Alice received: ${aliceReceived.futureValue}")
    println(s"Bob received: ${bobReceived.futureValue}")

    alice.shutdown().evaluated
    bob.shutdown().evaluated
  }

}
