
import java.net.{InetAddress, InetSocketAddress}
import java.security.SecureRandom
import java.util.concurrent.{Executors, TimeUnit}

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.scalanet.IntegerCodecContract
import io.iohk.scalanet.peergroup.{InetMultiAddress, TCPPeerGroup}
import io.iohk.scalanet.peergroup.kademlia.{KNetworkBeta, KRouter, KRouterBeta}
import org.junit._
import monix.eval.Task
import monix.execution.Scheduler
import scodec.bits.BitVector

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration.Duration

class BroadcastTest{
   val random = new SecureRandom()

  @Test def `two nodes`: Unit = {
    val vec1 = {val arr = new Array[Byte](20);random.nextBytes(arr);BitVector(arr)}
    val vec2 = {val arr = new Array[Byte](20);random.nextBytes(arr);BitVector(arr)}
    if(vec1.equals(vec2)){
      throw new RuntimeException("EQUALS VECTORS!!")
    }
    val ip1 = new InetSocketAddress(InetAddress.getByName("127.0.0.1"),5555)
    val ip2 = new InetSocketAddress(InetAddress.getByName("127.0.0.2"),5556)
    var ping = 0
    val function = {x:Int => (ping = x)}
    System.out.println("1")

    launchNewKRouter(vec1,ip1,Set(),function).runAsync({
      case Left(tw) => throw tw
      case Right(k) => {
        new Thread(new Runnable {
          override def run(): Unit = launchNewKRouter(vec2,ip2,Set((vec1,ip1)),function).runAsyncAndForget
        }).start()
        k.broadCastMessage(55).delayExecution(Duration(1000,"millis")).runAsyncAndForget
      }
    })

    System.out.println("2")
    Thread.sleep(10000)
    Assert.assertEquals(55,ping)
  }

  def launchNewKRouter(myID:BitVector,myIP:InetSocketAddress,knowIPs:Set[(BitVector,InetSocketAddress)],function:Int => Unit):Task[KRouterBeta[InetMultiAddress,Int]] = {
    val myNodeRecord = new KRouter.NodeRecord[InetMultiAddress](myID,InetMultiAddress(myIP),InetMultiAddress(myIP))

    val nodesRecords = knowIPs.map(x => KRouter.NodeRecord(x._1,InetMultiAddress(x._2),InetMultiAddress(x._2)))

    val config = KRouter.Config(myNodeRecord,nodesRecords,3,20,2000,Duration(10000,"millis"))

    val configTCPPeerGroup = TCPPeerGroup.Config(myIP)

    KNetworkBeta.createKNetworkBetaTCP[Int](configTCPPeerGroup)(IntegerCodecContract,HeapByteBuffer,global).flatMap(kNetwork => KRouterBeta.startRouterWithServerSeq(config,kNetwork,function))
  }

  @tailrec
  private def generateNewVector(others:mutable.ListBuffer[(BitVector,InetSocketAddress)]): BitVector ={
    val newVector = {val arr = new Array[Byte](20); random.nextBytes(arr);BitVector(arr)}
    if(others.forall(v => !v._1.equals(newVector)) ) newVector
    else generateNewVector(others)
  }

  @Test def `broadcast in dumy case`: Unit = {
    var ping = 0
    var knowNodes:Map[BitVector,Set[(BitVector,InetSocketAddress)]] = Map()
    val listVectors = mutable.ListBuffer[(BitVector,InetSocketAddress)]()
    val cant = 40
    for(i <- 1 to cant){
      val newVector = generateNewVector(listVectors)
      val newPort = 5555 + i
      val newIP = {val arr = new Array[Byte](4);arr(0) = 127;arr(1)=0; arr(2) = 0;arr(3) = i.toByte;new InetSocketAddress(InetAddress.getByAddress(arr),newPort) }
      val newVal:(BitVector,InetSocketAddress) = (newVector,newIP)
      knowNodes = knowNodes + (newVector -> listVectors.toSet)
      listVectors.+=(newVal)
    }

    val function = synchronized({x:Int => {ping += 1}})

    var index = 0

    for(vec <- listVectors){
      if(index==3) launchNewKRouter(vec._1,vec._2,knowNodes(vec._1), function).runSyncUnsafe().broadCastMessage(55).delayExecution(Duration(8000,"millis")).runAsyncAndForget
      else launchNewKRouter(vec._1,vec._2,knowNodes(vec._1), function).runSyncUnsafe()
      index = index + 1
    }
    Thread.sleep(15000)
    Assert.assertEquals(cant-1,ping)
    System.out.println("PINGS: " + ping)
  }


  @Test def `broadcast in line`:Unit = {
    var ping = 0
    var knowNodes:Map[BitVector,(BitVector,InetSocketAddress)] = Map()
    val listVectors = mutable.ListBuffer[(BitVector,InetSocketAddress)]()
    val cant = 40
    for(i <- 1 to cant){
      val newVector = generateNewVector(listVectors)
      val newPort = 5555 + i
      val newIP = {val arr = new Array[Byte](4);arr(0) = 127;arr(1)=0; arr(2) = 0;arr(3) = i.toByte;new InetSocketAddress(InetAddress.getByAddress(arr),newPort) }
      val newVal:(BitVector,InetSocketAddress) = (newVector,newIP)
      if(listVectors.nonEmpty) knowNodes = knowNodes + (newVector -> listVectors.last)
      listVectors.+=(newVal)
    }

    val function = synchronized({x:Int => {ping += 1}})

    var index = 0
    for(vec <- listVectors){
      if(index==3)launchNewKRouter(vec._1,vec._2,if(knowNodes.contains(vec._1)) Set(knowNodes(vec._1)) else Set(), function).map(k => k.broadCastMessage(55).delayExecution(Duration(18000,"millis")).runAsyncAndForget).runSyncUnsafe()
      else launchNewKRouter(vec._1,vec._2,if(knowNodes.contains(vec._1)) Set(knowNodes(vec._1)) else Set(), function).runSyncUnsafe()

      index = index + 1
    }
    Thread.sleep(25000)
    System.out.println("PINGS: " + ping)
    Assert.assertEquals(cant-1,ping)
  }

  @Test def `broadcast with root`: Unit = {
    var ping = 0
    val listVectors = mutable.ListBuffer[(BitVector,InetSocketAddress)]()
    val cant = 40
    for(i <- 1 to cant){
      val newVector = generateNewVector(listVectors)
      val newPort = 5555 + i
      val newIP = {val arr = new Array[Byte](4);arr(0) = 127;arr(1)=0; arr(2) = 0;arr(3) = i.toByte;new InetSocketAddress(InetAddress.getByAddress(arr),newPort) }
      val newVal:(BitVector,InetSocketAddress) = (newVector,newIP)
      listVectors.+=(newVal)
    }

    val function = synchronized({x:Int => {ping += 1}})

    launchNewKRouter(listVectors.head._1,listVectors.head._2,Set(), function).runSyncUnsafe()

    {val vec = listVectors.tail.head;launchNewKRouter(vec._1,vec._2,Set(listVectors.head), function).runSyncUnsafe().broadCastMessage(55).delayExecution(Duration(8000,"millis")).runAsyncAndForget}



    for(vec <- listVectors.tail.tail){
      launchNewKRouter(vec._1,vec._2,Set(listVectors.head), function).runSyncUnsafe()
    }

    Thread.sleep(15000)

    Assert.assertEquals(cant-1,ping)
    System.out.println("PINGS: " + ping)
  }
}
