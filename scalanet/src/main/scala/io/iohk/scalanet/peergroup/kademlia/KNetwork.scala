package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.KMessage.{KRequest, KResponse}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.kademlia.KRouterBeta.BroadCastMessage
import io.iohk.scalanet.peergroup.{Channel, InetMultiAddress, PeerGroup, TCPPeerGroup}
import monix.eval.Task
import monix.reactive.Observable

trait KNetwork[A] {

  /**
    * Server side requests stream.
    * @return An Observable for receiving FIND_NODES and PING requests.
    *         Each element contains a tuple consisting of a request
    *         with a function for accepting the required response.
    *         With current conventions, it is mandatory to provide
    *         Some(response) or None for all request types, in order that the
    *         implementation can close the channel.
    */
  def kRequests: Observable[(KRequest[A], Option[KResponse[A]] => Task[Unit])]

  /**
    * Send a FIND_NODES message to another peer.
    * @param to the peer to send the message to
    * @param request the FIND_NODES request
    * @return the future response
    */
  def findNodes(to: NodeRecord[A], request: FindNodes[A]): Task[Nodes[A]]

  /**
    * Send a PING message to another peer.
    * @param to the peer to send the message to
    * @param request the PING request
    * @return the future response
    */
  def ping(to: NodeRecord[A], request: Ping[A]): Task[Pong[A]]
}

object KNetwork {

  import scala.concurrent.duration._

  class KNetworkScalanetImpl[A](
      val peerGroup: PeerGroup[A, KMessage[A]],
      val requestTimeout: FiniteDuration = 10 seconds
  ) extends KNetwork[A] {

    override lazy val kRequests: Observable[(KRequest[A], Option[KResponse[A]] => Task[Unit])] = {
      peerGroup
        .server()
        .refCount
        .collectChannelCreated
        .mapEval { channel: Channel[A, KMessage[A]] =>
          channel.in.refCount
            .collect { case r: KRequest[A] => r }
            .map(request => Some((request, sendOptionalResponse(channel))))
            .headL
            .timeout(requestTimeout)
            .onErrorHandle(closeTheChannel(channel))
        }
        .collect { case Some(thing) => thing }
    }

    override def findNodes(to: NodeRecord[A], request: FindNodes[A]): Task[Nodes[A]] = {
      requestTemplate(to, request, { case n @ Nodes(_, _, _) => n })
    }

    override def ping(to: NodeRecord[A], request: Ping[A]): Task[Pong[A]] = {
      requestTemplate(to, request, { case p @ Pong(_, _) => p })
    }

    private def requestTemplate[Request <: KRequest[A], Response <: KResponse[A]](
        to: NodeRecord[A],
        message: Request,
        pf: PartialFunction[KMessage[A], Response]
    ): Task[Response] = {
      peerGroup
        .client(to.routingAddress)
        .bracket { clientChannel =>
          sendRequest(message, clientChannel, pf)
        } { clientChannel =>
          clientChannel.close()
        }
    }

    private def sendRequest[Request <: KRequest[A], Response <: KResponse[A]](
        message: Request,
        clientChannel: Channel[A, KMessage[A]],
        pf: PartialFunction[KMessage[A], Response]
    ): Task[Response] = {
      for {
        _ <- clientChannel.sendMessage(message).timeout(requestTimeout)
        response <- clientChannel.in.refCount
          .collect(pf)
          .headL
          .timeout(requestTimeout)
      } yield response
    }

    private def closeTheChannel[Request <: KRequest[A]](
        channel: Channel[A, KMessage[A]]
    )(error: Throwable): Option[(Request, Option[KMessage[A]] => Task[Unit])] = {
      channel.close()
      None
    }

    private def sendResponse(
        channel: Channel[A, KMessage[A]]
    ): KMessage[A] => Task[Unit] = { message =>
      channel
        .sendMessage(message)
        .timeout(requestTimeout)
        .doOnFinish(_ => channel.close())
    }

    private def sendOptionalResponse(
        channel: Channel[A, KMessage[A]]
    ): Option[KMessage[A]] => Task[Unit] = { maybeMessage =>
      maybeMessage.fold(channel.close())(sendResponse(channel))
    }
  }


}
