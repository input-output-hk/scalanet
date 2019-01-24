package io.iohk.network.discovery

import java.net.InetSocketAddress

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.{actor => untyped}
import akka.io.{IO, Udp}
import io.iohk.network.discovery.DiscoveryListener._
import io.iohk.codecs.nio.NioCodec
import akka.actor.typed.scaladsl.adapter._
import io.iohk.codecs.utils._
class UDPBridge(
    discoveryListener: ActorRef[DiscoveryListenerRequest],
    codec: NioCodec[DiscoveryWireMessage],
    udpBinder: untyped.ActorContext => Unit
) extends untyped.Actor {

  udpBinder(context)

  override def receive: Receive = {
    case Udp.Bound(local) =>
      discoveryListener ! Forward(Ready(local))
      context.become(ready(sender()))
  }

  private def ready(socket: untyped.ActorRef): Receive = {
    case Udp.Received(data, remote) =>
      codec.decode(data.toByteBuffer).foreach(packet => discoveryListener ! Forward(MessageReceived(packet, remote)))

    case SendMessage(packet, to) =>
      val encodedPacket = codec.encode(packet).toByteString
      socket ! Udp.Send(encodedPacket, to)
  }
}

object UDPBridge {
  def creator(config: DiscoveryConfig, codec: NioCodec[DiscoveryWireMessage])(
      context: ActorContext[DiscoveryListenerRequest]
  ): untyped.ActorRef =
    context.actorOf(
      untyped.Props(
        new UDPBridge(
          context.asScala.self,
          codec,
          context =>
            IO(Udp)(context.system)
              .tell(Udp.Bind(context.self, new InetSocketAddress(config.interface, config.port)), context.self)
        )
      )
    )
}
