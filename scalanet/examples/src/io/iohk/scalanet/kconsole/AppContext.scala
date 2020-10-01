package io.iohk.scalanet.kconsole

import cats.effect.Resource
import io.iohk.scalanet.codec.DefaultCodecs._
import io.iohk.scalanet.kademlia.codec.DefaultCodecs._
import io.iohk.scalanet.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.kademlia.{KMessage, KRouter}
import io.iohk.scalanet.peergroup.{InetMultiAddress}
import io.iohk.scalanet.peergroup.udp.DynamicUDPPeerGroup
import monix.execution.Scheduler
import monix.eval.Task
import scodec.codecs.implicits._

object AppContext {

  def apply(
      nodeConfig: KRouter.Config[InetMultiAddress]
  )(implicit scheduler: Scheduler): Resource[Task, KRouter[InetMultiAddress]] = {
    val routingConfig = DynamicUDPPeerGroup.Config(nodeConfig.nodeRecord.routingAddress.inetSocketAddress)
    for {
      routingPeerGroup <- DynamicUDPPeerGroup[KMessage[InetMultiAddress]](routingConfig)
      kNetwork = new KNetworkScalanetImpl[InetMultiAddress](routingPeerGroup)
      kRouter <- Resource.liftF(KRouter.startRouterWithServerSeq(nodeConfig, kNetwork))
    } yield kRouter
  }
}
