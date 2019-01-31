package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
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

  class UDPPeerGroup[F[_]](implicit liftF: Lift[F]) extends TerminalPeerGroup[InetSocketAddress, F]() {

    // TODO start listening
    println("UDPPeerGroup starting")

    override def sendMessage(address: InetSocketAddress, message: ByteBuffer): F[Unit] = {
      val send: Task[Unit] = Task {
        println(s"UDPPeerGroup, send to address $address, message $message")
        // TODO send the message
        ()
      }
      liftF(send)
    }

    override def shutdown(): F[Unit] = {
      liftF(Task {
        println(s"UDPPeerGroup, shutdown")
        // TODO shutdown
        ()
      })
    }
  }
}

