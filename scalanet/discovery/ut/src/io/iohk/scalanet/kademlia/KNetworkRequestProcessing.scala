package io.iohk.scalanet.kademlia

import io.iohk.scalanet.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.kademlia.KMessage.KResponse.{Nodes, Pong}
import io.iohk.scalanet.kademlia.KMessage.{KRequest, KResponse}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

/**
  * If a user of KNetwork wanted to consume only one kind of request,
  * it is not sufficient to collect or filter the request stream, since it is
  * still necessary to invoke response handers to close channels for excluded request types.
  * The code to do this is demonstrated here.
  * Note that findNodesRequests and pingRequests are mutually exclusive.
  */
object KNetworkRequestProcessing {

  implicit class KNetworkExtension[A](kNetwork: KNetwork[A])(implicit scheduler: Scheduler) {

    type KRequestT = (KRequest[A], Option[KResponse[A]] => Task[Unit])
    type FindNodesT = (FindNodes[A], Option[Nodes[A]] => Task[Unit])
    type PingT = (Ping[A], Option[Pong[A]] => Task[Unit])

    def findNodesRequests(): Observable[FindNodesT] =
      kNetwork.kRequests
        .collect {
          case (f @ FindNodes(_, _, _), h) =>
            Some((f, h))
          case (_, h) =>
            ignore(h)
        }
        .collect { case Some(v) => v }

    def pingRequests(): Observable[PingT] =
      kNetwork.kRequests
        .map {
          case (p @ Ping(_, _), h) =>
            Some((p, h))
          case (_, h) =>
            ignore(h)
        }
        .collect { case Some(v) => v }

    private def ignore(
        handler: Option[KResponse[A]] => Task[Unit]
    ): None.type = {
      handler(None).runSyncUnsafe()
      None
    }
  }
}
