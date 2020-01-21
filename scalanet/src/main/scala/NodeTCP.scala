import java.net.{InetAddress, InetSocketAddress}
import java.util.UUID

import monix.execution.Scheduler.Implicits.global
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.scalanet.{InetAddressCodecContract, KMessageCodecContract, NodeRecordCodeContract, StreamCodecFromContract}
import io.iohk.scalanet.peergroup.{InetMultiAddress, TCPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.KMessage
import io.iohk.scalanet.peergroup.kademlia.KRouter.NodeRecord
import scodec.bits.BitVector

import scala.io.StdIn
import scala.util.Random

object NodeTCP {
  def main(argv:Array[String]):Unit = {
    val random = new Random()
    val configTCPPeerGroup = TCPPeerGroup.Config(new InetSocketAddress(InetAddress.getByName(argv(0)),Integer.parseInt(argv(1))))
    val peerGroupMultiAddress = new TCPPeerGroup[KMessage[InetMultiAddress] ](configTCPPeerGroup)(new StreamCodecFromContract[KMessage[InetMultiAddress]](new KMessageCodecContract[InetMultiAddress](new NodeRecordCodeContract[InetMultiAddress](InetAddressCodecContract))),HeapByteBuffer,global)
    peerGroupMultiAddress.initialize().runAsyncAndForget
    peerGroupMultiAddress.server().collectChannelCreated.foreach({cb => {
      cb.in.foreach({m => {
        System.out.println(m)
      }})
    }})
    val ip = StdIn.readLine()
    val port = StdIn.readInt()
    peerGroupMultiAddress.client(InetMultiAddress(new InetSocketAddress(InetAddress.getByName(ip),port))).runAsync(cb => cb match{
      case Left(tw) => throw tw
      case Right(ch) => {
        val id = new Array[Byte](20)
        random.nextBytes(id)
        ch.sendMessage(KMessage.KRequest.Ping(new UUID(0,1),NodeRecord(BitVector(id),InetMultiAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),1020)),InetMultiAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),1040))))).runAsyncAndForget
      }
    })
  }
}
