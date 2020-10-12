package io.iohk.scalanet.peergroup.udp

import cats.effect.Resource
import java.net.InetSocketAddress
import monix.eval.Task
import monix.execution.Scheduler
import scodec.Codec
import io.iohk.scalanet.peergroup.PeerGroup
import io.iohk.scalanet.peergroup.InetMultiAddress

class DynamicUDPPeerGroupSpec extends UDPPeerGroupSpec("DynamicUDPPPeerGroup") {
  override def initUdpPeerGroup[M](
      address: InetSocketAddress
  )(implicit s: Scheduler, c: Codec[M]): Resource[Task, UDPPeerGroupSpec.TestGroup[M]] = {
    DynamicUDPPeerGroup[M](DynamicUDPPeerGroup.Config(address)).map { pg =>
      new PeerGroup[InetMultiAddress, M] {
        override def processAddress = pg.processAddress
        override def server = pg.server
        override def client(to: InetMultiAddress) = pg.client(to)
        def channelCount: Int = pg.activeChannels.size
      }
    }
  }
}
