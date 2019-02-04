package io.iohk.scalanet.peergroup

import java.net.InetSocketAddress
import java.net.InetSocketAddress.createUnresolved
import java.nio.ByteBuffer

import io.iohk.scalanet.NetUtils.aRandomAddress
import org.scalatest.FlatSpec
import org.scalatest.Matchers._

class PeerGroupSpec extends FlatSpec {
  behavior of "PeerGroup"

  private val address: InetSocketAddress = createUnresolved("localhost", 8080)
  private val message: ByteBuffer = ByteBuffer.allocate(0)
  private val config = UDPPeerGroupConfig(aRandomAddress())

  it should "enable implementation in terms of Future" in {
    """
      |    import scala.concurrent.Future
      |    import monix.execution.Scheduler.Implicits.global
      |    import io.iohk.scalanet.peergroup.future._
      |    val peerGroup = new UDPPeerGroup(config)
      |    val future: Future[Unit] = peerGroup.sendMessage(address, message)
      |    """.stripMargin should compile
  }

  it should "enable implementation in terms of EitherT" in {
    """
      |    import cats.data.EitherT
      |    import monix.execution.Scheduler.Implicits.global
      |    import scala.concurrent.Future
      |    import io.iohk.scalanet.peergroup.eithert._
      |    val peerGroup = new UDPPeerGroup(config)
      |    val eitherT: EitherT[Future, SendError, Unit] = peerGroup.sendMessage(address, message)
      |
    """.stripMargin should compile
  }

  it should "enable implementation in terms of Monix Task" in {
    """
      |    import monix.eval.Task
      |    import io.iohk.scalanet.peergroup.monixtask._
      |    import monix.execution.Scheduler.Implicits.global
      |    val peerGroup = new UDPPeerGroup(config)
      |    val task: Task[Unit] = peerGroup.sendMessage(address, message)
    """.stripMargin should compile
  }

  it should "enable implementation in terms of Cats IO" in {
    """
      |    import cats.effect.IO
      |    import io.iohk.scalanet.peergroup.catsio._
      |    import monix.execution.Scheduler.Implicits.global
      |    val peerGroup = new UDPPeerGroup(config)
      |    val task: IO[Unit] = peerGroup.sendMessage(address, message)
      |    """.stripMargin should compile
  }
}
