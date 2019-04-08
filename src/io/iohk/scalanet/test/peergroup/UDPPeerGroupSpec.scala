//package io.iohk.scalanet.peergroup
//
//import java.net.{BindException, InetSocketAddress}
//
//import io.iohk.scalanet.peergroup.UDPPeerGroup.Config
//import org.scalatest.EitherValues._
//import org.scalatest.FlatSpec
//import org.scalatest.Matchers._
//import io.iohk.scalanet.NetUtils._
//
//import scala.concurrent.Future
//import scala.concurrent.duration._
//import org.scalatest.concurrent.ScalaFutures._
//import io.iohk.decco.auto._
//import monix.execution.Scheduler.Implicits.global
//
//import scala.util.Random
//
//class UDPPeerGroupSpec extends FlatSpec {
//
//  implicit val patienceConfig = PatienceConfig(1 second)
//
//  behavior of "UDPPeerGroup"
//
//  it should "send and receive a large message of message" in withTwoRandomUDPPeerGroups { (pg1, pg2) =>
//    // 64 kilobytes is the theoretical maximum size of a complete IP datagram
//
//    val message = Random.alphanumeric.take(1024 * 5).mkString
//    println(message)
//    val pg1Channel = pg1.messageChannel[String]
//    val pg2Channel = pg2.messageChannel[String]
//    val pg2Msg: Future[(InetSocketAddress, String)] = pg2Channel.headL.runToFuture
//
//    pg1.sendMessage(pg2.config.bindAddress, message).runToFuture
//    pg2Msg.futureValue shouldBe (pg1.processAddress, message)
//
//    val pg1Msg: Future[(InetSocketAddress, String)] = pg1Channel.headL.runToFuture
//    pg2.sendMessage(pg1.config.bindAddress, message).runToFuture
//    pg1Msg.futureValue shouldBe (pg2.processAddress, message)
//  }
//
//  it should "send and receive a message" in withTwoRandomUDPPeerGroups { (pg1, pg2) =>
//    val pg1Channel = pg1.messageChannel[String]
//    val pg2Channel = pg2.messageChannel[String]
//    val pg2Msg: Future[(InetSocketAddress, String)] = pg2Channel.headL.runToFuture
//    val b = "Hello"
//
//    pg1.sendMessage(pg2.config.bindAddress, b).runToFuture
//    pg2Msg.futureValue shouldBe (pg1.processAddress, b)
//
//    val pg1Msg: Future[(InetSocketAddress, String)] = pg1Channel.headL.runToFuture
//    pg2.sendMessage(pg1.config.bindAddress, b).runToFuture
//    pg1Msg.futureValue shouldBe (pg2.processAddress, b)
//  }
//
//  it should "shutdown cleanly" in {
//    val pg1 = randomUDPPeerGroup
//    isListeningUDP(pg1.config.bindAddress) shouldBe true
//
//    pg1.shutdown().runToFuture.futureValue
//
//    isListeningUDP(pg1.config.bindAddress) shouldBe false
//  }
//
//  it should "support a throws create method" in withUDPAddressInUse { address =>
//    isListeningUDP(address) shouldBe true
//    val exception = the[IllegalStateException] thrownBy UDPPeerGroup.createOrThrow(Config(address))
//    exception.getCause shouldBe a[BindException]
//  }
//
//  it should "support an Either create method" in withUDPAddressInUse { address =>
//    isListeningUDP(address) shouldBe true
//    UDPPeerGroup.create(Config(address)).left.value.cause shouldBe a[BindException]
//  }
//}
