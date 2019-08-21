package io.iohk.scalanet.experimental

import io.iohk.decco.auto._
import io.iohk.scalanet.codec.FramingCodec
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.Codec
import io.iohk.scalanet.NetUtils
import org.scalatest.FlatSpec
import org.scalatest.Matchers._

import scala.concurrent.duration._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures._
import io.iohk.scalanet.TaskValues._

import scala.util.Random

class TCPExpPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig = PatienceConfig(5 seconds)
  implicit val codec = new FramingCodec(Codec[String])

  behavior of "TCPExpPeerGroup"

  it should "send and receive messages" in {
    val aliceAddress = NetUtils.aRandomAddress()
    val bobAddress = NetUtils.aRandomAddress()

    println(s"Alice address: $aliceAddress")
    println(s"Bob address: $bobAddress")

    val alicesMessage: String = Random.alphanumeric.take(1024).mkString
    val bobsMessage: String = Random.alphanumeric.take(1024).mkString

    println(s"Alice message: $alicesMessage")
    println(s"bob message: $bobsMessage")

    val alice = new TCPExpPeerGroup[String](aliceAddress)
    val bob = new TCPExpPeerGroup[String](bobAddress)

    alice onConnectionArrival { _ =>
      throw new Exception("Alice should receive no connection")
    }

    bob onConnectionArrival { connection =>
      println(s"Bob received a connection from ${connection.underlyingAddress}")
      connection.underlyingAddress.getAddress shouldBe aliceAddress.getAddress
    }

    alice onMessageReception { envelope =>
      println("Alice received a message")
      envelope.msg shouldBe bobsMessage
    }

    bob onMessageReception { envelope =>
      println(s"Bob received a message")
      envelope.msg shouldBe alicesMessage
      envelope.channel.sendMessage(bobsMessage).evaluated
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
