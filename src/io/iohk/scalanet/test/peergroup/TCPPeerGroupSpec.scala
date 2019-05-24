//package io.iohk.scalanet.peergroup
//
//import io.iohk.decco.auto._
//import io.iohk.scalanet.NetUtils._
//import io.iohk.scalanet.TaskValues._
//import monix.execution.Scheduler.Implicits.global
//import org.scalatest.Matchers._
//import org.scalatest.concurrent.ScalaFutures
//import org.scalatest.concurrent.ScalaFutures._
//import org.scalatest.{BeforeAndAfterAll, FlatSpec}
//
//import scala.concurrent.Future
//import scala.concurrent.duration._
////import scala.util.Random
//class TCPPeerGroupSpec extends FlatSpec with BeforeAndAfterAll {
//
//  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(5 seconds)
//
//  behavior of "TCPPeerGroup"
//
//  it should "send and receive a message" in
//    withTwoRandomTCPPeerGroups[String] { (alice, bob) =>
//      println(s"Alice is ${alice.processAddress}, bob is ${bob.processAddress}")
//      val alicesMessage = "Hi Bob" // Random.alphanumeric.take(1024).mkString
//      val bobsMessage = "Hi Alice" //Random.alphanumeric.take(1024).mkString
//
//      bob.server().foreachL(channel => channel.sendMessage(bobsMessage).evaluated).runToFuture
//      val bobReceived: Future[String] = bob.server().mergeMap(channel => channel.in).headL.runToFuture
//
//      val aliceClient = alice.client(bob.processAddress).evaluated
//      val aliceReceived = aliceClient.in.headL.runToFuture
//      aliceClient.sendMessage(alicesMessage).evaluated
//
//      bobReceived.futureValue shouldBe alicesMessage
//      aliceReceived.futureValue shouldBe bobsMessage
//    }
//
//  it should "shutdown a TCPPeerGroup properly" in {
//    val tcpPeerGroup = randomTCPPeerGroup[String]
//    isListening(tcpPeerGroup.config.bindAddress) shouldBe true
//
//    tcpPeerGroup.shutdown().runToFuture.futureValue
//
//    isListening(tcpPeerGroup.config.bindAddress) shouldBe false
//  }
//
//  it should "report the same address for two inbound channels" in
//    withTwoRandomTCPPeerGroups[String] { (alice, bob) =>
//      val firstInbound = bob.server().headL.runToFuture
//      val secondInbound = bob.server().drop(1).headL.runToFuture
//
//      alice.client(bob.processAddress).evaluated
//      alice.client(bob.processAddress).evaluated
//
//      firstInbound.futureValue.to shouldBe alice.processAddress
//      secondInbound.futureValue.to shouldBe alice.processAddress
//    }
//}
