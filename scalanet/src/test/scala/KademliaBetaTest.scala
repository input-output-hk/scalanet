import java.net.{InetAddress, InetSocketAddress}

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration.Duration
import scala.util.Random
import io.iohk.scalanet.peergroup.{InetMultiAddress, TCPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.{KNetworkBeta, KRouter, KRouterBeta}
import io.iohk.scalanet.IntegerCodecContract
import org.junit.Assert
import scodec.bits.BitVector

class KademliaBetaTest(myID:BitVector, myIP:InetSocketAddress, root:Option[(BitVector,InetSocketAddress)], delay:Int,function: Int => Unit) {
  val myNodeRecord = new KRouter.NodeRecord[InetMultiAddress](myID,InetMultiAddress(myIP),InetMultiAddress(myIP))
  val knowIP:Set[KRouter.NodeRecord[InetMultiAddress]] = root match{
    case None => Set()
    case Some(dir) => Set(new KRouter.NodeRecord[InetMultiAddress](dir._1,InetMultiAddress(dir._2),InetMultiAddress(dir._2)))
  }
  val config = KRouter.Config(myNodeRecord,knowIP,3,1)

  val configTCPPeerGroup = TCPPeerGroup.Config(myIP)

  val kNetwork = KNetworkBeta.createKNetworkBetaTCP[Int](configTCPPeerGroup)(IntegerCodecContract,HeapByteBuffer,global).runSyncUnsafe()


  val krouter = KRouterBeta.startRouterWithServerSeq(config,kNetwork,function).runSyncUnsafe()
}

object KademliaBetaTest extends App{
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
  val bVec11 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec12 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec13 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec14 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec15 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec16 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec17 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec18 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec19 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}
  val bVec20 = {val arr = new Array[Byte](20);rand.nextBytes(arr);BitVector(arr)}

  if(Set(bVec1,bVec2,bVec3,bVec4,bVec5,bVec6,bVec7,bVec8,bVec9,bVec10,bVec11,bVec12,bVec13,bVec14,bVec15,bVec16,bVec17,bVec18,bVec19,bVec20).size!=20){
    throw new RuntimeException("EQUALS VECTOR IN SIZE")
  }

  val rootAddr = new InetSocketAddress(InetAddress.getByName("127.0.0.1"),5555)
  var ping = 0
  val function = synchronized({x:Int => ping = ping + 1})

  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec1,rootAddr,None,0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec2,new InetSocketAddress(InetAddress.getByName("127.0.0.2"),5556),Some(bVec1,rootAddr),220,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec3,new InetSocketAddress(InetAddress.getByName("127.0.0.3"),5557),Some(bVec1,rootAddr),1000,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = {val kb = new KademliaBetaTest(bVec4,new InetSocketAddress(InetAddress.getByName("127.0.0.4"),5558),Some(bVec1,rootAddr),0,function);kb.krouter.broadCastMessage(55).delayExecution(Duration(9000,"millis")).runAsyncAndForget}
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec5,new InetSocketAddress(InetAddress.getByName("127.0.0.5"),5559),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec6,new InetSocketAddress(InetAddress.getByName("127.0.0.6"),5560),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec7,new InetSocketAddress(InetAddress.getByName("127.0.0.7"),5561),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec8,new InetSocketAddress(InetAddress.getByName("127.0.0.8"),5562),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec9,new InetSocketAddress(InetAddress.getByName("127.0.0.9"),5563),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec10,new InetSocketAddress(InetAddress.getByName("127.0.0.10"),5564),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec11,new InetSocketAddress(InetAddress.getByName("127.0.0.11"),5565),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec12,new InetSocketAddress(InetAddress.getByName("127.0.0.12"),5566),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec13,new InetSocketAddress(InetAddress.getByName("127.0.0.13"),5567),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec14,new InetSocketAddress(InetAddress.getByName("127.0.0.14"),5568),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec15,new InetSocketAddress(InetAddress.getByName("127.0.0.15"),5569),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec16,new InetSocketAddress(InetAddress.getByName("127.0.0.16"),5570),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec17,new InetSocketAddress(InetAddress.getByName("127.0.0.17"),5571),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec18,new InetSocketAddress(InetAddress.getByName("127.0.0.18"),5572),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec19,new InetSocketAddress(InetAddress.getByName("127.0.0.19"),5573),Some(bVec1,rootAddr),0,function)
  }).start()
  new Thread(new Runnable {
    override def run(): Unit = new KademliaBetaTest(bVec20,new InetSocketAddress(InetAddress.getByName("127.0.0.20"),5574),Some(bVec1,rootAddr),0,function)
  }).start()

  Thread.sleep(15000)
  Assert.assertEquals(19,ping)
}
