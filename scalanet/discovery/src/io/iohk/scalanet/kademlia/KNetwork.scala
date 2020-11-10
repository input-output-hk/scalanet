package io.iohk.scalanet.kademlia

import cats.implicits._
import io.iohk.scalanet.kademlia.KMessage.{KRequest, KResponse}
import io.iohk.scalanet.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.implicits._
import io.iohk.scalanet.peergroup.{Channel, PeerGroup}
import io.iohk.scalanet.peergroup.Channel.MessageReceived
import monix.eval.Task
import monix.reactive.Observable
import scala.util.control.NonFatal

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
      peerGroup: PeerGroup[A, KMessage[A]],
      requestTimeout: FiniteDuration = 3 seconds
  ) extends KNetwork[A] {

    override lazy val kRequests: Observable[(KRequest[A], Option[KResponse[A]] => Task[Unit])] = {
      peerGroup.serverEventObservable.collectChannelCreated
        .mergeMap {
          case (channel: Channel[A, KMessage[A]], release: Task[Unit]) =>
            // NOTE: We cannot use mapEval with a Task here, because that would hold up
            // the handling of further incoming requests. For example if instead of a
            // request we got an incoming "response" type message that the collect
            // discards, `headL` would eventually time out but while we wait for
            // that the next incoming channel would not be picked up.
            Observable.fromTask {
              channel.messageObservable
                .collect { case MessageReceived(req: KRequest[A]) => req }
                .headL
                .timeout(requestTimeout)
                .map { request =>
                  Some {
                    request -> { (maybeResponse: Option[KResponse[A]]) =>
                      maybeResponse
                        .fold(Task.unit) { response =>
                          channel.sendMessage(response).timeout(requestTimeout)
                        }
                        .guarantee(release)
                    }
                  }
                }
                .onErrorHandleWith {
                  case NonFatal(_) =>
                    // Most likely it wasn't a request that initiated the channel.
                    release.as(None)
                }
            }
        }
        .collect { case Some(pair) => pair }
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
        .use { clientChannel =>
          sendRequest(message, clientChannel, pf)
        }
    }

    private def sendRequest[Request <: KRequest[A], Response <: KResponse[A]](
        message: Request,
        clientChannel: Channel[A, KMessage[A]],
        pf: PartialFunction[KMessage[A], Response]
    ): Task[Response] = {
      for {
        _ <- clientChannel.sendMessage(message).timeout(requestTimeout)
        // This assumes that `requestTemplate` always opens a new channel.
        response <- clientChannel.messageObservable
          .collect {
            case MessageReceived(m) if pf.isDefinedAt(m) => pf(m)
          }
          .headL
          .timeout(requestTimeout)
      } yield response
    }
  }
}
