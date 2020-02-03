package io.iohk.scalanet.kconsole

import io.iohk.scalanet.peergroup.{InetMultiAddress, PeerGroup, UDPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.{KMessage, KRouter}
import monix.execution.Scheduler

class AppContext(nodeConfig: KRouter.Config[InetMultiAddress])(implicit scheduler: Scheduler) {
  import scodec.codecs.implicits._
  import io.iohk.scalanet.codec.DefaultCodecs.KademliaMessages._
  import io.iohk.scalanet.codec.DefaultCodecs.General._

  val kRouter: KRouter[InetMultiAddress] = {

    try {
      val routingConfig =
        UDPPeerGroup.Config(
          nodeConfig.nodeRecord.routingAddress.inetSocketAddress
        )
      val routingPeerGroup = PeerGroup.createOrThrow(
        new UDPPeerGroup[KMessage[InetMultiAddress]](routingConfig),
        routingConfig
      )
      val kNetwork =
        new KNetworkScalanetImpl[InetMultiAddress](routingPeerGroup)

      KRouter.startRouterWithServerSeq(nodeConfig, kNetwork).runSyncUnsafe()
    } catch {
      case e: Exception =>
        System.err.println(
          s"Exiting due to initialization error: $e, ${e.getCause}"
        )
        throw e
    }
  }
}
