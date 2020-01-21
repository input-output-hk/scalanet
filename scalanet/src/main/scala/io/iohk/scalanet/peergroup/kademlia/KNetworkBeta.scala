package io.iohk.scalanet.peergroup.kademlia

import java.nio.ByteBuffer

import io.iohk.decco.{BufferInstantiator, CodecContract}
import io.iohk.scalanet.{BroadCastMessageCodec2, EitherCodecContract, InetAddressCodecContract, KMessageCodecContract, NodeRecordCodeContract, StreamCodecFromContract}
import io.iohk.scalanet.peergroup.kademlia.KMessage.{KRequest, KResponse}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.kademlia.KRouterBeta.BroadCastMessage
import io.iohk.scalanet.peergroup.{Channel, InetMultiAddress, PeerGroup, TCPPeerGroup}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.execution.Scheduler.Implicits.global

trait KNetworkBeta[A,M] {

  /**
   * Server side requests stream.
   * @return An Observable for receiving FIND_NODES and PING requests.
   *         Each element contains a tuple consisting of a request
   *         with a function for accepting the required response.
   *         With current conventions, it is mandatory to provide
   *         Some(response) or None for all request types, in order that the
   *         implementation can close the channel.
   */
  def kRequests: Observable[Either[(KRequest[A], Option[KResponse[A]] => Task[Unit]),M]]

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

  def sendMessage(to:NodeRecord[A],mess:M):Task[Unit]
}

object KNetworkBeta {

  import scala.concurrent.duration._

  class KNetworkBetaScalanetImpl[A,M](
                                 val peerGroup: PeerGroup[A, Either[KMessage[A],M] ],
                                 val requestTimeout: FiniteDuration = 10 seconds
                               ) extends KNetworkBeta[A,M] {

    override lazy val kRequests: Observable[Either[(KRequest[A], Option[KResponse[A]] => Task[Unit]),M]] = {
      peerGroup
        .server()
        .refCount
        .collectChannelCreated
        .mapEval { channel: Channel[A, Either[KMessage[A],M]] =>
          channel.in.refCount
            .collect {
              case Left(r:KRequest[A]) => Left(r)
              case Right(m) => Right(m)
            }
            .map({
              case Left(request) => Left(Some((request, sendOptionalResponse(channel))))
              case Right(m) => Right(m)
            })
            .headL
            .timeout(requestTimeout)
            .onErrorHandle(closeTheChannel(channel))
        }
        .collect {
            case Left(Some(thing)) => Left(thing)
            case Right(value) => Right(value)
        }
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
                                                                               clientChannel: Channel[A, Either[KMessage[A],M]],
                                                                               pf: PartialFunction[KMessage[A], Response]
                                                                             ): Task[Response] = {
      for {
        _ <- clientChannel.sendMessage(Left(message)).timeout(requestTimeout)
        response <- clientChannel.in.refCount
          .collect({case Left(resp) => pf(resp)})
          .headL
          .timeout(requestTimeout)
      } yield response
    }

    private def closeTheChannel[Request <: KRequest[A]](
                                                         channel: Channel[A, Either[KMessage[A],M]]
                                                       )(error: Throwable): Either[Option[(Request, Option[KMessage[A]] => Task[Unit])],M] = {
      channel.close()
      Left(None)
    }

    private def sendResponse(
                              channel: Channel[A, Either[KMessage[A],M]]
                            ): KMessage[A] => Task[Unit] = { message =>
      channel
        .sendMessage(Left(message))
        .timeout(requestTimeout)
        .doOnFinish(_ => channel.close())
    }

    private def sendOptionalResponse(
                                      channel: Channel[A, Either[KMessage[A],M]]
                                    ): Option[KMessage[A]] => Task[Unit] = { maybeMessage =>
      maybeMessage.fold(channel.close())(sendResponse(channel))
    }

    override def sendMessage(to: NodeRecord[A], mess: M): Task[Unit] = {
      peerGroup.client(to.routingAddress).flatMap(ch => {
          ch.sendMessage(Right(mess)).flatMap(_ => ch.close())
      })
    }
  }

  def createKNetworkBetaTCP[M](netConfig:TCPPeerGroup.Config,timeout: FiniteDuration = 10 seconds)(implicit codecContract:CodecContract[M], bufferInstantiator: BufferInstantiator[ByteBuffer], scheduler:Scheduler): Task[KNetworkBeta[InetMultiAddress,BroadCastMessage[M]]] = {
    val contract = new StreamCodecFromContract[Either[KMessage[InetMultiAddress],KRouterBeta.BroadCastMessage[M]]](new EitherCodecContract[KMessage[InetMultiAddress],KRouterBeta.BroadCastMessage[M]](new KMessageCodecContract[InetMultiAddress](new NodeRecordCodeContract[InetMultiAddress](InetAddressCodecContract)),new BroadCastMessageCodec2[M](codecContract)))
    val subnet = new TCPPeerGroup[Either[KMessage[InetMultiAddress],BroadCastMessage[M]]](netConfig)(contract,bufferInstantiator,scheduler)
    subnet.initialize().map(_ => new KNetworkBetaScalanetImpl[InetMultiAddress,BroadCastMessage[M]](subnet,timeout))
  }

}
