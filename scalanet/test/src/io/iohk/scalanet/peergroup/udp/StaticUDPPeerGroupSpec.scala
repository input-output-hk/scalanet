package io.iohk.scalanet.peergroup.udp

import cats.effect.Resource
import cats.implicits._
import java.net.InetSocketAddress
import monix.execution.Scheduler
import monix.eval.Task
import scodec.Codec
import io.iohk.scalanet.peergroup.PeerGroup
import io.iohk.scalanet.peergroup.InetMultiAddress
import scodec.codecs.implicits._

class StaticUDPPeerGroupSpec extends UDPPeerGroupSpec("StaticUDPPeerGroup") {

  override def initUdpPeerGroup[M](
      address: InetSocketAddress
  )(implicit s: Scheduler, c: Codec[M]): Resource[Task, UDPPeerGroupSpec.TestGroup[M]] = {
    StaticUDPPeerGroup[M](StaticUDPPeerGroup.Config(address)).map { pg =>
      new PeerGroup[InetMultiAddress, M] {
        override def processAddress = pg.processAddress
        override def server = pg.server
        override def client(to: InetMultiAddress) = pg.client(to)
        def channelCount: Int = pg.channelCount.runSyncUnsafe()
      }
    }
  }

  it should "use the server port when it opens client channels" in {
    List
      .fill(2)(initUdpPeerGroup[String]())
      .sequence
      .use {
        case List(pg1, pg2) =>
          ???
      }
      .runSyncUnsafe()
  }

  it should "replicate incoming messages to all channels connected to the remote address" in (pending)
  it should "re-emit a server event if a closed channel is re-activated" in (pending)
}
