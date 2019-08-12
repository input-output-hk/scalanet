package io.iohk.scalanet.peergroup

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils._
import io.iohk.scalanet.peergroup.SimplePeerGroup.ControlMessage
import io.iohk.scalanet.peergroup.StandardTestPack.{
  messageToSelfTest,
  messagingTest,
  simpleMulticastTest,
  twoWayMulticastTest
}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.Future
import scala.concurrent.duration._

class SimplePeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 2 seconds)

  behavior of "SimplePeerGroup"

  "SimplePeerGroup" should "send a message to itself" in withASimplePeerGroup(
    "Alice"
  ) { alice =>
    messageToSelfTest(alice)
  }

  it should "send and receive a message to another peer of SimplePeerGroup" in withTwoSimplePeerGroups(
    Set.empty,
    "Alice",
    "Bob"
  ) { (alice, bob) =>
    messagingTest(alice, bob)
  }

  it should "send a message to another peer's multicast address" in withTwoSimplePeerGroups(
    multiCastAddresses = Set("news", "sports"),
    a = "Alice",
    b = "Bob"
  ) { (alice, bob) =>
    simpleMulticastTest(alice, bob, "news")
  }

  it should "send a message to 2 peers sharing a multicast address" in
    withThreeSimplePeerGroups(Set("news", "sports"), "Alice", "Bob", "Charlie") { (alice, bob, charlie) =>
      twoWayMulticastTest(alice, bob, charlie, "news")
    }

  private def withASimplePeerGroup(a: String)(
      testCode: SimplePeerGroup[String, InetMultiAddress, String] => Any
  ): Unit = {
    withSimplePeerGroups(a, Set.empty)(groups => testCode(groups(0)))
  }

  private def withTwoSimplePeerGroups(multiCastAddresses: Set[String], a: String, b: String)(
      testCode: (
          SimplePeerGroup[String, InetMultiAddress, String],
          SimplePeerGroup[String, InetMultiAddress, String]
      ) => Any
  ): Unit = {

    withSimplePeerGroups(a, multiCastAddresses, b)(
      groups => testCode(groups(0), groups(1))
    )
  }

  private def withThreeSimplePeerGroups(multiCastAddresses: Set[String], a: String, b: String, c: String)(
      testCode: (
          SimplePeerGroup[String, InetMultiAddress, String],
          SimplePeerGroup[String, InetMultiAddress, String],
          SimplePeerGroup[String, InetMultiAddress, String]
      ) => Any
  ): Unit = {

    withSimplePeerGroups(a, multiCastAddresses, b, c)(
      groups => testCode(groups(0), groups(1), groups(2))
    )
  }

  type UnderlyingMessage =
    Either[ControlMessage[String, InetMultiAddress], String]

  private def withSimplePeerGroups(bootstrapAddress: String, multiCastAddresses: Set[String], addresses: String*)(
      testCode: Seq[SimplePeerGroup[String, InetMultiAddress, String]] => Any
  ): Unit = {

    val bootStrapTerminalGroup = randomUDPPeerGroup[UnderlyingMessage]

    val bootstrap = new SimplePeerGroup(
      SimplePeerGroup.Config(
        bootstrapAddress,
        multiCastAddresses,
        Map.empty[String, InetMultiAddress]
      ),
      bootStrapTerminalGroup
    )
    bootstrap.initialize().runToFuture.futureValue

    val otherPeerGroups = addresses
      .map(
        address =>
          new SimplePeerGroup(
            SimplePeerGroup.Config(
              address,
              multiCastAddresses,
              Map(bootstrapAddress -> bootStrapTerminalGroup.processAddress)
            ),
            randomUDPPeerGroup[UnderlyingMessage]
          )
      )
      .toList

    val futures: Seq[Future[Unit]] =
      otherPeerGroups.map(pg => pg.initialize().runToFuture)
    Future.sequence(futures).futureValue

    val peerGroups = bootstrap :: otherPeerGroups

    try {
      testCode(peerGroups)
    } finally {
      peerGroups.foreach(_.shutdown())
    }
  }
}
