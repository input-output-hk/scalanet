package io.iohk.scalanet.peergroup.kademlia

import io.iohk.scalanet.codec.{EitherCodec, NodeRecordCode}
import io.iohk.scalanet.peergroup.InMemoryPeerGroup.Network
import io.iohk.scalanet.peergroup.PeerGroup.createOrThrow
import io.iohk.scalanet.peergroup.StandardTestPack.messagingTest
import io.iohk.scalanet.peergroup.{InMemoryPeerGroup, PeerGroup}
import io.iohk.scalanet.peergroup.kademlia.Generators.aRandomNodeRecord
import io.iohk.scalanet.peergroup.kademlia.KPeerGroupSpec.withTwoPeerGroups
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import monix.eval.Task
import monix.execution.Scheduler
import org.mockito.Mockito.when
import org.scalatest.FlatSpec
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar._
import org.scalatest.concurrent.ScalaFutures._
import scodec.Codec

import scala.concurrent.Await
import scala.concurrent.duration._

class KPeerGroupSpec extends FlatSpec {

  import scodec.codecs.implicits._
  implicit val nodeRecordCodec = new NodeRecordCode[String]
  implicit val codec = new EitherCodec[KRouter.NodeRecord[String], String]

  implicit val patienceConfig: ScalaFutures.PatienceConfig =
    PatienceConfig(1 second)

  behavior of "KPeerGroup"

  import monix.execution.Scheduler.Implicits.global

  it should "send and receive a message" in withTwoPeerGroups(
    aRandomNodeRecord(),
    aRandomNodeRecord()
  ) { (alice, bob) =>
    messagingTest(alice, bob)
  }
}

object KPeerGroupSpec {

  def withTwoPeerGroups(a: NodeRecord[String], b: NodeRecord[String])(
      testCode: (KPeerGroup[String, String], KPeerGroup[String, String]) => Any
  )(
      implicit scheduler: Scheduler,
      codec: Codec[Either[NodeRecord[String], String]]
  ): Unit = {

    val n: Network[String, Either[NodeRecord[String], String]] = new Network()

    val underlying1 = PeerGroup.createOrThrow(
      new InMemoryPeerGroup[String, Either[NodeRecord[String], String]](
        a.messagingAddress
      )(n),
      "underlying1"
    )

    val underlying2 = PeerGroup.createOrThrow(
      new InMemoryPeerGroup[String, Either[NodeRecord[String], String]](
        b.messagingAddress
      )(n),
      "underlying2"
    )

    val kRouter1 = mockKRouter(a, Seq(b))
    val kRouter2 = mockKRouter(b, Seq(a))

    val kPeerGroup1 = createOrThrow(
      new KPeerGroup[String, String](kRouter1, underlying1),
      a
    )
    val kPeerGroup2 = createOrThrow(
      new KPeerGroup[String, String](kRouter2, underlying2),
      b
    )

    try {
      testCode(kPeerGroup1, kPeerGroup2)
    } finally {
      Await.result(kPeerGroup1.shutdown().runToFuture, Duration.Inf)
      Await.result(kPeerGroup1.shutdown().runToFuture, Duration.Inf)
    }
  }

  private def mockKRouter(nodeRecord: NodeRecord[String], peers: Seq[NodeRecord[String]]): KRouter[String] = {
    val kRouter = mock[KRouter[String]]
    val kRouterConfig = KRouter.Config[String](nodeRecord, Set.empty)
    peers.foreach(
      peerRecord => when(kRouter.get(peerRecord.id)).thenReturn(Task(peerRecord))
    )
    when(kRouter.config).thenReturn(kRouterConfig)
    kRouter
  }
}
