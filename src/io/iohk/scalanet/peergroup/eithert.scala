package io.iohk.scalanet.peergroup

import cats.data.{EitherT, Kleisli}
import io.iohk.scalanet.peergroup.PeerGroup.Lift
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.{ExecutionContext, Future}

object eithert {

  case class SendError(message: String)

  type ET[T] = EitherT[Future, SendError, T]

  type PeerGroup[A] = io.iohk.scalanet.peergroup.PeerGroup[A, ET]

  type TerminalPeerGroup[A] = io.iohk.scalanet.peergroup.PeerGroup.TerminalPeerGroup[A, ET]

  type UDPPeerGroup = io.iohk.scalanet.peergroup.UDPPeerGroup[ET]

  implicit def liftEitherT(implicit ec: ExecutionContext): Lift[ET] =
    Kleisli { task: Task[Unit] =>

      val errorMappedFuture: Future[Either[SendError, Unit]] =
        task.runAsync(Scheduler(ec)).map(_ => Right(())).recover { case t => Left(SendError(t.getMessage)) }

      EitherT(errorMappedFuture)
    }
}
