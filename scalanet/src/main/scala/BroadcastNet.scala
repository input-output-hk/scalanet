import java.net.{InetAddress, InetSocketAddress}
import java.util.UUID

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.scalanet.monix_subject.ConnectableSubject
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.{InetMultiAddress, PeerGroup, TCPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.{KMessage, KRouter}
import scodec.bits.BitVector
import monix.execution.Scheduler.Implicits.global

import scala.collection.mutable
import scala.concurrent.duration.Duration


class BroadcastNet[M](val krouter: KRouter[InetMultiAddress],val net: PeerGroup[InetMultiAddress,BroadcastNet.Message[M]],ipMess:InetAddress) {
  val server = ConnectableSubject[M]()
  private val nextUUIDs = new mutable.HashMap[BitVector,UUID]()
  net.server().collectChannelCreated.foreachL(newChannel => {
    System.out.println("CHANNEL CREATE")
    newChannel.in.foreachL(mes => mes match{
      case BroadcastNet.Message(uuid,sender,message) => {
        val spectedUUID = nextUUIDs.getOrElse(sender,new UUID(0,0) )
        System.out.println("MENSAJE ENTRANTE 1")
        if(!sender.equals(krouter.config.nodeRecord.id) && uuid.equals(spectedUUID)){
          System.out.println("MENSAJE ENTRANTE")
          nextUUIDs.put(sender,new UUID(spectedUUID.getMostSignificantBits,spectedUUID.getLeastSignificantBits + 1))
          server.onNext(message)
          broadcast(mes)
        }
      }
    }).runAsyncAndForget
  }).runAsyncAndForget
  def broadcast(mes:BroadcastNet.Message[M]):Unit = {
    krouter.nodeRecords.runAsync({
      case Left(tw) => throw tw
      case Right(routerState) => {
        routerState.map(x => x._2).filter(x => !x.id.toByteArray.sameElements(krouter.config.nodeRecord.id.toByteArray)).foldRight(())((nRec,rec) => {
          System.out.println("MENSAJE ENVIADO!!!!!!")
          net.client(InetMultiAddress(new InetSocketAddress(ipMess,nRec.routingAddress.inetSocketAddress.getPort))).runAsync(ch => {if(ch.isRight) {ch.right.get.sendMessage(mes).runAsyncAndForget };rec} )
        })
      }
    })
  }

  def broadcast(mes:M):Unit = {
    broadcast(BroadcastNet.Message(nextUUIDs.getOrElse(krouter.config.nodeRecord.id,new UUID(0,0)),krouter.config.nodeRecord.id,mes))
  }
}

object BroadcastNet{
  case class Message[M](uuid:UUID,sender:BitVector,message: M)
}