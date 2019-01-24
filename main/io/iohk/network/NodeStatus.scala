package io.iohk.network

import java.net.InetSocketAddress

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.ByteString

sealed trait ServerStatus
object ServerStatus {
  case object NotListening extends ServerStatus
  case class Listening(address: InetSocketAddress) extends ServerStatus
}

object NodeStatus {

  case class NodeState(
      key: ByteString,
      serverStatus: ServerStatus,
      discoveryStatus: ServerStatus,
      capabilities: Capabilities
  ) extends {

    val nodeId = key
  }

  case class StateUpdated(state: NodeState)

  sealed trait NodeStatusMessage
  case class RequestState(replyTo: ActorRef[NodeState]) extends NodeStatusMessage
  case class UpdateState(capabilities: Capabilities) extends NodeStatusMessage
  case class UpdateDiscoveryStatus(serverStatus: ServerStatus) extends NodeStatusMessage
  case class UpdateServerStatus(serverStatus: ServerStatus) extends NodeStatusMessage
  case class Subscribe(ref: ActorRef[StateUpdated]) extends NodeStatusMessage

  def nodeState(state: NodeState, subscribers: Seq[ActorRef[StateUpdated]]): Behavior[NodeStatusMessage] =
    Behaviors.setup { context =>
      def sendUpdate(newState: NodeState) = subscribers.foreach(_ ! StateUpdated(newState))

      Behaviors.receiveMessage { message =>
        message match {
          case request: RequestState =>
            request.replyTo ! state
            Behavior.same
          case update: UpdateState =>
            val newState = state.copy(capabilities = update.capabilities)
            sendUpdate(newState)
            nodeState(newState, subscribers)
          case update: UpdateDiscoveryStatus =>
            val newState = state.copy(discoveryStatus = update.serverStatus)
            sendUpdate(newState)
            nodeState(newState, subscribers)
          case update: UpdateServerStatus =>
            val newState = state.copy(serverStatus = update.serverStatus)
            sendUpdate(newState)
            nodeState(newState, subscribers)
          case subscribe: Subscribe =>
            subscribe.ref ! StateUpdated(state)
            nodeState(state, subscribers :+ subscribe.ref)
        }
      }
    }
}
