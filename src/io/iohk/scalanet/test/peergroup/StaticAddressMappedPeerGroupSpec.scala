package io.iohk.scalanet.peergroup

import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.Future
import scala.concurrent.duration._
import io.iohk.scalanet.TaskValues._

import scala.util.Random

class StaticAddressMappedPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 5 second, interval = 1000 millis)

  behavior of "StaticAddressMappedPeerGroup"

  it should "send and receive a message" in
    withTwoStaticPeerGroups(
      "Alice",
      "Bob"
    ) { (alice, bob) =>
      println(s"Alice is ${alice.processAddress}, bob is ${bob.processAddress}")
      val alicesMessage = Random.alphanumeric.take(1024).mkString
      val bobsMessage = Random.alphanumeric.take(1024).mkString

      bob.server().foreachL(channel => channel.sendMessage(bobsMessage).evaluated).runAsync
      val bobReceived: Future[String] = bob.server().mergeMap(channel => channel.in).headL.runAsync

      val aliceClient = alice.client(bob.processAddress).evaluated
      val aliceReceived = aliceClient.in.headL.runAsync
      aliceClient.sendMessage(alicesMessage).evaluated

      bobReceived.futureValue shouldBe alicesMessage
      aliceReceived.futureValue shouldBe bobsMessage
    }

  private def withTwoStaticPeerGroups(a: String, b: String)(
      testCode: (
          StaticAddressMappedPeerGroup[String, InetMultiAddress, String],
          StaticAddressMappedPeerGroup[String, InetMultiAddress, String]
      ) => Any
  ): Unit = {

    val underlying1 = randomTCPPeerGroup[String]
    val underlying2 = randomTCPPeerGroup[String]

    val routingTable = Map(a -> underlying1.processAddress, b -> underlying2.processAddress)

    val config1 = StaticAddressMappedPeerGroup.Config(a, routingTable)
    val config2 = StaticAddressMappedPeerGroup.Config(b, routingTable)

    val pg1 = PeerGroup.createOrThrow(new StaticAddressMappedPeerGroup(config1, underlying1), config1)
    val pg2 = PeerGroup.createOrThrow(new StaticAddressMappedPeerGroup(config2, underlying2), config2)

    try {
      testCode(pg1, pg2)
    } finally {
      pg1.shutdown()
      pg2.shutdown()
    }
  }
}
