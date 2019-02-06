package io.iohk.scalanet.peergroup


import java.nio.ByteBuffer

import io.iohk.scalanet.messagestream.MessageStream
import io.iohk.scalanet.peergroup.PeerGroup.{Lift, NonTerminalPeerGroup}
import io.iohk.scalanet.peergroup.SimplePeerGroup.Config

import scala.language.higherKinds

class SimplePeerGroup[A,F[_],AA](val config: Config,underLinePeerGroup:PeerGroup[AA,F])(implicit liftF: Lift[F])
  extends NonTerminalPeerGroup[A,F,AA](underLinePeerGroup) {
  override def sendMessage(address: A, message: ByteBuffer): F[Unit] = ???

  override def shutdown(): F[Unit] = ???

  override def messageStream(): MessageStream[ByteBuffer] = ???
}

object SimplePeerGroup{

  case class Config()

}
