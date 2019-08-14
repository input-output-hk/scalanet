package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.FindNodes
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.Nodes
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.peergroup.{Channel, PeerGroup}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

import scala.concurrent.Future

trait KNetwork[A] {
  def server: Observable[(Channel[A, KMessage[A]], KRequest[A])]

  def findNodes(to: NodeRecord[A], request: FindNodes[A]): Future[Nodes[A]]
}

object KNetwork {

  import scala.concurrent.duration._

  class KNetworkScalanetImpl[A](
      val peerGroup: PeerGroup[A, KMessage[A]],
      val requestTimeout: FiniteDuration = 3 seconds
  )(
      implicit scheduler: Scheduler
  ) extends KNetwork[A] {

    // TODO from where will server channels be closed (here or the krouter)?
    override def server: Observable[(Channel[A, KMessage[A]], KRequest[A])] = {
      peerGroup.server().collectChannelCreated.mergeMap { channel =>
        channel.in.collect {
          case f @ FindNodes(_, _, _) =>
            (channel, f)
        }
      }
    }

    override def findNodes(to: NodeRecord[A], message: FindNodes[A]): Future[Nodes[A]] = {

      val findTask: Task[Nodes[A]] = for {
        clientChannel <- peerGroup.client(to.routingAddress)
        _ <- clientChannel
          .sendMessage(message)
          .timeout(requestTimeout)
          .doOnFinish(closeIfAnError(clientChannel))
        nodes <- clientChannel.in
          .collect { case n @ Nodes(_, _, _) => n }
          .headL
          .timeout(requestTimeout)
          .doOnFinish(closeIfAnError(clientChannel))
        _ <- clientChannel.close()
      } yield nodes

      findTask.runAsync
    }

    private def closeIfAnError(
        channel: Channel[A, KMessage[A]]
    )(maybeError: Option[Throwable]): Task[Unit] = {
      maybeError.fold(Task.unit)(_ => channel.close())
    }
  }
}
