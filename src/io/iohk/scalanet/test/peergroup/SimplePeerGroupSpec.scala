//package io.iohk.scalanet.peergroup
//
//import java.net.InetSocketAddress
//
//import io.iohk.decco.auto._
//import io.iohk.scalanet.NetUtils._
//import org.scalatest.FlatSpec
//import org.scalatest.Matchers._
//import org.scalatest.concurrent.ScalaFutures._
//import monix.execution.Scheduler.Implicits.global
//import org.scalatest.concurrent.ScalaFutures
//
//import scala.concurrent.Future
//import scala.concurrent.duration._
//import scala.util.Random
//
//class SimplePeerGroupSpec extends FlatSpec {
//
//  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 1 second, interval = 100 millis)
//
//  behavior of "SimplePeerGroup"
//
//  it should "send a message to itself" in new SimpleTerminalPeerGroups {
//    terminalPeerGroups.foreach { terminalGroup =>
//      withASimplePeerGroup(terminalGroup, "Alice") { alice =>
//        val message = Random.alphanumeric.take(1044).mkString
//        val messageReceivedF = alice.messageChannel[String].headL.runToFuture
//
//        alice.sendMessage("Alice", message).runToFuture.futureValue
//
//        val messageReceived = messageReceivedF.futureValue
//        messageReceived shouldBe (alice.processAddress, message)
//      }
//    }
//  }
//
//  it should "send and receive a message to another peer of SimplePeerGroup" in new SimpleTerminalPeerGroups {
//    terminalPeerGroups.foreach { terminalGroup =>
//      withTwoSimplePeerGroups(
//        terminalGroup,
//        List.empty[String],
//        "Alice",
//        "Bob"
//      ) { (alice, bob) =>
//        val message = "HI!! Alice"
//
//        val messageReceivedF = alice.messageChannel[String].headL.runToFuture
//
//        bob.sendMessage("Alice", message).runToFuture.futureValue
//
//        val messageReceived = messageReceivedF.futureValue
//        messageReceived shouldBe (bob.processAddress, message)
//
//        val aliceMessage = "HI!! Bob"
//        val messageReceivedByBobF = bob.messageChannel[String].headL.runToFuture
//
//        alice.sendMessage("Bob", aliceMessage).runToFuture.futureValue
//
//        val messageReceivedByBob = messageReceivedByBobF.futureValue
//        messageReceivedByBob shouldBe (alice.processAddress, aliceMessage)
//
//      }
//    }
//  }
//
//  it should "send a message to another peer's multicast address" in new SimpleTerminalPeerGroups {
//    terminalPeerGroups.foreach { terminalGroup =>
//      withTwoSimplePeerGroups(
//        terminalGroup,
//        List("news", "sports"),
//        "Alice",
//        "Bob"
//      ) { (alice, bob) =>
//        val message = "HI!! Alice"
//
//        val messageReceivedF = alice.messageChannel[String].headL.runToFuture
//        bob.sendMessage("Alice", message).runToFuture.futureValue
//        val messageReceived = messageReceivedF.futureValue
//        messageReceived shouldBe (bob.processAddress, message)
//
//        val messageReceivedByBobF = bob.messageChannel[String].headL.runToFuture
//        val aliceMessage = "HI!! Bob"
//        alice.sendMessage("Bob", aliceMessage).runToFuture.futureValue
//        val messageReceivedByBob = messageReceivedByBobF.futureValue
//        messageReceivedByBob shouldBe (alice.processAddress, aliceMessage)
//
//        val messageReceivedByBobNewsF = bob.messageChannel[String].headL.runToFuture
//        val messageNews = "Latest News"
//        alice.sendMessage("news", messageNews).runToFuture.futureValue
//        val messageReceivedByBobNews = messageReceivedByBobNewsF.futureValue
//        messageReceivedByBobNews shouldBe (alice.processAddress, messageNews)
//
//        val messageReceivedByBobSportsF = bob.messageChannel[String].headL.runToFuture
//        val messageSports = "Sports Updates"
//        alice.sendMessage("sports", messageSports).runToFuture.futureValue
//        val messageReceivedByBobSports = messageReceivedByBobSportsF.futureValue
//        messageReceivedByBobSports shouldBe (alice.processAddress, messageSports)
//
//      }
//    }
//  }
//
//  it should "send a message to 2 peers sharing a multicast address" in new SimpleTerminalPeerGroups {
//    terminalPeerGroups.foreach { terminalGroup =>
//      withThreeSimplePeerGroups(
//        terminalGroup,
//        List("news", "sports"),
//        "Alice",
//        "Bob",
//        "Charlie"
//      ) { (alice, bob, charlie) =>
//        val message = "HI!! Alice"
//
//        val messageReceivedF = alice.messageChannel[String].headL.runToFuture
//        val messageReceivedByBobF = bob.messageChannel[String].headL.runToFuture
//
//        bob.sendMessage("Alice", message).runToFuture.futureValue
//        val messageReceived = messageReceivedF.futureValue
//        messageReceived shouldBe (bob.processAddress, message)
//
//        val aliceMessage = "HI!! Bob"
//        alice.sendMessage("Bob", aliceMessage).runToFuture.futureValue
//        val messageReceivedByBob = messageReceivedByBobF.futureValue
//        messageReceivedByBob shouldBe (alice.processAddress, aliceMessage)
//
//        val messageReceivedByBobNewsF = bob.messageChannel[String].headL.runToFuture
//        val messageReceivedByCharlieNewsF = charlie.messageChannel[String].headL.runToFuture
//
//        val messageNews = "Latest News"
//        alice.sendMessage("news", messageNews).runToFuture.futureValue
//        val messageReceivedByBobNews = messageReceivedByBobNewsF.futureValue
//        messageReceivedByBobNews shouldBe (alice.processAddress, messageNews)
//        val messageReceivedByCharlieNews = messageReceivedByCharlieNewsF.futureValue
//        messageReceivedByCharlieNews shouldBe (alice.processAddress, messageNews)
//
//        val messageReceivedByBobSportsF = bob.messageChannel[String].headL.runToFuture
//        val messageReceivedByCharlieSportsF = charlie.messageChannel[String].headL.runToFuture
//
//        val messageSports = "Sports Updates"
//        alice.sendMessage("sports", messageSports).runToFuture.futureValue
//        val messageReceivedByBobSports = messageReceivedByBobSportsF.futureValue
//        messageReceivedByBobSports shouldBe (alice.processAddress, messageSports)
//        val messageReceivedByCharlieSports = messageReceivedByCharlieSportsF.futureValue
//        messageReceivedByCharlieSports shouldBe (alice.processAddress, messageSports)
//
//      }
//    }
//  }
//
//  trait SimpleTerminalPeerGroups {
//    val terminalPeerGroups = List(TcpTerminalPeerGroup, UdpTerminalPeerGroup)
//  }
//
//  private def withASimplePeerGroup(
//      underlyingTerminalGroup: SimpleTerminalPeerGroup,
//      a: String
//  )(testCode: SimplePeerGroup[String, InetSocketAddress] => Any): Unit = {
//    withSimplePeerGroups(underlyingTerminalGroup, a, List.empty[String])(groups => testCode(groups(0)))
//  }
//
//  private def withTwoSimplePeerGroups(
//      underlyingTerminalGroup: SimpleTerminalPeerGroup,
//      multiCastAddresses: List[String],
//      a: String,
//      b: String
//  )(
//      testCode: (
//          SimplePeerGroup[String, InetSocketAddress],
//          SimplePeerGroup[String, InetSocketAddress]
//      ) => Any
//  ): Unit = {
//
//    withSimplePeerGroups(underlyingTerminalGroup, a, multiCastAddresses, b)(groups => testCode(groups(0), groups(1)))
//  }
//
//  private def withThreeSimplePeerGroups(
//      underlyingTerminalGroup: SimpleTerminalPeerGroup,
//      multiCastAddresses: List[String],
//      a: String,
//      b: String,
//      c: String
//  )(
//      testCode: (
//          SimplePeerGroup[String, InetSocketAddress],
//          SimplePeerGroup[String, InetSocketAddress],
//          SimplePeerGroup[String, InetSocketAddress]
//      ) => Any
//  ): Unit = {
//
//    withSimplePeerGroups(underlyingTerminalGroup, a, multiCastAddresses, b, c)(
//      groups => testCode(groups(0), groups(1), groups(2))
//    )
//  }
//
//  private def withSimplePeerGroups(
//      underlyingTerminalGroup: SimpleTerminalPeerGroup,
//      bootstrapAddress: String,
//      multiCastAddresses: List[String],
//      addresses: String*
//  )(
//      testCode: Seq[SimplePeerGroup[String, InetSocketAddress]] => Any
//  ): Unit = {
//
//    val bootStrapTerminalGroup = randomTerminalPeerGroup(underlyingTerminalGroup)
//    val bootstrap = new SimplePeerGroup(
//      SimplePeerGroup.Config(bootstrapAddress, List.empty[String], Map.empty[String, InetSocketAddress]),
//      bootStrapTerminalGroup
//    )
//    bootstrap.initialize().runToFuture.futureValue
//
//    val otherPeerGroups = addresses
//      .map(
//        address =>
//          new SimplePeerGroup(
//            SimplePeerGroup.Config(
//              address,
//              multiCastAddresses,
//              Map(bootstrapAddress -> bootStrapTerminalGroup.processAddress)
//            ),
//            randomTerminalPeerGroup(underlyingTerminalGroup)
//          )
//      )
//      .toList
//
//    val futures: Seq[Future[Unit]] = otherPeerGroups.map(pg => pg.initialize().runToFuture)
//    Future.sequence(futures).futureValue
//
//    val peerGroups = bootstrap :: otherPeerGroups
//
//    try {
//      testCode(peerGroups)
//    } finally {
//      peerGroups.foreach(_.shutdown())
//    }
//  }
//}
