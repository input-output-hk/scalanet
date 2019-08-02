//package io.iohk.scalanet.peergroup
//
//import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
//import io.iohk.decco.Codec
//import io.iohk.decco.auto._
//import io.iohk.scalanet.NetUtils._
//import io.iohk.scalanet.TaskValues._
//import io.iohk.scalanet.codec.FramingCodec
//import io.iohk.scalanet.peergroup.PeerGroup.ServerEvent._
//import monix.execution.Scheduler.Implicits.global
//import org.scalatest.Matchers._
//import org.scalatest.concurrent.ScalaFutures
//import org.scalatest.concurrent.ScalaFutures._
//import org.scalatest.{BeforeAndAfterAll, FlatSpec}
//
//import scala.concurrent.duration._
//
//class TCPPeerGroupTestSpec extends FlatSpec with BeforeAndAfterAll {
//
//  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(5 seconds)
//
//  implicit val codec = new FramingCodec(Codec[String])
//
//  behavior of "TCPPeerGroup"
//
//
//  it should "send and receive a message 3" in
//    with3RandomTCPPeerGroups[String] { (alice, bob, charlie) =>
//
//      val alicesMessage = "Hi Alice"
//      val bobsMessage = "Hi Bob"
//      val charliesMessage = "Hi Charlie"
//
//
//
//
//      val aliceClient = alice.client(bob.processAddress).evaluated
//      val aliceReceived = aliceClient.in.headL.runAsync
//
//      //bob.server().collectChannelCreated.foreach(channel => channel.sendMessage(bobsMessage).runAsync)
//      val bobReceived =
//        bob.server().collectChannelCreated.mergeMap(channel => channel.in)
//      aliceClient.sendMessage(alicesMessage).runAsync
//
//
//      val bobMsg1 = bobReceived.headL.runAsync
//      aliceClient.connect().runAsync
//
//      bob.server().connectChannels().foreachL(_ => Unit).runAsync
//      bob.connect().runAsync
//
//
//      bobMsg1.futureValue shouldBe alicesMessage
////      aliceReceived.futureValue shouldBe bobsMessage
//
//      val charlieClient = charlie.client(bob.processAddress).evaluated
//
//
//
//      charlieClient.sendMessage(charliesMessage).runAsync
//      val bobMsg2 = bobReceived.headL.runAsync
//
//      charlieClient.connect().runAsync
//      bob.server().connectChannels().foreachL(_ => Unit).runAsync
//      bob.connect().runAsync.futureValue
//
//      bobMsg2.futureValue shouldBe charliesMessage
//    }
//
//
//}
