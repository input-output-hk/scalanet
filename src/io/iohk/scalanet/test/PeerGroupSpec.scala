package io.iohk.scalanet.test

import java.net.InetSocketAddress
import java.net.InetSocketAddress.createUnresolved
import java.nio.ByteBuffer

import cats.data.EitherT
import cats.effect.IO
import monix.eval.Task
import org.scalatest.FlatSpec
import org.scalatest.Matchers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class PeerGroupSpec extends FlatSpec {
  behavior of "PeerGroup"

  private val address: InetSocketAddress = createUnresolved("localhost", 8080)
  private val message: ByteBuffer = ByteBuffer.allocate(0)

  it should "enable implementation in terms of Future" in {
    """
      |    import io.iohk.scalanet.peergroup.future._
      |    val peerGroup = new UDPPeerGroup
      |    val future: Future[Unit] = peerGroup.sendMessage(address, message)
      |    """.stripMargin should compile
  }

  it should "enable implementation in terms of EitherT" in {
    """
      |    import io.iohk.scalanet.peergroup.eithert._
      |    val peerGroup = new UDPPeerGroup
      |    val eitherT: EitherT[Future, SendError, Unit] = peerGroup.sendMessage(address, message)
      |
    """.stripMargin should compile
  }

  it should "enable implementation in terms of Monix Task" in {
    """
      |    import io.iohk.scalanet.peergroup.monixtask._
      |    import monix.execution.Scheduler.Implicits.global
      |    val peerGroup = new UDPPeerGroup
      |    val task: Task[Unit] = peerGroup.sendMessage(address, message)
    """.stripMargin should compile
  }

  it should "enable implementation in terms of Cats IO" in {
    """
      |    import io.iohk.scalanet.peergroup.catsio._
      |    import monix.execution.Scheduler.Implicits.global
      |    val peerGroup = new UDPPeerGroup
      |    val task: IO[Unit] = peerGroup.sendMessage(address, message)
      |    """.stripMargin should compile
  }
}
