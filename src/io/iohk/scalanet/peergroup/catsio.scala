package io.iohk.scalanet.peergroup

import cats.effect.IO
import cats.data.Kleisli
import io.iohk.scalanet.peergroup.PeerGroup.Lift
import monix.execution.Scheduler

object catsio {

  type PeerGroup[A] = io.iohk.scalanet.peergroup.PeerGroup[A, IO]

  type TerminalPeerGroup[A] = io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup[A, IO]

  type TCPPeerGroup = io.iohk.scalanet.peergroup.TCPPeerGroup[IO]

  type UDPPeerGroup = io.iohk.scalanet.peergroup.UDPPeerGroup[IO]

  implicit def liftIO(implicit scheduler: Scheduler): Lift[IO] =
    Kleisli(task => task.toIO)
}
