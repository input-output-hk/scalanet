import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer
import java.util.UUID

import io.iohk.decco.BufferInstantiator.global
import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.scalanet.{ByteArrayCodecContract, InetAddressCodecContract, IntegerCodecContract, KMessageCodecContract, LongerCodecContract, NodeRecordCodeContract, SeqCodecContract, StreamCodecFromContract}
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.kademlia.{KMessage, KRouter}
import io.iohk.scalanet.peergroup.kademlia.KMessage.KRequest.Ping
import org.junit._
import org.scalacheck.Properties
import org.scalacheck.Prop
import org.scalacheck.Prop.forAll
import org.scalacheck.Test.check
import scodec.bits.BitVector

import scala.util.Random

class StreamObjectsTest {
  @Test def `Integer Contract`:Unit = {
    val buff = ByteBuffer.allocate(4)
    Assert.assertTrue(check(forAll({
      x:Int => {
        IntegerCodecContract.encodeImpl(x,0,buff)
        x.equals(IntegerCodecContract.decodeImpl(0,buff).right.get.decoded)
      }
    }))(identity).passed)
  }

  @Test def `Longer Contract`:Unit = {
    val buff = ByteBuffer.allocate(8)
    Assert.assertTrue(check(forAll({
      x:Long => {
        LongerCodecContract.encodeImpl(x,0,buff)
        x.equals(LongerCodecContract.decodeImpl(0,buff).right.get.decoded)
      }
    }))(identity).passed)
  }

  @Test def `Seq Longer Contract`:Unit = {
    val seqLongerContract = new SeqCodecContract[Long](LongerCodecContract)
    Assert.assertTrue(check(forAll({
      x:Seq[Long] => {
        val buff = ByteBuffer.allocate(x.size*8 + 4)
        seqLongerContract.encodeImpl(x,0,buff)
        val dec = seqLongerContract.decodeImpl(0,buff).right.get.decoded
        x.sameElements(dec)
      }
    }))(identity).passed)
  }

  @Test def `encode decode messages`:Unit = {
    val codec = new NodeRecordCodeContract[Int](IntegerCodecContract)
    val rand = new Random()
    for(i <- 0 to 100){
      val tam = abs(rand.nextInt() % 101)
      val buff = new Array[Byte](tam)
      rand.nextBytes(buff)
      val obj = KRouter.NodeRecord[Int](BitVector(buff),rand.nextInt(),rand.nextInt())
      val encoded = ByteBuffer.allocate(codec.size(obj))
      codec.encodeImpl(obj,0,encoded)
      val decoded = codec.decodeImpl(0,encoded).right.get.decoded
      //System.out.println("ORIGINAL: " + obj.id + " DECODED: " + decoded.id)
      Assert.assertTrue(obj.id.toByteArray.sameElements(decoded.id.toByteArray))
      Assert.assertTrue(obj.messagingAddress.equals(decoded.messagingAddress))
      Assert.assertTrue(obj.routingAddress.equals(decoded.routingAddress))
    }
  }
  @Test def `Test InetAddress`:Unit = {
    def mod(n:Int):Int = {
      if(n<0) -n
      else n
    }
    val random = new Random()
    for(i <- 0 to 100){
      val obj = InetMultiAddress(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),mod(random.nextInt() % 2000) ))
      val encoded = ByteBuffer.allocate(InetAddressCodecContract.size(obj))
      InetAddressCodecContract.encodeImpl(obj,0,encoded)
      val decoded = InetAddressCodecContract.decodeImpl(0,encoded)
      Assert.assertTrue(decoded.isRight)
      System.out.println("DECODED DIR: " + decoded.right.get.decoded)
      Assert.assertTrue(decoded.right.get.decoded.inetSocketAddress.getAddress.getAddress.sameElements(obj.inetSocketAddress.getAddress.getAddress))
      Assert.assertTrue(decoded.right.get.decoded.inetSocketAddress.getPort.equals(obj.inetSocketAddress.getPort))
    }
  }
  def abs(i:Int): Int = {
    if(i<0) -i
    else i
  }
  @Test def `ArrayInstantiator`:Unit = {
    val random = new Random()
    for(i <- 1 to 500) {
      val tam = abs(random.nextInt() % 101)
      val arr = new Array[Byte](tam)
      random.nextBytes(arr)
      val encoded = ByteBuffer.allocate(tam + 4)
      ByteArrayCodecContract.encodeImpl(arr, 0, encoded)
      val decoded = ByteArrayCodecContract.decodeImpl(0,encoded).right.get.decoded
      Assert.assertTrue(arr.sameElements(decoded))
    }
  }

  @Test def `KMessageCodecContract`: Unit = {
    val random = new Random()
    val codec = new KMessageCodecContract[Int](new NodeRecordCodeContract[Int](IntegerCodecContract))
    for(i <- 0 to 100){
      val buff = new Array[Byte](20)
      random.nextBytes(buff)
      val nRecord = KRouter.NodeRecord[Int](BitVector(buff),random.nextInt(),random.nextInt())
      val obj = Ping(new UUID(random.nextLong(),random.nextLong()),nRecord)
      val encoded = ByteBuffer.allocate(codec.size(obj))
      codec.encodeImpl(obj,0,encoded)
      val decoded = codec.decodeImpl(0,encoded).right.get.decoded
      decoded match {
        case Ping(uuid2,nodRec2) => {
          Assert.assertTrue(uuid2.getLeastSignificantBits.equals(obj.requestId.getLeastSignificantBits))
          Assert.assertTrue(uuid2.getMostSignificantBits.equals(obj.requestId.getMostSignificantBits))
          System.out.println("ORIGINAL: " + obj.nodeRecord.id + " DECODED: " + nodRec2.id)
          System.out.println("ORIGINAL: " + obj.nodeRecord.id.toByteArray.toList + "\nDECODED:  " + nodRec2.id.toByteArray.toList)
          Assert.assertTrue(obj.nodeRecord.id.toByteArray.sameElements(nodRec2.id.toByteArray))
          Assert.assertTrue(obj.nodeRecord.routingAddress.equals(nodRec2.routingAddress))
          Assert.assertTrue(obj.nodeRecord.messagingAddress.equals(nodRec2.messagingAddress))
        }
        case _ => Assert.assertTrue(false)
      }
    }
  }
  @Test def `Test StreamCodecFromContract`:Unit = {
    val strCodec = new StreamCodecFromContract(IntegerCodecContract)
    val random = new Random()
    for(i <- 0 to 1000){
      val r = random.nextInt()
      val encoded = strCodec.encode(r)
      strCodec.decode(0,encoded) match{
        case Left(_) => Assert.assertTrue(false)
        case Right(decoded) => Assert.assertTrue(decoded.equals(r))
      }
    }
  }

  @Test def `complete messages test`:Unit = {
    val codec = new KMessageCodecContract[InetMultiAddress](new NodeRecordCodeContract[InetMultiAddress](InetAddressCodecContract))
    val random = new Random()
    for(i <- 0 to 100){
      val buff = new Array[Byte](20)
      random.nextBytes(buff)
      val inet1 = new Array[Byte](4)
      val inet2 = new Array[Byte](4)
      random.nextBytes(inet1)
      random.nextBytes(inet2)
      val nRecord = KRouter.NodeRecord[InetMultiAddress](BitVector(buff),InetMultiAddress(new InetSocketAddress(InetAddress.getByAddress(inet1),abs(random.nextInt() % 1000) )),InetMultiAddress(new InetSocketAddress(InetAddress.getByAddress(inet2),abs(random.nextInt() % 1000) )))
      val obj = Ping(new UUID(random.nextLong(),random.nextLong()),nRecord)
      val encoded = ByteBuffer.allocate(codec.size(obj))
      codec.encodeImpl(obj,0,encoded)
      val _decoded = codec.decodeImpl(0,encoded)
      //Assert.assertTrue(_decoded.isRight)
      val decoded = _decoded.right.get.decoded
      decoded match {
        case Ping(uuid2,nodRec2) => {
          Assert.assertTrue(uuid2.getLeastSignificantBits.equals(obj.requestId.getLeastSignificantBits))
          Assert.assertTrue(uuid2.getMostSignificantBits.equals(obj.requestId.getMostSignificantBits))
          System.out.println("ORIGINAL: " + obj.nodeRecord.id + " DECODED: " + nodRec2.id)
          System.out.println("ORIGINAL: " + obj.nodeRecord.id.toByteArray.toList + "\nDECODED:  " + nodRec2.id.toByteArray.toList)
          Assert.assertTrue(obj.nodeRecord.id.toByteArray.sameElements(nodRec2.id.toByteArray))
          Assert.assertTrue(obj.nodeRecord.routingAddress.equals(nodRec2.routingAddress))
          Assert.assertTrue(obj.nodeRecord.messagingAddress.equals(nodRec2.messagingAddress))
        }
        case _ => Assert.assertTrue(false)
      }
    }
  }




  @Test def `complete messages test streamCodec`:Unit = {
    val streamCodec = new StreamCodecFromContract[KMessage[InetMultiAddress]](new KMessageCodecContract[InetMultiAddress](new NodeRecordCodeContract[InetMultiAddress](InetAddressCodecContract)))
    val random = new Random()
    for(i <- 0 to 100){
      val buff = new Array[Byte](20)
      random.nextBytes(buff)
      val inet1 = new Array[Byte](4)
      val inet2 = new Array[Byte](4)
      random.nextBytes(inet1)
      random.nextBytes(inet2)
      val nRecord = KRouter.NodeRecord[InetMultiAddress](BitVector(buff),InetMultiAddress(new InetSocketAddress(InetAddress.getByAddress(inet1),abs(random.nextInt() % 1000) )),InetMultiAddress(new InetSocketAddress(InetAddress.getByAddress(inet2),abs(random.nextInt() % 1000) )))
      val obj = Ping(new UUID(random.nextLong(),random.nextLong()),nRecord)
      val encoded = streamCodec.encode[ByteBuffer](obj)
      val _decoded = streamCodec.decode[ByteBuffer](0,encoded)
      //Assert.assertTrue(_decoded.isRight)
      val decoded = _decoded.right.get
      decoded match {
        case Ping(uuid2,nodRec2) => {
          Assert.assertTrue(uuid2.getLeastSignificantBits.equals(obj.requestId.getLeastSignificantBits))
          Assert.assertTrue(uuid2.getMostSignificantBits.equals(obj.requestId.getMostSignificantBits))
          System.out.println("ORIGINAL: " + obj.nodeRecord.id + " DECODED: " + nodRec2.id)
          System.out.println("ORIGINAL: " + obj.nodeRecord.id.toByteArray.toList + "\nDECODED:  " + nodRec2.id.toByteArray.toList)
          Assert.assertTrue(obj.nodeRecord.id.toByteArray.sameElements(nodRec2.id.toByteArray))
          Assert.assertTrue(obj.nodeRecord.routingAddress.equals(nodRec2.routingAddress))
          Assert.assertTrue(obj.nodeRecord.messagingAddress.equals(nodRec2.messagingAddress))
          System.out.println("TOTAL CAPACITY: " + encoded.capacity() + " NEXT INDEX: " + _decoded)
        }
        case _ => Assert.assertTrue(false)
      }
    }
  }
}
