package io.iohk.scalanet.peergroup.kademlia

import java.net.InetSocketAddress

import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.FindNodes
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.Nodes
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.{Channel, InetMultiAddress, PeerGroup}
import monix.execution.Scheduler
import monix.reactive.Observable
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.Failure

trait KNetwork {
  def server: Observable[(Channel[InetMultiAddress, KMessage], KRequest)]

  def findNodes(to: NodeRecord, request: FindNodes): Future[Nodes]
}

object KNetwork {

  class KNetworkScalanetImpl(peerGroup: PeerGroup[InetMultiAddress, KMessage])(
      implicit scheduler: Scheduler
  ) extends KNetwork {

    private val log = LoggerFactory.getLogger(getClass)

    override def server: Observable[(Channel[InetMultiAddress, KMessage], KRequest)] = {
      peerGroup.server().collectChannelCreated.mergeMap { channel =>
        channel.in.collect {
          case f @ FindNodes(_, _, _) =>
            (channel, f)
        }
      }
    }

    override def findNodes(to: NodeRecord, message: FindNodes): Future[Nodes] = {
      peerGroup
        .client(InetMultiAddress(new InetSocketAddress(to.ip, to.udp)))
        .flatMap { channel =>
          channel.sendMessage(message).runAsync.onComplete {
            case Failure(exception) =>
              log.info(s"findNodes request to $to failed to send. Got error: $exception")
            case _ =>
          }
          channel.in.collect { case n @ Nodes(_, _, _) => n }.headL
        }
        .runAsync
    }
  }
}
