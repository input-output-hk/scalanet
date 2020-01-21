import java.net.{InetAddress, InetSocketAddress}

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.scalanet.{InetAddressCodecContract, IntegerCodecContract, KMessageCodecContract, NodeRecordCodeContract, StreamCodecFromContract}
import io.iohk.scalanet.peergroup.{InetMultiAddress, TCPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.{KMessage, KRouter}
import monix.eval.Task
import scodec.bits.BitVector

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.util.Random
import monix.execution.Scheduler.Implicits.global

class KademliaBroadcastTest(myID:BitVector, myIP:InetSocketAddress, root:Option[(BitVector,InetSocketAddress)], ipMess:InetAddress) {
  val myNodeRecord = new KRouter.NodeRecord[InetMultiAddress](myID,InetMultiAddress(myIP),InetMultiAddress(myIP))
  val knowIP:Set[KRouter.NodeRecord[InetMultiAddress]] = root match{
    case None => Set()
    case Some(dir) => Set(new KRouter.NodeRecord[InetMultiAddress](dir._1,InetMultiAddress(dir._2),InetMultiAddress(dir._2)))
  }
  val config = KRouter.Config(myNodeRecord,knowIP)

  val configTCPPeerGroup = TCPPeerGroup.Config(myIP)
  val configTCPPeerGroupSubnet = TCPPeerGroup.Config(new InetSocketAddress(ipMess,myIP.getPort))
  val peerGroupMultiAddress = new TCPPeerGroup[KMessage[InetMultiAddress] ](configTCPPeerGroup)(new StreamCodecFromContract[KMessage[InetMultiAddress]](new KMessageCodecContract[InetMultiAddress](new NodeRecordCodeContract[InetMultiAddress](InetAddressCodecContract))),HeapByteBuffer,global)
  val subNet = new TCPPeerGroup[BroadcastNet.Message[Int] ](configTCPPeerGroupSubnet)(new StreamCodecFromContract[BroadcastNet.Message[Int] ](new BroadCastMessageCodec[Int](IntegerCodecContract)),HeapByteBuffer,global)

  peerGroupMultiAddress.initialize().runSyncUnsafe()
  subNet.initialize().runSyncUnsafe()
  val kNetwork = new KNetworkScalanetImpl[InetMultiAddress](peerGroupMultiAddress)

  val krouter = KRouter.startRouterWithServerSeq(config,kNetwork).runSyncUnsafe()

  val br = new BroadcastNet[Int](krouter,subNet,ipMess)
  br.server.foreachL(m => {
    System.out.println("NEW MESSAGE: " + m)
    System.out.println("NEW MESSAGE: " + m)
  }).runAsyncAndForget
}

object KademliaBroadcastTest extends App{
  val rand = new Random()

  val bVec1 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec2 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec3 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec4 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec5 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec6 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec7 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec8 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec9 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec10 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}

  if(Set(bVec1,bVec2,bVec3,bVec4,bVec5,bVec6,bVec7,bVec8,bVec9,bVec10).size!=10){
    throw new RuntimeException("EQUALS VECTOR IN SIZE")
  }

  val rootAddr = new InetSocketAddress(InetAddress.getByName("127.0.0.1"),5555)

  new Thread(new Runnable {
    override def run(): Unit = new KademliaBroadcastTest(bVec1,rootAddr,None,InetAddress.getByName("127.0.0.100"))
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBroadcastTest(bVec2,new InetSocketAddress(InetAddress.getByName("127.0.0.2"),5556),Some(bVec1,rootAddr),InetAddress.getByName("127.0.0.100"))
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = {val test = new KademliaBroadcastTest(bVec3,new InetSocketAddress(InetAddress.getByName("127.0.0.3"),5557),Some(bVec1,rootAddr),InetAddress.getByName("127.0.0.100"));Task.eval({
      System.out.println("ENVIANDO MENSAJE!!!")
      test.br.broadcast(50)
    }).delayExecution(Duration(15000,"millis")).runAsyncAndForget}
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBroadcastTest(bVec4,new InetSocketAddress(InetAddress.getByName("127.0.0.4"),5558),Some(bVec1,rootAddr),InetAddress.getByName("127.0.0.100"))
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBroadcastTest(bVec5,new InetSocketAddress(InetAddress.getByName("127.0.0.5"),5559),Some(bVec1,rootAddr),InetAddress.getByName("127.0.0.100"))
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBroadcastTest(bVec6,new InetSocketAddress(InetAddress.getByName("127.0.0.6"),5560),Some(bVec1,rootAddr),InetAddress.getByName("127.0.0.100"))
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBroadcastTest(bVec7,new InetSocketAddress(InetAddress.getByName("127.0.0.7"),5561),Some(bVec1,rootAddr),InetAddress.getByName("127.0.0.100"))
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBroadcastTest(bVec8,new InetSocketAddress(InetAddress.getByName("127.0.0.8"),5562),Some(bVec1,rootAddr),InetAddress.getByName("127.0.0.100"))
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBroadcastTest(bVec9,new InetSocketAddress(InetAddress.getByName("127.0.0.9"),5563),Some(bVec1,rootAddr),InetAddress.getByName("127.0.0.100"))
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBroadcastTest(bVec10,new InetSocketAddress(InetAddress.getByName("127.0.0.10"),5564),Some(bVec1,rootAddr),InetAddress.getByName("127.0.0.100"))
  }).start()

  while(true) {
    Thread.sleep(1000)
  }
}