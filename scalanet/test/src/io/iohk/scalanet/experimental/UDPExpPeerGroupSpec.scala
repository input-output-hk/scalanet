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

    val alicesMessage: String = Random.alphanumeric.take(1024).mkString
    val bobsMessage: String = Random.alphanumeric.take(1024).mkString

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
      // note that UDP does not provide reliable addressing, that is why we can not
      // use the source channel
      val bobClient = bob.client(aliceAddress).evaluated
      bobClient.sendMessage(bobsMessage).evaluated
    }

    alice.connect().evaluated
    bob.connect().evaluated

    val aliceClient = alice.client(bob.processAddress).evaluated
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
