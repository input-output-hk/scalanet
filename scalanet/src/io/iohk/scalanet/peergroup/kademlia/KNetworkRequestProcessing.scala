package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.peergroup.kademlia.KMessage.{KRequest, KResponse}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KResponse.{Nodes, Pong}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

/**
  * If a user of KNetwork wanted to consume only one kind of request,
  * it is not sufficient to collect or filter the request stream, since it is
  * still necessary to close channels for excluded request types.
  * The code to do this is demonstrated here.
  */
object KNetworkRequestProcessing {

  implicit class KNetworkExtension[A](kNetwork: KNetwork[A])(implicit scheduler: Scheduler) {

    type KRequestT = (KRequest[A], Option[KResponse[A]] => Task[Unit])
    type FindNodesT = (FindNodes[A], Option[Nodes[A]] => Task[Unit])
    type PingT = (Ping[A], Option[Pong[A]] => Task[Unit])

    def findNodesRequests(): Observable[FindNodesT] =
      kNetwork.kRequests
        .map {
          case (f @ FindNodes(_, _, _), h) =>
            Some((f, h))
          case (_, h) =>
            ignore(h)
            None
        }
        .collect { case Some(v) => v }

    def pingRequests(): Observable[PingT] =
      kNetwork.kRequests
        .map {
          case (p @ Ping(_, _), h) =>
            Some((p, h))
          case (_, h) =>
            ignore(h)
            None
        }
        .collect { case Some(v) => v }

    private def ignore(
        handler: Option[KResponse[A]] => Task[Unit]
    ): Option[KResponse[A]] => Task[Unit] = {
      handler(None).runToFuture
      _ => Task.unit
    }
  }
}
