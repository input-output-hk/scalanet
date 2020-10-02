package io.iohk.scalanet.peergroup.udp

import cats.effect.Resource
import java.net.InetSocketAddress
import monix.execution.Scheduler
import monix.eval.Task
import scodec.Codec
import io.iohk.scalanet.peergroup.PeerGroup
import io.iohk.scalanet.peergroup.InetMultiAddress

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

  it should "use the server port for reach client it creates" in (pending)
  it should "replicate incoming messages to all channels connected to the remote address" in (pending)
}
