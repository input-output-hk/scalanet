package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.FindNodes
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.Nodes
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.{Channel, PeerGroup}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.observables.ConnectableObservable

trait KNetwork[A] {

  /**
    * Server side FIND_NODES handler.
    * @return An Observable for receiving FIND_NODES requests.
    *         Each element contains a tuple consisting of a FIND_NODES request
    *         with a function for accepting the required NODES response.
    */
  def findNodes: ConnectableObservable[(FindNodes[A], Nodes[A] => Task[Unit])]

  /**
    * Send a FIND_NODES message to another peer.
    * @param to the peer to send the message to
    * @param request the FIND_NODES request
    * @return the future response
    */
  def findNodes(to: NodeRecord[A], request: FindNodes[A]): Task[Nodes[A]]
}

object KNetwork {

  import scala.concurrent.duration._

  class KNetworkScalanetImpl[A](
      val peerGroup: PeerGroup[A, KMessage[A]],
      val requestTimeout: FiniteDuration = 3 seconds
  )(implicit scheduler: Scheduler)
      extends KNetwork[A] {

    override def findNodes(to: NodeRecord[A], message: FindNodes[A]): Task[Nodes[A]] = {

      peerGroup
        .client(to.routingAddress)
        .bracket { clientChannel =>
          makeFindNodesRequest(message, clientChannel)
        } { clientChannel =>
          clientChannel.close()
        }
    }

    override def findNodes: ConnectableObservable[(FindNodes[A], Nodes[A] => Task[Unit])] = {
      val serverChannels: Observable[Channel[A, KMessage[A]]] = peerGroup.server().collectChannelCreated
      try {
        val collectedChannels = serverChannels.mergeMap { channel: Channel[A, KMessage[A]] =>
          channel.in
            .collect {
              case f @ FindNodes(_, _, _) =>
                (f, nodesTask(channel))
            }
        }
        val connectableNodes = ConnectableSubject[(FindNodes[A], Nodes[A] => Task[Unit])](collectedChannels)
        peerGroup.server().collectChannelCreated.foreach(_.in.connect())
        peerGroup.server().connect()
        connectableNodes
      } finally {
        serverChannels.map(closeIfAnError)
      }
    }
    private def makeFindNodesRequest(message: FindNodes[A], clientChannel: Channel[A, KMessage[A]]): Task[Nodes[A]] = {
      clientChannel.sendMessage(message).timeout(requestTimeout).flatMap { _ =>
        {
          val observable = clientChannel.in.collect { case n @ Nodes(_, _, _) => n }
          val nodesF = observable.headL.runToFuture
          clientChannel.in.connect()
          Task.fromFuture(nodesF).timeout(requestTimeout)
        }
      }
    }

    private def closeIfAnError(
        channel: Channel[A, KMessage[A]]
    )(maybeError: Option[Throwable]): Task[Unit] = {
      maybeError.fold(Task.unit)(_ => channel.close())
    }

    private def nodesTask(
        channel: Channel[A, KMessage[A]]
    ): Nodes[A] => Task[Unit] = { nodes =>
      channel
        .sendMessage(nodes)
        .timeout(requestTimeout)
        .doOnFinish(_ => channel.close())
    }

  }
}
