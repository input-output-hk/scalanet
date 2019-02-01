package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import cats.data.Kleisli
import monix.eval.Task

import scala.language.higherKinds

sealed trait PeerGroup[A, F[_]] {
  def sendMessage(address: A, message: ByteBuffer): F[Unit]
  def shutdown(): F[Unit]
}

object PeerGroup {

  type Lift[F[_]] = Kleisli[F, Task[Unit], Unit]

  abstract class TerminalPeerGroup[A, F[_]] extends PeerGroup[A, F]
}

