//package io.iohk.scalanet.peergroup
//
//import io.iohk.decco.auto._
//import io.iohk.scalanet.NetUtils._
//import monix.execution.Scheduler.Implicits.global
//import org.scalatest.FlatSpec
//import org.scalatest.Matchers._
//import org.scalatest.concurrent.ScalaFutures
//import org.scalatest.concurrent.ScalaFutures._
//
//import scala.concurrent.Future
//import scala.concurrent.duration._
//import io.iohk.scalanet.TaskValues._
//
//class SimplestPeerGroupSpec extends FlatSpec {
//
//  implicit val patienceConfig = ScalaFutures.PatienceConfig(timeout = 5 second, interval = 1000 millis)
//
//  behavior of "SimplePeerGroup"
//
//  it should "send and receive a message to another peer of SimplePeerGroup" in
//    withTwoSimplestPeerGroups(
//      "Alice",
//      "Bob"
//    ) { (alice, bob) =>
//      println(s"Alice is ${alice.processAddress}, Bob is ${bob.processAddress}")
//      val alicesMessage = "hi bob, from alice"
//      val bobsMessage = "hi alice, from bob"
//
//      val bobReceived: Future[String] = bob
//        .server()
//        .flatMap { channel =>
//          channel.in
//        }
//        .filter { msg =>
//          msg == alicesMessage
//        }
//        .headL
//        .runToFuture
//
//      bob.server().foreach(channel => channel.sendMessage(bobsMessage).evaluated)
//
//      val aliceClient = alice.client(bob.processAddress).evaluated
//      val aliceReceived = aliceClient.in
//        .filter { msg =>
//          msg == bobsMessage
//        }
//        .headL
//        .runToFuture
//      aliceClient.sendMessage(alicesMessage).evaluated
//
//      aliceReceived.futureValue shouldBe bobsMessage
//      bobReceived.futureValue shouldBe alicesMessage
//    }
//
//  private def withTwoSimplestPeerGroups(a: String, b: String)(
//      testCode: (
//          SimplestPeerGroup[String, InetMultiAddress, String],
//          SimplestPeerGroup[String, InetMultiAddress, String]
//      ) => Any
//  ): Unit = {
//
//    val underlying1 = randomTCPPeerGroup[Either[SimplestPeerGroup.ControlMessage[String, InetMultiAddress], String]]
//    val underlying2 = randomTCPPeerGroup[Either[SimplestPeerGroup.ControlMessage[String, InetMultiAddress], String]]
//
//    val routingTable = Map(a -> underlying1.processAddress, b -> underlying2.processAddress)
//
//    val simplest1 =
//      new SimplestPeerGroup[String, InetMultiAddress, String](SimplestPeerGroup.Config(a, routingTable), underlying1)
//    val simplest2 =
//      new SimplestPeerGroup[String, InetMultiAddress, String](SimplestPeerGroup.Config(b, routingTable), underlying2)
//
//    simplest1.initialize().evaluated
//    simplest2.initialize().evaluated
//    try {
//      testCode(simplest1, simplest2)
//    } finally {
//      simplest1.shutdown()
//      simplest2.shutdown()
//    }
//  }
//}
