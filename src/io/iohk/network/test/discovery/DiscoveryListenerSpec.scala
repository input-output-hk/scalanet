package io.iohk.network.discovery

import java.net.{InetAddress, InetSocketAddress}

import akka.actor.typed.scaladsl.adapter._
import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import akka.util.ByteString
import akka.{actor => untyped, testkit => untypedKit}
import io.iohk.network.discovery.DiscoveryListener._
import io.iohk.codecs.{Decoder, Encoder}
import io.iohk.network.{Capabilities, NodeInfo}
import io.iohk.network.test.utils.StopAfterAll
import org.scalatest.WordSpecLike

import scala.concurrent.duration._

class DiscoveryListenerSpec
    extends untypedKit.TestKit(untyped.ActorSystem("DiscoveryListenerSpec"))
    with WordSpecLike
    with StopAfterAll {

  val discAddressNodeA = new InetSocketAddress(InetAddress.getByAddress(Array(0, 0, 0, 1)), 1001)
  val serverAddressNodeA = new InetSocketAddress(InetAddress.getByAddress(Array(0, 0, 1, 1)), 2001)
  val discAddressNodeB = new InetSocketAddress(InetAddress.getByAddress(Array(0, 0, 0, 2)), 1002)
  val serverAddressNodeB = new InetSocketAddress(InetAddress.getByAddress(Array(0, 0, 2, 2)), 2002)

  val nodeA = NodeInfo(ByteString("1"), discAddressNodeA, serverAddressNodeA, Capabilities(1))
  val nodeB = NodeInfo(ByteString("2"), discAddressNodeB, serverAddressNodeB, Capabilities(1))

  val nonce = ByteString("nonce")
  val pingToken = ByteString("ping-token")
  val seekToken = ByteString("seek-token")
  val ping = Ping(1, nodeB, 0, nonce)
  val pong = Pong(nodeB, pingToken, 0)
  val seek = Seek(Capabilities(1), 10, 0, nonce)
  val neighbors = Neighbors(Capabilities(1), seekToken, 10, Seq(), 0)
  val allMessages = Seq(ping, pong, seek, neighbors)

  val encDec = new Encoder[DiscoveryWireMessage, ByteString] with Decoder[ByteString, DiscoveryWireMessage] {
    override def encode(t: DiscoveryWireMessage): ByteString = {
      t match {
        case _: Ping => ByteString(0x01)
        case _: Pong => ByteString(0x02)
        case _: Seek => ByteString(0x03)
        case _: Neighbors => ByteString(0x04)
      }
    }

    override def decode(u: ByteString): Option[DiscoveryWireMessage] = {
      Some(u match {
        case 0x01 +: rest => ping
        case 0x02 +: rest => pong
        case 0x03 +: rest => seek
        case 0x04 +: rest => neighbors
      })
    }
  }

  val discoveryConfig = DiscoveryConfig(
    true,
    "0.0.0.0",
    1000,
    Set(),
    10,
    10,
    10,
    5 seconds,
    5 seconds,
    10 seconds,
    10,
    true,
    10 seconds
  )

  "A DiscoveryListener" must {
    "receive and send UDP packets and forward them to the parent" in {
      val udpProbe = untypedKit.TestProbe()
      val behavior =
        DiscoveryListener.behavior(discoveryConfig, _ => udpProbe.ref)
      val actor = BehaviorTestKit(behavior)
      val inbox = TestInbox[DiscoveryListenerResponse]()
      actor.run(Start(inbox.ref))
      actor.run(Forward(Ready(discAddressNodeA)))
      inbox.expectMessage(Ready(discAddressNodeA))
      allMessages.foreach(message => {
        val wrappedMsg = SendMessage(message, discAddressNodeB)
        actor.run(wrappedMsg)
        udpProbe.expectMsg(wrappedMsg)
        val messageReceived = Forward(MessageReceived(message, discAddressNodeB))
        actor.run(messageReceived)
        inbox.expectMessage(messageReceived.response)
      })
    }
    "not bind to the UDP port at startup" in {
      val behavior =
        DiscoveryListener.behavior(discoveryConfig, _ => fail("UDP actor was created"))
      system.spawn(behavior, "testing-DiscoveryListener")
    }
    "ignore messages before the connection is ready" in {
      val udpProbe = untypedKit.TestProbe()
      val behavior =
        DiscoveryListener.behavior(discoveryConfig, _ => udpProbe.ref)
      val tk = BehaviorTestKit(behavior)
      allMessages.foreach(msg => {
        tk.run(SendMessage(msg, discAddressNodeB))
        //TODO: fix expectation when PR gets into a release. https://github.com/akka/akka/pull/25062
        //tk.expectEffect(Effects.noEffects())
      })
      val inbox = TestInbox[DiscoveryListenerResponse]()
      tk.run(Start(inbox.ref))
      allMessages.foreach(msg => {
        tk.run(SendMessage(msg, discAddressNodeB))
        //TODO: fix expectation when PR gets into a release. https://github.com/akka/akka/pull/25062
        //tk.expectEffect(Effects.noEffects())
      })
    }
    "throw an exception if there's a double start msg" in {
      intercept[IllegalStateException] {
        val udpProbe = untypedKit.TestProbe()
        val behavior =
          DiscoveryListener.behavior(discoveryConfig, _ => udpProbe.ref)
        val tk = BehaviorTestKit(behavior)
        val inbox = TestInbox[DiscoveryListenerResponse]()
        tk.run(Start(inbox.ref))
        tk.run(Start(inbox.ref))
      }
    }
    "ignore start when the connection is ready" in {
      val udpProbe = untypedKit.TestProbe()
      val behavior =
        DiscoveryListener.behavior(discoveryConfig, _ => udpProbe.ref)
      val tk = BehaviorTestKit(behavior)
      val inbox = TestInbox[DiscoveryListenerResponse]()
      tk.run(Start(inbox.ref))
      tk.run(Forward(Ready(discAddressNodeA)))
      tk.run(Start(inbox.ref))
      //TODO: fix expectation when PR gets into a release. https://github.com/akka/akka/pull/25062
      //tk.expectEffect(Effects.noEffects())
    }
  }
}
