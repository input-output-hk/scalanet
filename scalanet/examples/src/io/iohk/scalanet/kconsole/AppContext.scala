package io.iohk.scalanet.kconsole

import cats.effect.Resource
import io.iohk.scalanet.codec.DefaultCodecs._
import io.iohk.scalanet.kademlia.codec.DefaultCodecs._
import io.iohk.scalanet.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.kademlia.{KMessage, KRouter}
import io.iohk.scalanet.peergroup.{InetMultiAddress}
import io.iohk.scalanet.peergroup.udp.StaticUDPPeerGroup
import monix.execution.Scheduler
import monix.eval.Task

object AppContext {
  def apply(
      nodeConfig: KRouter.Config[InetMultiAddress]
  )(implicit scheduler: Scheduler): Resource[Task, KRouter[InetMultiAddress]] = {
    val routingConfig =
      StaticUDPPeerGroup.Config(nodeConfig.nodeRecord.routingAddress.inetSocketAddress, channelCapacity = 100)
    for {
      routingPeerGroup <- StaticUDPPeerGroup[KMessage[InetMultiAddress]](routingConfig)
      kNetwork = new KNetworkScalanetImpl[InetMultiAddress](routingPeerGroup)
      kRouter <- Resource.liftF(KRouter.startRouterWithServerSeq(nodeConfig, kNetwork))
    } yield kRouter
  }
}
