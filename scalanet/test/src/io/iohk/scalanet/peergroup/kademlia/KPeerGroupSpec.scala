package io.iohk.scalanet.peergroup.kademlia

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import io.iohk.decco.{BufferInstantiator, Codec}
import io.iohk.scalanet.peergroup.InMemoryPeerGroup.Network
import io.iohk.scalanet.peergroup.PeerGroup.createOrThrow
import io.iohk.scalanet.peergroup.StandardTestPack.messagingTest
import io.iohk.scalanet.peergroup.{InMemoryPeerGroup, InetMultiAddress}
import io.iohk.scalanet.peergroup.kademlia.Generators.aRandomNodeRecord
import io.iohk.scalanet.peergroup.kademlia.KPeerGroupSpec.withTwoPeerGroups
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import monix.execution.Scheduler
import monix.reactive.subjects.PublishSubject
import org.mockito.Mockito.when
import org.scalatest.FlatSpec
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar._
import org.scalatest.concurrent.ScalaFutures._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class KPeerGroupSpec extends FlatSpec {

  implicit val patienceConfig: ScalaFutures.PatienceConfig = PatienceConfig(1 second)

  behavior of "KPeerGroup"

  import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
  import io.iohk.decco.auto._
  import io.iohk.scalanet.peergroup.kademlia.BitVectorCodec._
  import monix.execution.Scheduler.Implicits.global

  it should "send and receive a message" in withTwoPeerGroups(
    aRandomNodeRecord(),
    aRandomNodeRecord()
  ) { (alice, bob) =>
    messagingTest(alice, bob)
  }
}

object KPeerGroupSpec {

  def withTwoPeerGroups(a: NodeRecord, b: NodeRecord)(
      testCode: (KPeerGroup[String], KPeerGroup[String]) => Any
  )(
      implicit scheduler: Scheduler,
      codec: Codec[Either[NodeRecord, String]],
      bufferInstantiator: BufferInstantiator[ByteBuffer]
  ): Unit = {

    val n: Network[InetMultiAddress, Either[NodeRecord, String]] = new Network()

    val underlying1 = new InMemoryPeerGroup[InetMultiAddress, Either[NodeRecord, String]](
      InetMultiAddress(new InetSocketAddress(a.ip, a.udp))
    )(n)

    val underlying2 = new InMemoryPeerGroup[InetMultiAddress, Either[NodeRecord, String]](
      InetMultiAddress(new InetSocketAddress(b.ip, b.udp))
    )(n)

    Await.result(underlying1.initialize().runAsync, Duration.Inf)
    Await.result(underlying2.initialize().runAsync, Duration.Inf)

    val kRouter1 = mockKRouter(a, Seq(b))
    val kRouter2 = mockKRouter(b, Seq(a))

    val kPeerGroup1 = createOrThrow(new KPeerGroup[String](kRouter1, PublishSubject(), underlying1), a)
    val kPeerGroup2 = createOrThrow(new KPeerGroup[String](kRouter2, PublishSubject(), underlying2), b)

    try {
      testCode(kPeerGroup1, kPeerGroup2)
    } finally {
      Await.result(kPeerGroup1.shutdown().runAsync, Duration.Inf)
      Await.result(kPeerGroup1.shutdown().runAsync, Duration.Inf)
    }
  }

  private def mockKRouter(nodeRecord: NodeRecord, peers: Seq[NodeRecord]): KRouter = {
    val kRouter = mock[KRouter]
    val kRouterConfig = KRouter.Config(nodeRecord, Set.empty)
    peers.foreach(
      peerRecord => when(kRouter.get(peerRecord.id)).thenReturn(Future(peerRecord))
    )
    when(kRouter.config).thenReturn(kRouterConfig)
    kRouter
  }
}
