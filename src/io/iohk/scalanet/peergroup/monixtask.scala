package io.iohk.scalanet.peergroup

import cats.data.Kleisli
import io.iohk.scalanet.peergroup.PeerGroup.Lift
import monix.eval.Task
import monix.execution.Scheduler

object monixtask {

  type PeerGroup[A] = io.iohk.scalanet.peergroup.PeerGroup[A, Task]

  type TerminalPeerGroup[A] = io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup[A, Task]

  type TCPPeerGroup = io.iohk.scalanet.peergroup.TCPPeerGroup[Task]

  implicit def liftTask(implicit scheduler: Scheduler): Lift[Task] =
    Kleisli(identity)
}
