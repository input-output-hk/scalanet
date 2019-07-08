package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.KademliaMessage.KRequest
import io.iohk.scalanet.peergroup.kademlia.KademliaMessage.KRequest.FindNodes
import io.iohk.scalanet.peergroup.kademlia.KademliaMessage.KResponse.Nodes
import io.iohk.scalanet.peergroup.{Channel, PeerGroup}
import monix.execution.Scheduler
import monix.reactive.Observable

import scala.concurrent.Future

trait KNetwork[A] {
  def server: Observable[(Channel[A, KMessage[A]], KRequest[A])]

  def findNodes(to: A, request: FindNodes[A]): Future[Nodes[A]]
}

object KNetwork {

  class KNetworkScalanetImpl[A](peerGroup: PeerGroup[A, KMessage[A]])(
      implicit scheduler: Scheduler
  ) extends KNetwork[A] {
    override def server: Observable[(Channel[A, KMessage[A]], KRequest[A])] = {
      peerGroup.server().mergeMap { channel =>
        channel.in.collect {
          case f @ FindNodes(_, _, _) =>
            (channel, f)
        }
      }
    }

    override def findNodes(to: A, message: FindNodes[A]): Future[Nodes[A]] = {
      peerGroup
        .client(to)
        .flatMap { channel =>
          channel.sendMessage(message).runAsync
          channel.in.collect { case n @ Nodes(_, _, _) => n }.headL
        }
        .runAsync
    }
  }

}
