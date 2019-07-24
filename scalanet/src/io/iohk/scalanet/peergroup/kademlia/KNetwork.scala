package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.FindNodes
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.Nodes
import io.iohk.scalanet.peergroup.{Channel, PeerGroup}
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.Failure

trait KNetwork[A] {
  def server: Observable[(Channel[A, KMessage[A]], KRequest[A])]

  def findNodes(to: A, request: FindNodes[A]): Future[Nodes[A]]
}

object KNetwork {

  class KNetworkScalanetImpl[A](peerGroup: PeerGroup[A, KMessage[A]])(
      implicit scheduler: Scheduler
  ) extends KNetwork[A] {

    private val log = LoggerFactory.getLogger(getClass)

    override def server: Observable[(Channel[A, KMessage[A]], KRequest[A])] = {
      peerGroup.server().collectChannelCreated.mergeMap { channel =>
        channel.in.collect {
          case f @ FindNodes(_, _, _, _) =>
            (channel, f)
        }
      }
    }

    override def findNodes(to: A, message: FindNodes[A]): Future[Nodes[A]] = {
      peerGroup
        .client(to)
        .flatMap { channel =>
          channel.sendMessage(message).runAsync.onComplete {
            case Failure(exception) =>
              log.info(s"findNodes request to $to failed to send. Got error: $exception")
            case _ =>
          }
          channel.in.collect { case n @ Nodes(_, _, _, _) => n }.headL
        }
        .runAsync
    }
  }

}
