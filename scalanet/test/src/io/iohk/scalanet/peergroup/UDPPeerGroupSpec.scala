package io.iohk.scalanet.peergroup

import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import io.iohk.scalanet.NetUtils._

import scala.concurrent.duration._
import io.iohk.decco.auto._
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.Codec
import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.external.NettyUDPClient
import io.iohk.scalanet.peergroup.ControlEvent.InitializationError
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures._
import io.iohk.scalanet.peergroup.PeerGroup.MessageMTUException
import io.iohk.scalanet.peergroup.StandardTestPack.messagingTest
import org.scalatest.RecoverMethods._

import scala.concurrent.{Await, Future}

class UDPPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "UDPPeerGroup"

  it should "report an error for sending a message greater than the MTU" in
    withARandomUDPPeerGroup[Array[Byte]] { alice =>
      val address = InetMultiAddress(NetUtils.aRandomAddress())
      val invalidMessage = NetUtils.randomBytes(16777216)
      val messageSize = Codec[Array[Byte]].encode(invalidMessage).capacity()

      val error = recoverToExceptionIf[MessageMTUException[InetMultiAddress]] {
        alice.client(address).flatMap(channel => channel.sendMessage(invalidMessage)).runAsync
      }.futureValue

      error.size shouldBe messageSize
    }

  it should "send and receive a message" in withTwoRandomUDPPeerGroups[String] { (alice, bob) =>
    messagingTest(alice, bob)
  }

  it should "shutdown cleanly" in {
    val pg1 = randomUDPPeerGroup[String]
    isListeningUDP(pg1.config.bindAddress) shouldBe true

    pg1.shutdown().runAsync.futureValue

    isListeningUDP(pg1.config.bindAddress) shouldBe false
  }

  it should "throw InitializationError when port already in use" in {
    val address = aRandomAddress()
    val pg1 = new UDPPeerGroup[String](UDPPeerGroup.Config(address))
    val pg2 = new UDPPeerGroup[String](UDPPeerGroup.Config(address))

    Await.result(pg1.initialize().runAsync, 10 seconds)
    assertThrows[InitializationError] {
      Await.result(pg2.initialize().runAsync, 10 seconds)
    }
    pg1.shutdown().runAsync.futureValue
  }

  it should "communicate seamlessly with other UDP implementations" in {
    val aliceNettyAddress = aRandomAddress()
    val aliceScalanetAddress = aRandomAddress()
    val bobAddress = aRandomAddress()
    println(s"Alice netty address: $aliceNettyAddress")
    println(s"Alice scalanet address: $aliceScalanetAddress")
    println(s"Bob address: $bobAddress")

    val alicesNettyMessage = "netty message" // Random.alphanumeric.take(10).mkString
    val alicesScalanetMessage = "scalanet message" // Random.alphanumeric.take(10).mkString
    val bobsFirstMessage = "bob message 1" // Random.alphanumeric.take(10).mkString
    val bobsSecondMessage = "bob message 2" // Random.alphanumeric.take(10).mkString
    println(s"Alice netty message: $alicesNettyMessage")
    println(s"Alice scalanet message: $alicesScalanetMessage")
    println(s"Bob first message: $bobsFirstMessage")
    println(s"Bob second message: $bobsSecondMessage")

    var nettyReceived: Int = 0
    var scalanetReceived: Int = 0

    def aliceNettyCode(m: String): Unit = {
      println("Alice netty client received a message")
      m shouldBe bobsSecondMessage
      nettyReceived += 1
    }

    val aliceNetty = new NettyUDPClient[String](aliceNettyAddress)(aliceNettyCode)
    val aliceScalanet = new UDPPeerGroup[String](UDPPeerGroup.Config(aliceScalanetAddress))
    val bob = new UDPPeerGroup[String](UDPPeerGroup.Config(bobAddress))

    aliceScalanet.initialize().evaluated
    bob.initialize().evaluated

    def serverCode[A, M](bob: PeerGroup[A, M], replyWith: M): Future[M] = {
      for {
        channel <- bob.server().collectChannelCreated.headL.runAsync
        m <- channel.in.headL.runAsync
      } yield {
        println(s"Bob received a message: $m from ${channel.to}")
        channel.sendMessage(replyWith).runAsync
        println(s"Bob replied to ${channel.to} with $replyWith")
        m
      }
    }

    val bobMessageFromScalanetClient = serverCode(bob, bobsFirstMessage)
    val aliceScalanetClient = aliceScalanet.client(bob.processAddress).evaluated
    aliceScalanetClient.in foreach { _ =>
      scalanetReceived += 1
    }
    aliceScalanet.server().foreach(_ => println("Alice received a channel"))
    val aliceReceived = aliceScalanetClient.in.headL.runAsync
    aliceScalanetClient.sendMessage(alicesScalanetMessage).runAsync
    bobMessageFromScalanetClient.futureValue shouldBe alicesScalanetMessage
    aliceReceived.futureValue shouldBe bobsFirstMessage

    Thread.sleep(1000)
    val bobMessageFromNettyClient = serverCode(bob, bobsSecondMessage)
    aliceNetty.sendMessage(bob.processAddress.inetSocketAddress, alicesNettyMessage)
    bobMessageFromNettyClient.futureValue shouldBe alicesNettyMessage

    Thread.sleep(1000)
    (nettyReceived, scalanetReceived) shouldBe (1, 1)

    Thread.sleep(1000)
    aliceNetty.shutDown()
    aliceScalanet.shutdown()
    bob.shutdown()
  }

  it should "receive consecutive messages from other UDP implementations in a single channel" in {
    val aliceNettyAddress = aRandomAddress()
    val bobAddress = aRandomAddress()
    println(s"Alice netty address: $aliceNettyAddress")
    println(s"Bob address: $bobAddress")

    val alicesFirstNettyMessage = "netty message 1"
    val alicesSecondNettyMessage = "netty message 2"
    val bobsMessage = "bob message"
    println(s"Netty first message: $alicesFirstNettyMessage")
    println(s"Netty second message: $alicesSecondNettyMessage ")
    println(s"Bob second message: $bobsMessage")

    def aliceNettyCode(m: String): Unit = {
      println("Alice netty client received a message")
      m shouldBe bobsMessage
    }

    val aliceNetty = new NettyUDPClient[String](aliceNettyAddress)(aliceNettyCode)
    val bob = new UDPPeerGroup[String](UDPPeerGroup.Config(bobAddress))

    bob.initialize().evaluated

    var bobsMsgCount = 0
    val channelFuture = bob.server().collectChannelCreated.headL.runAsync
    channelFuture map { channel =>
      channel.in.foreach { m =>
        bobsMsgCount += 1
        println(s"Bob received a message: $m from ${channel.to}")
        channel.sendMessage(bobsMessage).runAsync
        println(s"Bob replied to ${channel.to} with $bobsMessage")
        if (bobsMsgCount == 1) {
          m shouldBe alicesFirstNettyMessage
        } else {
          m shouldBe alicesSecondNettyMessage
        }
      }
    }

    aliceNetty.sendMessage(bob.processAddress.inetSocketAddress, alicesFirstNettyMessage)
    aliceNetty.sendMessage(bob.processAddress.inetSocketAddress, alicesSecondNettyMessage)

    Thread.sleep(1000)
    bobsMsgCount shouldBe 2

    Thread.sleep(1000)
    aliceNetty.shutDown()
    bob.shutdown().evaluated
  }

}
