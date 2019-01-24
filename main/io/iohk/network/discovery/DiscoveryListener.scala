package io.iohk.network.discovery

import java.net.InetSocketAddress

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.{actor => untyped}

private[network] object DiscoveryListener {

  sealed trait DiscoveryListenerRequest

  case class Start(replyTo: ActorRef[DiscoveryListenerResponse]) extends DiscoveryListenerRequest

  case class SendMessage(message: DiscoveryWireMessage, to: InetSocketAddress) extends DiscoveryListenerRequest

  private[discovery] case class Forward(response: DiscoveryListenerResponse) extends DiscoveryListenerRequest

  sealed trait DiscoveryListenerResponse

  case class Ready(address: InetSocketAddress) extends DiscoveryListenerResponse

  case class MessageReceived(packet: DiscoveryWireMessage, from: InetSocketAddress) extends DiscoveryListenerResponse

  def behavior(
      discoveryConfig: DiscoveryConfig,
      udpBridgeCreator: (ActorContext[DiscoveryListenerRequest]) => untyped.ActorRef
  ): Behavior[DiscoveryListenerRequest] = {

    def initialState: Behavior[DiscoveryListenerRequest] = Behaviors.receivePartial {
      case (context, Start(replyTo)) => {
        val udpBridge = udpBridgeCreator(context)
        startingState(replyTo, udpBridge)
      }
      case (context, msg) => {
        context.log.warning(
          s"Ignoring discovery listener request $msg. " +
            s"The discovery listener is not yet initialzed. " +
            s"You need to send a Start message."
        )
        Behavior.same
      }
    }

    def startingState(
        replyTo: ActorRef[DiscoveryListenerResponse],
        udpBridge: untyped.ActorRef
    ): Behavior[DiscoveryListenerRequest] =
      Behaviors.setup { context =>
        Behaviors.receiveMessage {
          case Start(_) =>
            throw new IllegalStateException(
              s"Start has already been invoked on this listener by actor $replyTo. " +
                s"You can do this only once."
            )

          case Forward(ready: Ready) =>
            replyTo ! ready
            readyState(replyTo, udpBridge)

          case x =>
            context.log.warning(
              s"Ignoring discovery listener request $x. " +
                s"The discovery listener is starting. " +
                s"You need to await the Ready message."
            )
            Behavior.same
        }
      }

    def readyState(
        replyTo: ActorRef[DiscoveryListenerResponse],
        udpBridge: untyped.ActorRef
    ): Behavior[DiscoveryListenerRequest] =
      Behaviors.setup { context =>
        Behaviors.receiveMessage {
          case Forward(messageReceived: MessageReceived) =>
            replyTo ! messageReceived
            Behavior.same
          case sm: SendMessage =>
            udpBridge ! sm
            Behaviors.same
          case x =>
            context.log.warning(
              s"Ignoring discovery listener request $x. " +
                s"The discovery listener is already listening and ready."
            )
            Behaviors.same
        }
      }

    initialState
  }
}
