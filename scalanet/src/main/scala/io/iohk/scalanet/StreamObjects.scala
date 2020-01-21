package io.iohk.scalanet

import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer
import java.util.UUID

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.decco.Codec.{DecodeResult, Failure}
import io.iohk.decco.{BufferInstantiator, CodecContract}
import io.iohk.scalanet.codec.StreamCodec
import io.iohk.scalanet.peergroup.InetMultiAddress
import io.iohk.scalanet.peergroup.kademlia.KMessage.{KRequest, KResponse}
import io.iohk.scalanet.peergroup.kademlia.{KMessage, KRouter, KRouterBeta}
import scodec.bits.BitVector

import scala.annotation.tailrec

object IntegerStreamCodec extends StreamCodec[Int]{
  override def streamDecode[B](source: B)(implicit bi: BufferInstantiator[B]): Seq[Int] = {
    val buff = bi.asByteBuffer(source)
    @tailrec
    def aux(start:Int,acum:List[Int]): List[Int] ={
      if(start + 4 < buff.capacity()) {
        val n = this.decode(start,buff).right.get
        aux(start + 4, n :: acum)
      }else acum
    }
    aux(0,Nil)
  }

  override def cleanSlate: StreamCodec[Int] = IntegerStreamCodec

  override def encode[B](t: Int)(implicit bi: BufferInstantiator[B]): B = {
    val buff = ByteBuffer.allocate(4)
    buff.putInt(t)
    bi.asB(buff)
  }

  override def decode[B](start: Int, source: B)(implicit bi: BufferInstantiator[B]): Either[Failure, Int] = {
    val buff = bi.asByteBuffer(source)
    if(buff.capacity() < start+4) Left(Failure)
    else Right(buff.getInt(start))
  }

  override def decode[B](source: B)(implicit bi: BufferInstantiator[B]): Either[Failure, Int] = this.decode(0,source)
}

object IntegerCodecContract extends CodecContract[Int]{
  override def size(t: Int): Int = 4

  override def encodeImpl(t: Int, start: Int, destination: ByteBuffer): Unit = {
    destination.putInt(start,t)
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[Int]] = {
    if(start+4 > source.capacity()) Left(Failure)
    else Right(new DecodeResult[Int](source.getInt(start),start+4))
  }
}


object CharacterStreamCodec extends StreamCodec[Char]{
  override def streamDecode[B](source: B)(implicit bi: BufferInstantiator[B]): Seq[Char] = {
    val buff = bi.asByteBuffer(source)
    @tailrec
    def aux(act:Int,acum:List[Char]): List[Char] ={
      if(act>=buff.capacity()) acum
      else aux(act+1,buff.get(act).toChar :: acum)
    }
    aux(0,Nil)
  }

  override def cleanSlate: StreamCodec[Char] = CharacterStreamCodec

  override def encode[B](t: Char)(implicit bi: BufferInstantiator[B]): B = {
    val buff = ByteBuffer.allocate(1)
    buff.putChar(t)
    bi.asB(buff)
  }

  override def decode[B](start: Int, source: B)(implicit bi: BufferInstantiator[B]): Either[Failure, Char] = {
    val buff = bi.asByteBuffer(source)
    if(start>buff.capacity()) Left(Failure)
    else Right(buff.getChar(start))
  }

  override def decode[B](source: B)(implicit bi: BufferInstantiator[B]): Either[Failure, Char] = decode(0,source)
}

object ArrayBufferInstantiator extends BufferInstantiator[Array[Byte]] {
  override def instantiateByteBuffer(size: Int): ByteBuffer = ByteBuffer.allocate(size)

  override def asB(byteBuffer: ByteBuffer): Array[Byte] = {
    val res = new Array[Byte](byteBuffer.capacity())
    for(i <- 0 until byteBuffer.capacity()){
      res(i) = byteBuffer.get(i)
    }
    res
  }

  override def asByteBuffer(b: Array[Byte]): ByteBuffer = {
    val res = instantiateByteBuffer(b.length)
    for(i <- 0 until b.length){
      res.put(i,b(i))
    }
    res
  }
}

class SeqCodecContract[A](codecContract: CodecContract[A]) extends CodecContract[Seq[A]]{

  override def size(t: Seq[A]): Int = t.foldLeft(4)((rec,x) => codecContract.size(x) + rec)

  override def encodeImpl(t: Seq[A], start: Int, destination: ByteBuffer): Unit = {
    IntegerCodecContract.encodeImpl(t.size,start,destination)
    var ind = start + 4
    for(el <- t ){
      codecContract.encodeImpl(el,ind,destination)
      ind = ind + codecContract.size(el)
    }
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[Seq[A]]] = {
    IntegerCodecContract.decodeImpl(start,source) match {
      case Left(f) => Left(f)
      case Right(_dec) => {
        @tailrec
        def aux(pos:Int,number:Int,acum:List[A]): Either[Failure, DecodeResult[List[A]]] = {
          if(number>=_dec.decoded) Right(new DecodeResult[List[A]](acum.reverse,pos))
          else{
            val dec = codecContract.decodeImpl(pos,source)
            dec match {
              case Left(f) => Left(f)
              case Right(v) => aux(v.nextIndex,number+1,v.decoded :: acum)
            }
          }
        }
        aux(_dec.nextIndex,0,Nil)
      }
    }
  }
}


class EitherCodecContract[A,B](a:CodecContract[A],b:CodecContract[B]) extends CodecContract[Either[A,B]]{
  override def size(t: Either[A, B]): Int = t match{
    case Left(elemA) => a.size(elemA) + 1
    case Right(elemB) => b.size(elemB) + 1
  }

  override def encodeImpl(t: Either[A, B], start: Int, destination: ByteBuffer): Unit = t match{
    case Left(elemA) =>{
      destination.put(start,0)
      a.encodeImpl(elemA,start+1,destination)
    }
    case Right(elemB) => {
      destination.put(start,1)
      b.encodeImpl(elemB,start+1,destination)
    }
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[Either[A, B]]] = {
    if(source.get(start)==0){
      val rec = a.decodeImpl(start+1,source)
      rec match {
        case Left(f) => Left(f)
        case Right(dec) => Right( new DecodeResult[Either[A, B]](Left(dec.decoded),dec.nextIndex))
      }
    }else if(source.get(start)==1){
      val rec = b.decodeImpl(start+1,source)
      rec match {
        case Left(f) => Left(f)
        case Right(dec) => Right( new DecodeResult[Either[A, B]](Right(dec.decoded),dec.nextIndex))
      }
    }else{
      Left(Failure)
    }
  }
}

object ByteArrayCodecContract extends CodecContract[Array[Byte]] {
  override def size(t: Array[Byte]): Int = t.length + 4

  override def encodeImpl(t: Array[Byte], start: Int, destination: ByteBuffer): Unit = {
    IntegerCodecContract.encodeImpl(t.length,start,destination)
    t.foldLeft(start+4)((pos,elem) => {destination.put(pos,elem);pos+1} )
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[Array[Byte]]] = {
    IntegerCodecContract.decodeImpl(start,source) match{
      case Left(f) => Left(f)
      case Right(size) => {
        val res = new Array[Byte](size.decoded)
        if(source.capacity() < size.nextIndex+size.decoded) {return Left(Failure)}
        for(i <- 0 until size.decoded){
          res(i) = source.get(i+size.nextIndex)
        }
        Right(new DecodeResult[Array[Byte]](res,size.decoded+size.nextIndex))
      }
    }
  }
}

object InetAddressCodecContract extends CodecContract[InetMultiAddress]{
  override def size(t: InetMultiAddress): Int = 4 + ByteArrayCodecContract.size(t.inetSocketAddress.getAddress.getAddress)

  override def encodeImpl(t: InetMultiAddress, start: Int, destination: ByteBuffer): Unit = {
    IntegerCodecContract.encodeImpl(t.inetSocketAddress.getPort,start,destination)
    val addr = t.inetSocketAddress.getAddress.getAddress
    ByteArrayCodecContract.encodeImpl(addr,start+4,destination)
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[InetMultiAddress]] = {
    IntegerCodecContract.decodeImpl(start,source) match{
      case Left(f) => Left(f)
      case Right(port) => {
        ByteArrayCodecContract.decodeImpl(port.nextIndex,source) match{
          case Left(f) => Left(f)
          case Right(addr) => {
            Right(new DecodeResult[InetMultiAddress](InetMultiAddress(new InetSocketAddress(InetAddress.getByAddress(addr.decoded),port.decoded)),addr.nextIndex))
          }
        }
      }
    }
  }
}

class NodeRecordCodeContract[A](codecContract: CodecContract[A]) extends CodecContract[KRouter.NodeRecord[A]]{
  override def size(t: KRouter.NodeRecord[A]): Int = ByteArrayCodecContract.size(t.id.toByteArray) + codecContract.size(t.routingAddress) + codecContract.size(t.messagingAddress)

  override def encodeImpl(t: KRouter.NodeRecord[A], start: Int, destination: ByteBuffer): Unit = {
    val idArray = t.id.toByteArray
    ByteArrayCodecContract.encodeImpl(idArray,start,destination)
    codecContract.encodeImpl(t.messagingAddress,start + ByteArrayCodecContract.size(idArray),destination)
    codecContract.encodeImpl(t.routingAddress,start + ByteArrayCodecContract.size(idArray) + codecContract.size(t.messagingAddress),destination)
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[KRouter.NodeRecord[A]]] = {
    ByteArrayCodecContract.decodeImpl(start,source) match{
      case Left(f) => Left(f)
      case Right(idArray) =>{
        codecContract.decodeImpl(idArray.nextIndex,source) match{
          case Left(f) => Left(f)
          case Right(messagingAddress) => {
            codecContract.decodeImpl(messagingAddress.nextIndex,source) match{
              case Left(f) => Left(f)
              case Right(routingAddress) => Right (new DecodeResult[KRouter.NodeRecord[A]](KRouter.NodeRecord[A](BitVector(idArray.decoded),routingAddress.decoded,messagingAddress.decoded),routingAddress.nextIndex))
            }
          }
        }
      }
    }
  }
}

object LongerCodecContract extends CodecContract[Long] {
  override def size(t: Long): Int = 8

  override def encodeImpl(t: Long, start: Int, destination: ByteBuffer): Unit = destination.putLong(start,t)

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[Long]] ={
    if(source.capacity() < start + 8) Left(Failure)
    else Right(new DecodeResult[Long](source.getLong(start),start+8))
  }
}

class KMessageCodecContract[A](codecContract: CodecContract[KRouter.NodeRecord[A]]) extends CodecContract[KMessage[A]]{
  val nodesSeq = new SeqCodecContract[KRouter.NodeRecord[A]](codecContract)
  override def size(t: KMessage[A]): Int = t match{
    case KRequest.FindNodes(_,nodeRecord,targetNodeId) => 1 +  16 + codecContract.size(nodeRecord) + ByteArrayCodecContract.size((targetNodeId.toByteArray))
    case KRequest.Ping(_,nodeRecord) =>  1 + 16 + codecContract.size(nodeRecord)
    case KResponse.Pong(_,nodeRecord) =>  1 +16 + codecContract.size(nodeRecord)
    case KResponse.Nodes(_,nodeRecord,nodes) => 1 + 16 + codecContract.size(nodeRecord) + nodesSeq.size(nodes)
  }

  override def encodeImpl(t: KMessage[A], start: Int, destination: ByteBuffer): Unit = t match{
    case KRequest.FindNodes(id,nodeRecord,targetNodeId) => {
      destination.put(start,0)
      LongerCodecContract.encodeImpl(id.getLeastSignificantBits,start+1,destination)
      LongerCodecContract.encodeImpl(id.getMostSignificantBits,start+9,destination)
      codecContract.encodeImpl(nodeRecord,start+17,destination)
      ByteArrayCodecContract.encodeImpl(targetNodeId.toByteArray,start+17+codecContract.size(nodeRecord),destination)
    }
    case KRequest.Ping(id,nodeRecord) => {
      destination.put(start,1)
      LongerCodecContract.encodeImpl(id.getLeastSignificantBits,start+1,destination)
      LongerCodecContract.encodeImpl(id.getMostSignificantBits,start+1+8,destination)
      codecContract.encodeImpl(nodeRecord,start+17,destination)
    }
    case KResponse.Pong(id,nodeRecord) =>{
      destination.put(start,2)
      LongerCodecContract.encodeImpl(id.getLeastSignificantBits,start+1,destination)
      LongerCodecContract.encodeImpl(id.getMostSignificantBits,start+1+8,destination)
      codecContract.encodeImpl(nodeRecord,start+17,destination)
    }
    case KResponse.Nodes(id,nodeRecord,nodes) => {
      destination.put(start,3)
      LongerCodecContract.encodeImpl(id.getLeastSignificantBits,start+1,destination)
      LongerCodecContract.encodeImpl(id.getMostSignificantBits,start+1+8,destination)
      codecContract.encodeImpl(nodeRecord,start+17,destination)
      nodesSeq.encodeImpl(nodes,start+17+codecContract.size(nodeRecord),destination)
    }
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[KMessage[A]]] = {
    if(source.get(start)==0.toByte){
      LongerCodecContract.decodeImpl(start+1,source) match {
        case Left(f) => Left(f)
        case Right(leastSignificantBits) => {
          LongerCodecContract.decodeImpl(leastSignificantBits.nextIndex,source) match{
            case Left(f) => Left(f)
            case Right(mostSignificantBits) => {
              codecContract.decodeImpl(mostSignificantBits.nextIndex,source) match{
                case Left(f) => Left(f)
                case Right(nodeRecord) => ByteArrayCodecContract.decodeImpl(nodeRecord.nextIndex,source) match{
                  case Left(f) => Left(f)
                  case Right(targetNodeId) => Right(new DecodeResult[KMessage[A]](KRequest.FindNodes(new UUID(mostSignificantBits.decoded,leastSignificantBits.decoded) ,nodeRecord.decoded,BitVector(targetNodeId.decoded) ),targetNodeId.nextIndex))
                }
              }
            }
          }
        }
      }
    }else if(source.get(start)==1.toByte){
      LongerCodecContract.decodeImpl(start+1,source) match {
        case Left(f) => Left(f)
        case Right(leastSignificantBits) => {
          LongerCodecContract.decodeImpl(leastSignificantBits.nextIndex,source) match{
            case Left(f) => Left(f)
            case Right(mostSignificantBits) => {
              codecContract.decodeImpl(mostSignificantBits.nextIndex,source) match{
                case Left(f) => Left(f)
                case Right(nodeRecord) => Right (new DecodeResult[KMessage[A]](KRequest.Ping(new UUID(mostSignificantBits.decoded,leastSignificantBits.decoded),nodeRecord.decoded),nodeRecord.nextIndex))
              }
            }
          }
        }
      }
    }else if(source.get(start)==2.toByte){
      LongerCodecContract.decodeImpl(start+1,source) match {
        case Left(f) => Left(f)
        case Right(leastSignificantBits) => {
          LongerCodecContract.decodeImpl(leastSignificantBits.nextIndex,source) match{
            case Left(f) => Left(f)
            case Right(mostSignificantBits) => {
              codecContract.decodeImpl(mostSignificantBits.nextIndex,source) match{
                case Left(f) => Left(f)
                case Right(nodeRecord) => Right (new DecodeResult[KMessage[A]](KResponse.Pong(new UUID(mostSignificantBits.decoded,leastSignificantBits.decoded),nodeRecord.decoded),nodeRecord.nextIndex))
              }
            }
          }
        }
      }
    }else if(source.get(start)==3.toByte){
      LongerCodecContract.decodeImpl(start+1,source) match {
        case Left(f) => Left(f)
        case Right(leastSignificantBits) => {
          LongerCodecContract.decodeImpl(leastSignificantBits.nextIndex,source) match{
            case Left(f) => Left(f)
            case Right(mostSignificantBits) => {
              codecContract.decodeImpl(mostSignificantBits.nextIndex,source) match{
                case Left(f) => Left(f)
                case Right(nodeRecord) => nodesSeq.decodeImpl(nodeRecord.nextIndex,source) match{
                  case Left(f) => Left(f)
                  case Right(nodes) => Right(new DecodeResult[KMessage[A]](KResponse.Nodes(new UUID(mostSignificantBits.decoded,leastSignificantBits.decoded),nodeRecord.decoded,nodes.decoded ) ,nodes.nextIndex ) )
                }
              }
            }
          }
        }
      }
    }else Left(Failure)
  }
}

class StreamCodecFromContract[A](codecContract: CodecContract[A]) extends StreamCodec[A] {
  /*override def streamDecode[B](source: B)(implicit bi: BufferInstantiator[B]): Seq[A] = {
    val buff = bi.asByteBuffer(source)
    @tailrec
    def aux(pos:Int,acum:List[A]):List[A] = {
      if(pos>=buff.capacity()) acum
      else {
        codecContract.decodeImpl(pos,buff) match{
          case Left(_) => throw new RuntimeException("PROBLEM DECODING")
          case Right(dec) => aux(dec.nextIndex,dec.decoded::acum)
        }
      }
    }
    aux(0,Nil)
  }*/

  override def streamDecode[B](source: B)(implicit bi: BufferInstantiator[B]): Seq[A] = {
    val buff = bi.asByteBuffer(source)
    def aux(pos:Int): Stream[A] = {
      if(pos >= buff.capacity()) Stream()
      else{
        codecContract.decodeImpl(pos,buff) match{
          case Left(_) => throw new RuntimeException("PROBLEM DECODING")
          case Right(dec) => {dec.decoded #:: aux(dec.nextIndex)}
        }
      }
    }
    aux(0)
  }

  override def cleanSlate: StreamCodec[A] = this

  override def encode[B](t: A)(implicit bi: BufferInstantiator[B]): B = {
    val res = ByteBuffer.allocate(codecContract.size(t))
    codecContract.encodeImpl(t,0,res)
    bi.asB(res)
  }

  override def decode[B](start: Int, source: B)(implicit bi: BufferInstantiator[B]): Either[Failure, A] = {
    codecContract.decodeImpl(start,bi.asByteBuffer(source)) match{
      case Left(f) => Left(f)
      case Right(res) => Right(res.decoded)
    }
  }

  override def decode[B](source: B)(implicit bi: BufferInstantiator[B]): Either[Failure, A] = decode(0,source)
}

object InetSocketCodecContract extends CodecContract[InetSocketAddress] {
  override def size(t: InetSocketAddress): Int = InetAddressCodecContract.size(InetMultiAddress(t))

  override def encodeImpl(t: InetSocketAddress, start: Int, destination: ByteBuffer): Unit = InetAddressCodecContract.encodeImpl(InetMultiAddress(t),start,destination)

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[InetSocketAddress]] = InetAddressCodecContract.decodeImpl(start,source) match{
    case Left(f) => Left(f)
    case Right(dec) => Right(new DecodeResult[InetSocketAddress](dec.decoded.inetSocketAddress,dec.nextIndex))}
}

object UUIDCodecContract extends CodecContract[UUID] {
  override def size(t: UUID): Int = 16

  override def encodeImpl(t: UUID, start: Int, destination: ByteBuffer): Unit = {
    LongerCodecContract.encodeImpl(t.getMostSignificantBits,start,destination)
    LongerCodecContract.encodeImpl(t.getLeastSignificantBits,start+8,destination)
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[UUID]] = {
    LongerCodecContract.decodeImpl(start,source) match{
      case Left(f) => Left(f)
      case Right(mostSignificant) => LongerCodecContract.decodeImpl(mostSignificant.nextIndex,source) match{
        case Left(f) => Left(f)
        case Right(leastSignificant) => Right(new DecodeResult[UUID](new UUID(mostSignificant.decoded,leastSignificant.decoded),leastSignificant.nextIndex))
      }
    }
  }
}

class BroadCastMessageCodec2[M](codecContract: CodecContract[M]) extends CodecContract[KRouterBeta.BroadCastMessage[M] ] {
  override def size(t: KRouterBeta.BroadCastMessage[M]): Int = t match{
    case KRouterBeta.BroadCastMessage(_,sender,message) => 8 + ByteArrayCodecContract.size(sender.toByteArray) + codecContract.size(message)
  }

  override def encodeImpl(t: KRouterBeta.BroadCastMessage[M], start: Int, destination: ByteBuffer): Unit = t match{
    case KRouterBeta.BroadCastMessage(id,sender,message) => {
      LongerCodecContract.encodeImpl(id,start,destination)
      ByteArrayCodecContract.encodeImpl(sender.toByteArray,start+8,destination)
      codecContract.encodeImpl(message,start+8+ByteArrayCodecContract.size(sender.toByteArray),destination)
    }
  }

  override def decodeImpl(start: Int, source: ByteBuffer): Either[Failure, DecodeResult[KRouterBeta.BroadCastMessage[M]]] = {
    LongerCodecContract.decodeImpl(start,source) match{
      case Left(f) => Left(f)
      case Right(id) => ByteArrayCodecContract.decodeImpl(id.nextIndex,source) match {
        case Left(f) => Left(f)
        case Right(sender) => codecContract.decodeImpl(sender.nextIndex,source) match{
          case Left(f) => Left(f)
          case Right(message) => Right(new DecodeResult[KRouterBeta.BroadCastMessage[M]](KRouterBeta.BroadCastMessage(id.decoded,BitVector(sender.decoded),message.decoded),message.nextIndex))
        }
      }
    }
  }
}
