package io.iohk.network

import java.net.InetSocketAddress

import akka.actor.typed.ActorRef
import akka.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import akka.util.ByteString
import io.iohk.network.NodeStatus._
import org.scalatest.{FlatSpec, MustMatchers}

class NodeStatusSpec extends FlatSpec with MustMatchers {

  class ReadyNodeStatus {
    def nodeState = NodeState(
      ByteString("key"),
      ServerStatus.NotListening,
      ServerStatus.NotListening,
      Capabilities(1)
    )

    def getSubscribers: Seq[ActorRef[StateUpdated]] = Seq()

    def getBehavior = NodeStatus.nodeState(nodeState, getSubscribers)

    def getActor = BehaviorTestKit(getBehavior)
  }

  behavior of "NodeStatus"

  it should "start with the vanilla node state" in new ReadyNodeStatus {
    //More than a standalone test, this is an assumption made by all tests below.
    //Hence, factoring it out to a single check.
    val actor = getActor
    val inbox = TestInbox[NodeState]()
    actor.run(RequestState(inbox.ref))
    inbox.expectMessage(nodeState)
  }
  it should "update its state" in new ReadyNodeStatus {
    val actor = getActor
    val inbox = TestInbox[NodeState]()
    nodeState.capabilities must not be Capabilities(2)
    actor.run(UpdateState(Capabilities(2)))
    actor.run(RequestState(inbox.ref))
    inbox.expectMessage(nodeState.copy(capabilities = Capabilities(2)))
  }
  it should "update the discovery status" in new ReadyNodeStatus {
    val actor = getActor
    val inbox = TestInbox[StateUpdated]()
    val addr = new InetSocketAddress(1)
    actor.run(Subscribe(inbox.ref))
    inbox.expectMessage(StateUpdated(nodeState))
    actor.run(UpdateDiscoveryStatus(ServerStatus.Listening(addr)))
    inbox.expectMessage(StateUpdated(nodeState.copy(discoveryStatus = ServerStatus.Listening(addr))))
  }
  it should "update the server status" in new ReadyNodeStatus {
    val actor = getActor
    val inbox = TestInbox[StateUpdated]()
    val addr = new InetSocketAddress(1)
    actor.run(Subscribe(inbox.ref))
    inbox.expectMessage(StateUpdated(nodeState))
    actor.run(UpdateServerStatus(ServerStatus.Listening(addr)))
    inbox.expectMessage(StateUpdated(nodeState.copy(serverStatus = ServerStatus.Listening(addr))))
  }

}
