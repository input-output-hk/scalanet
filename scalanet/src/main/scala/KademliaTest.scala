import java.net.{InetAddress, InetSocketAddress}

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration.Duration
import scala.util.Random
import io.iohk.scalanet.peergroup.{InetMultiAddress, TCPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.KNetwork.KNetworkScalanetImpl
import io.iohk.scalanet.peergroup.kademlia.{KMessage, KRouter}
import io.iohk.decco.CodecContract
import io.iohk.scalanet.{InetAddressCodecContract, KMessageCodecContract, NodeRecordCodeContract, StreamCodecFromContract}
import io.iohk.scalanet.peergroup.kademlia.BitVectorCodec.bitVectorInstance
import scodec.bits.BitVector

import scala.annotation.tailrec

class KademliaTest(myID:BitVector, myIP:InetSocketAddress, root:Option[(BitVector,InetSocketAddress)], delay:Int) {
  val myNodeRecord = new KRouter.NodeRecord[InetMultiAddress](myID,InetMultiAddress(myIP),InetMultiAddress(myIP))
  val knowIP:Set[KRouter.NodeRecord[InetMultiAddress]] = root match{
    case None => Set()
    case Some(dir) => Set(new KRouter.NodeRecord[InetMultiAddress](dir._1,InetMultiAddress(dir._2),InetMultiAddress(dir._2)))
  }
  val config = KRouter.Config(myNodeRecord,knowIP)

  val configTCPPeerGroup = TCPPeerGroup.Config(myIP)
  val peerGroupMultiAddress = new TCPPeerGroup[KMessage[InetMultiAddress] ](configTCPPeerGroup)(new StreamCodecFromContract[KMessage[InetMultiAddress]](new KMessageCodecContract[InetMultiAddress](new NodeRecordCodeContract[InetMultiAddress](InetAddressCodecContract))),HeapByteBuffer,global)

  peerGroupMultiAddress.initialize().runSyncUnsafe()
  val kNetwork = new KNetworkScalanetImpl[InetMultiAddress](peerGroupMultiAddress)


  KRouter.startRouterWithServerSeq(config,kNetwork).delayExecution(Duration(delay,"millis")).runAsyncAndForget
}

object KademliaTest extends App{
  val rand = new Random()
  @tailrec
  def generateNewVector(oldVectors:Seq[BitVector]):BitVector = {
    val arr = new Array[Byte](20)
    rand.nextBytes(arr)
    val arrIsNew = oldVectors.foldRight(true)((x,rec) => (!x.toByteArray.sameElements(arr)) && rec)
    if(arrIsNew) {System.out.println("newVector: " + BitVector(arr)); BitVector(arr)}
    else generateNewVector(oldVectors)
  }

  /*val bVec1 = generateNewVector(Seq())
  val bVec2 = generateNewVector(Seq(bVec1))
  val bVec3 = generateNewVector(Seq(bVec1,bVec2))
  val bVec4 = generateNewVector(Seq(bVec1,bVec2,bVec3))
  val bVec5 = generateNewVector(Seq(bVec1,bVec2,bVec3,bVec4))
  val bVec6 = generateNewVector(Seq(bVec1,bVec2,bVec3,bVec4,bVec5))
  val bVec7 = generateNewVector(Seq(bVec1,bVec2,bVec3,bVec4,bVec5,bVec6))
  val bVec8 = generateNewVector(Seq(bVec1,bVec2,bVec3,bVec4,bVec5,bVec6,bVec7))
  val bVec9 = generateNewVector(Seq(bVec1,bVec2,bVec3,bVec4,bVec5,bVec6,bVec7,bVec8))
  val bVec10 = generateNewVector(Seq(bVec1,bVec2,bVec3,bVec4,bVec5,bVec6,bVec7,bVec8,bVec9))*/

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
    override def run(): Unit = new KademliaTest(bVec1,rootAddr,None,0)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaTest(bVec2,new InetSocketAddress(InetAddress.getByName("127.0.0.2"),5556),Some(bVec1,rootAddr),220)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaTest(bVec3,new InetSocketAddress(InetAddress.getByName("127.0.0.3"),5557),Some(bVec1,rootAddr),1000)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaTest(bVec4,new InetSocketAddress(InetAddress.getByName("127.0.0.4"),5558),Some(bVec1,rootAddr),0)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaTest(bVec5,new InetSocketAddress(InetAddress.getByName("127.0.0.5"),5559),Some(bVec1,rootAddr),0)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaTest(bVec6,new InetSocketAddress(InetAddress.getByName("127.0.0.6"),5560),Some(bVec1,rootAddr),0)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaTest(bVec7,new InetSocketAddress(InetAddress.getByName("127.0.0.7"),5561),Some(bVec1,rootAddr),0)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaTest(bVec8,new InetSocketAddress(InetAddress.getByName("127.0.0.8"),5562),Some(bVec1,rootAddr),0)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaTest(bVec9,new InetSocketAddress(InetAddress.getByName("127.0.0.9"),5563),Some(bVec1,rootAddr),0)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaTest(bVec10,new InetSocketAddress(InetAddress.getByName("127.0.0.10"),5564),Some(bVec1,rootAddr),0)
  }).start()

  while(true) {
    Thread.sleep(1000)
  }
}
