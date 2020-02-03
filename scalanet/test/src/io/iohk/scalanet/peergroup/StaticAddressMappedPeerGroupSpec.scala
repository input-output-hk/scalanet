package io.iohk.scalanet.peergroup

import io.iohk.scalanet.NetUtils._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FlatSpec
import org.scalatest.concurrent.ScalaFutures
import scodec.codecs.implicits.implicitStringCodec

import scala.concurrent.duration._

class StaticAddressMappedPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 5 second, interval = 1000 millis)

  behavior of "StaticAddressMappedPeerGroup"

  it should "send and receive a message" in
    withTwoStaticPeerGroups(
      "Alice",
      "Bob"
    ) { (alice, bob) =>
      StandardTestPack.messagingTest(alice, bob)
    }

  private def withTwoStaticPeerGroups(a: String, b: String)(
      testCode: (
          StaticAddressMappedPeerGroup[String, InetMultiAddress, String],
          StaticAddressMappedPeerGroup[String, InetMultiAddress, String]
      ) => Any
  ): Unit = {

    val underlying1 = randomUDPPeerGroup[String]
    val underlying2 = randomUDPPeerGroup[String]

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
