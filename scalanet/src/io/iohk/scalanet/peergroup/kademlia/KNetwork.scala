package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.KMessage.{KRequest, KResponse}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.{Channel, PeerGroup}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

trait KNetwork[A] {

  /**
    * Server side FIND_NODES handler.
    * @return An Observable for receiving FIND_NODES requests.
    *         Each element contains a tuple consisting of a FIND_NODES request
    *         with a function for accepting the required NODES response.
    */
  def findNodes: Observable[(FindNodes[A], Nodes[A] => Task[Unit])]

  /**
    * Send a FIND_NODES message to another peer.
    * @param to the peer to send the message to
    * @param request the FIND_NODES request
    * @return the future response
    */
  def findNodes(to: NodeRecord[A], request: FindNodes[A]): Task[Nodes[A]]

  /**
    * Server side PING handler.
    * @return An Observable for receiving PING requests.
    *         Each element contains a tuple consisting of a PING request
    *         with a function for accepting the require PONG response.
    */
  def ping: Observable[(Ping[A], Pong[A] => Task[Unit])]

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
      val requestTimeout: FiniteDuration = 3 seconds
  )(implicit scheduler: Scheduler)
      extends KNetwork[A] {

    override def findNodes: Observable[(FindNodes[A], Nodes[A] => Task[Unit])] = serverTemplate {
      case f @ FindNodes(_, _, _) => f
    }

    override def ping: Observable[(Ping[A], Pong[A] => Task[Unit])] = serverTemplate {
      case p @ Ping(_, _) => p
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
        response <- clientChannel.in
          .collect(pf)
          .headL
          .timeout(requestTimeout)
      } yield response
    }

    private def closeIfAnError(
        channel: Channel[A, KMessage[A]]
    )(maybeError: Option[Throwable]): Task[Unit] = {
      maybeError.fold(Task.unit)(_ => channel.close())
    }

    private def sendResponse(
        channel: Channel[A, KMessage[A]]
    ): KMessage[A] => Task[Unit] = { message =>
      channel
        .sendMessage(message)
        .timeout(requestTimeout)
        .doOnFinish(_ => channel.close())
    }

    private def serverTemplate[Request <: KRequest[A], Response <: KResponse[A]](
        pf: PartialFunction[KMessage[A], Request]
    ): Observable[(Request, KMessage[A] => Task[Unit])] = {
      peerGroup.server().collectChannelCreated.mapEval { channel: Channel[A, KMessage[A]] =>
        channel.in
          .collect(pf)
          .map(request => (request, sendResponse(channel)))
          .headL
          .timeout(requestTimeout)
          .doOnFinish(closeIfAnError(channel))
      }
    }
  }
}
