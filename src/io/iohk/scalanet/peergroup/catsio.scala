package io.iohk.scalanet.peergroup

import cats.effect.IO
import cats.data.Kleisli
import io.iohk.scalanet.peergroup.PeerGroup.Lift
import monix.execution.Scheduler

object catsio {

  type PeerGroup[A] = io.iohk.scalanet.peergroup.PeerGroup[A, IO]

  type TerminalPeerGroup[A] = io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup[A, IO]

  type UDPPeerGroup = io.iohk.scalanet.peergroup.PeerGroup.UDPPeerGroup[IO]

  implicit def liftIO(implicit scheduler: Scheduler): Lift[IO] =
    Kleisli(_.toIO)
}