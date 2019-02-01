package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.iohk.scalanet.peergroup.PeerGroup.{Lift, TerminalPeerGroup}
import monix.eval.Task

import scala.language.higherKinds

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
