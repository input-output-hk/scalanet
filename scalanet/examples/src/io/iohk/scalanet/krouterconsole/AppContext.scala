package io.iohk.scalanet.krouterconsole

import java.nio.file.Path

import io.iohk.scalanet.peergroup.{InetMultiAddress, PeerGroup, UDPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.{KMessage, KRouter}
import pureconfig.generic.auto._
import monix.execution.Scheduler

class AppContext(configFile: Path)(implicit scheduler: Scheduler) {

  import PureConfigReadersAndWriters._

  val kRouter: KRouter[InetMultiAddress] = {
    val nodeConfig: KRouter.Config[InetMultiAddress] =
      pureconfig
        .loadConfigOrThrow[KRouter.Config[InetMultiAddress]](configFile)

    import io.iohk.scalanet.peergroup.kademlia.BitVectorCodec._
    import io.iohk.decco.auto._
    import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer

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

      new KRouter[InetMultiAddress](nodeConfig, kNetwork)

    } catch {
      case e: Exception =>
        System.err.println(
          s"Exiting due to initialization error: $e, ${e.getCause}"
        )
        throw e
    }
  }
}
