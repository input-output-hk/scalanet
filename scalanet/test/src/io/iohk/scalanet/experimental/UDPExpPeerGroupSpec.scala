package io.iohk.scalanet.experimental

import io.iohk.decco.auto._
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.scalanet.NetUtils
import org.scalatest.FlatSpec
import org.scalatest.Matchers._

import scala.concurrent.duration._

import monix.execution.Scheduler.Implicits.global

import org.scalatest.concurrent.ScalaFutures._
import io.iohk.scalanet.TaskValues._

import scala.util.Random

class UDPExpPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "UDPExpPeerGroup"

  it should "send and receive messages" in {
    val aliceAddress = NetUtils.aRandomAddress()
    val bobAddress = NetUtils.aRandomAddress()

    println(s"Alice address: $aliceAddress")
    println(s"Bob address: $bobAddress")

    val alicesMessage: String = Random.alphanumeric.take(10).mkString
    val bobsMessage: String = Random.alphanumeric.take(10).mkString

    println(s"Alice message: $alicesMessage")
    println(s"bob message: $bobsMessage")

    val alice = new UDPExpPeerGroup[String](aliceAddress)
    val bob = new UDPExpPeerGroup[String](bobAddress)

    alice onMessageReception { envelope =>
      println("Alice received a message")
      envelope.msg shouldBe bobsMessage
    }

    bob onMessageReception { envelope =>
      println(s"Bob received a message")
      envelope.msg shouldBe alicesMessage
      println(s"Envelope: $envelope")
      envelope.channel.sendMessage(bobsMessage).evaluated
    }

    alice.connect().evaluated
    bob.connect().evaluated

    val aliceClient = alice.client(bob.processAddress).evaluated
    aliceClient.sendMessage(alicesMessage).evaluated

    Thread.sleep(1000)
    println("Second message from first client")
    aliceClient.sendMessage(alicesMessage).evaluated

    Thread.sleep(1000)
    println("Second client")
    val aliceClient2 = alice.client(bob.processAddress).evaluated
    println(s"Second channel to ${aliceClient2.to}")
    aliceClient2.sendMessage(alicesMessage).evaluated

    // As everything is non blocking we need to wait to avoid missing
    // exceptions
    Thread.sleep(1000)
    alice.shutdown().evaluated
    bob.shutdown().evaluated
  }

  it should "communicate with another library through UDP seamlessly" in {
    val aliceAddress = NetUtils.aRandomAddress()
    val bobAddress = NetUtils.aRandomAddress()

    println(s"Alice address: $aliceAddress")
    println(s"Bob address: $bobAddress")

    val alicesMessage: String = Random.alphanumeric.take(10).mkString
    val bobsMessage: String = Random.alphanumeric.take(10).mkString

    println(s"Alice message: $alicesMessage")
    println(s"bob message: $bobsMessage")

    var aliceReceived = 0
    var bobReceived = 0

    def clientCode(received: String): Unit = {
      aliceReceived += 1
      println(s"alice received message $aliceReceived")
      received shouldBe bobsMessage
    }

    val alice = new NettyUDPWrapper[String](aliceAddress)(clientCode)
    val bob = new UDPExpPeerGroup[String](bobAddress)

    bob onMessageReception { envelope =>
      bobReceived += 1
      println(s"Bob received message $bobReceived")
      envelope.msg shouldBe alicesMessage
      envelope.channel.sendMessage(bobsMessage).evaluated
    }

    bob.connect().evaluated

    println("first message")
    alice.sendMessage(bobAddress, alicesMessage)
    println("second message")
    alice.sendMessage(bobAddress, alicesMessage)
    println("third message")
    alice.sendMessage(bobAddress, alicesMessage)

    Thread.sleep(2000)
    println(aliceReceived)
    println(bobReceived)
    alice.shutDown()
    bob.shutdown().evaluated
  }
}
