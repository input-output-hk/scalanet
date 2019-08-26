package io.iohk.scalanet.experimental.handlers

import java.net.InetSocketAddress

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.auto._
import io.iohk.scalanet.NetUtils
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.experimental.handlers.ExpAddressing.{AddressHeader, HeaderAddressingConfig}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.duration._
import scala.util.Random

class ExpAddressingSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(5 seconds)

  behavior of "ExpAddressing"

  it should "send and receive messages" in {
    val aliceAddress = NetUtils.aRandomAddress()
    val bobAddress = NetUtils.aRandomAddress()

    println(s"Alice address: $aliceAddress")
    println(s"Bob address: $bobAddress")

    val alicesMessage: String = Random.alphanumeric.take(1024).mkString
    val bobsMessage: String = Random.alphanumeric.take(1024).mkString

    println(s"Alice message: $alicesMessage")
    println(s"bob message: $bobsMessage")

    val aliceUnd = new UDPExpPeerGroup[AddressHeader[String, String]](aliceAddress)
    val bobUnd = new UDPExpPeerGroup[AddressHeader[String, String]](bobAddress)

    val alice =
      new ExpAddressing[String, InetSocketAddress, String](aliceUnd, HeaderAddressingConfig("alice")) {
        override def underlyingAddress(applicationAddress: String): InetSocketAddress =
          if (applicationAddress == "alice") aliceAddress else bobAddress
      }

    val bob =
      new ExpAddressing[String, InetSocketAddress, String](bobUnd, HeaderAddressingConfig("bob")) {
        override def underlyingAddress(applicationAddress: String): InetSocketAddress =
          if (applicationAddress == "alice") aliceAddress else bobAddress
      }

    alice onMessageReception { envelope =>
      println("Alice received a message")
      envelope.msg shouldBe bobsMessage
    }

    bob onMessageReception { envelope =>
      println(s"Bob received a message")
      envelope.msg shouldBe alicesMessage
      envelope.channel.sendMessage(bobsMessage)
    }

    alice.connect().evaluated
    bob.connect().evaluated

    val aliceClient = alice.client("bob").evaluated
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

}
