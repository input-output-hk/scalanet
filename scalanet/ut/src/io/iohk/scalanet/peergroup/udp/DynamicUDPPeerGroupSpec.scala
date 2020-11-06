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
  )(implicit scheduler: Scheduler, codec: Codec[M]): Resource[Task, UDPPeerGroupSpec.TestGroup[M]] = {
    DynamicUDPPeerGroup[M](DynamicUDPPeerGroup.Config(address)).map { pg =>
      new PeerGroup[InetMultiAddress, M] {
        override protected val s = scheduler
        override def processAddress = pg.processAddress
        override def nextServerEvent() = pg.nextServerEvent()
        override def client(to: InetMultiAddress) = pg.client(to)
        def channelCount: Int = pg.activeChannels.size
      }
    }
  }
}
