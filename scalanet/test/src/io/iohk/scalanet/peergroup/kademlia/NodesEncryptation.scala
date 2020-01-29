package src.io.iohk.scalanet.peergroup.kademlia

import java.net.{InetAddress, InetSocketAddress}
import java.security.SecureRandom
import java.util.UUID

import io.iohk.scalanet.codec.{InetAddressCodecContract, KMessageCodecContract, NodeRecordCodeContract, StreamCodecFromContract, StringCodecContract}
import io.iohk.scalanet.peergroup.{InMemoryPeerGroup, InetMultiAddress, TCPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.{Generators, KMessage, KNetwork, KRouter}
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import io.iohk.scalanet.crypto
import org.junit.{Assert, Test}
import scodec.bits.BitVector
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import monix.execution.Scheduler.Implicits.global

class NodesEncryptation {
  implicit val nodeRecordStreamCodec = new StreamCodecFromContract[KMessage[InetMultiAddress]](new KMessageCodecContract[InetMultiAddress](new NodeRecordCodeContract(InetAddressCodecContract)) )
  implicit val inetStreamCodec = new StreamCodecFromContract[InetMultiAddress](InetAddressCodecContract)
  val random = new SecureRandom()

  @Test def `system accept NodeRecord signed fine`: Unit = {
    val k1 = crypto.generateKeyPair(random)
    val k2 = crypto.generateKeyPair(random)
    val ip1 = InetMultiAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),5555))
    val ip2 = InetMultiAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),5556))
    val r1 = NodeRecord.create[InetMultiAddress](BitVector(crypto.encodeKey(k1._2)),ip1,ip1,new UUID(0,0),k1._1)
    val r2 = NodeRecord.create[InetMultiAddress](BitVector(crypto.encodeKey(k2._2)),ip2,ip2,new UUID(0,0),k2._1)

    val peerGroup1 = new TCPPeerGroup[KMessage[InetMultiAddress]](TCPPeerGroup.Config(ip1.inetSocketAddress))

    val kNetwork1 = new KNetwork.KNetworkScalanetImpl[InetMultiAddress](peerGroup1)
    val krouter = KRouter.startRouterWithServerSeq[InetMultiAddress](KRouter.Config(r1,Set(r2)),kNetwork1).runSyncUnsafe()
    val v = krouter.nodeRecords.runSyncUnsafe()
    Assert.assertTrue(v.contains(r2.id))
  }

  @Test def `system not accept NodeRecord signed bad`: Unit = {
    val k1 = crypto.generateKeyPair(random)
    val k2 = crypto.generateKeyPair(random)
    val ip1 = InetMultiAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),5555))
    val ip2 = InetMultiAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),5556))
    val r1 = NodeRecord.create[InetMultiAddress](BitVector(crypto.encodeKey(k1._2)),ip1,ip1,new UUID(0,0),k1._1)
    val r2 = NodeRecord.create[InetMultiAddress](BitVector(crypto.encodeKey(k2._2)),ip2,ip2,new UUID(0,0),k1._1)

    val peerGroup1 = new TCPPeerGroup[KMessage[InetMultiAddress]](TCPPeerGroup.Config(ip1.inetSocketAddress))

    val kNetwork1 = new KNetwork.KNetworkScalanetImpl[InetMultiAddress](peerGroup1)
    val krouter = KRouter.startRouterWithServerSeq[InetMultiAddress](KRouter.Config(r1,Set(r2)),kNetwork1).runSyncUnsafe()
    val v = krouter.nodeRecords.runSyncUnsafe()
    Assert.assertTrue(!v.contains(r2.id))
  }
