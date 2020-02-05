package io.iohk.scalanet.peergroup

import java.math.BigInteger
import java.security.SecureRandom

import monix.eval.Task
import monix.reactive.observables.ConnectableObservable
import scodec.Codec
import scodec.bits.BitVector
import io.iohk.scalanet.crypto
import io.iohk.scalanet.crypto.ECDSASignature
import io.iohk.scalanet.monix_subject.ConnectableSubject
import monix.execution.Scheduler
import monix.reactive.Observable
import org.spongycastle.crypto.AsymmetricCipherKeyPair

class SecureECPeerGroup[M](subnet: PeerGroup[BitVector, Seq[BigInteger]], keyPair: AsymmetricCipherKeyPair)(
    implicit codec: Codec[M],
    scheduler: Scheduler)
    extends PeerGroup[BitVector, M] {
  val random = new SecureRandom()

  private class SecureECChannel(
      subChannel: Channel[BitVector, Seq[BigInteger]],
      g: BigInteger,
      encryptorNumber: BigInteger,
      decryptorNumber: BigInteger,
      p: BigInteger,
      subChannelRefCount: Observable[Seq[BigInteger]])
      extends Channel[BitVector, M] {

    override def to: BitVector = subChannel.to

    override def sendMessage(message: M): Task[Unit] = {
      val encoded = codec.encode(message)
      val _blocks =
        crypto.Utils
          .divideInBlocks(encoded.toOption.get.toByteArray, (31).toByte)
      val blocks = _blocks.map(x => new BigInteger(x))
      if (!blocks.forall(x => x.compareTo(p) < 0)) throw new RuntimeException("PROBLEM WITH SIZE")
      val toSend = blocks.map(x => {
        encryptorNumber.multiply(x).mod(p)
      })
      subChannel.sendMessage(toSend)
    }

    val connectable = ConnectableSubject[M]()

    subChannelRefCount
      .foreachL(m => {
        val decrypted = m.map(x => decryptorNumber.multiply(x).mod(p)).toArray
        val encoded =
          crypto.Utils.ensamblateBlocks(decrypted.map(x => x.toByteArray), (31).toByte)
        connectable.onNext(codec.decode(BitVector(encoded)).toOption.get.value)
      })
      .runAsyncAndForget

    override def in: ConnectableObservable[M] = connectable

    override def close(): Task[Unit] = subChannel.close()
  }

  override def processAddress: BitVector = subnet.processAddress

  override def initialize(): Task[Unit] =
    subnet
      .initialize()
      .map(_ => {
        subnet
          .server()
          .refCount
          .collectChannelCreated
          .foreachL(ch => {
            val chRefCount = ch.in.refCount
            chRefCount.headL
              .map(numbers => {
                if (numbers.length != 5) throw new RuntimeException("PROTOCOL BREAK")
                val g = numbers(1)
                val p = numbers(2)
                val publicKeyForain = numbers.head
                val sign = ECDSASignature(numbers(3), numbers(4))
                val valid = crypto.verify(
                  publicKeyForain.toByteArray ++ g.toByteArray ++ p.toByteArray,
                  sign,
                  crypto.decodePublicKey(ch.to.toByteArray))
                if (valid) {
                  val a = new BigInteger({
                    val arr = new Array[Byte](32); random.nextBytes(arr); arr
                  }).mod(p).abs()
                  val publicNumberResponse = g.modPow(a, p)
                  val respSign = crypto.ECDSASignature.sign(publicNumberResponse.toByteArray, keyPair)
                  ch.sendMessage(Seq(publicNumberResponse, respSign.r.bigInteger, respSign.s.bigInteger))
                    .runAsyncAndForget
                  val channel = new SecureECChannel(
                    ch,
                    g,
                    publicKeyForain.modPow(a, p),
                    publicKeyForain.modPow(p.subtract(BigInteger.valueOf(1)).subtract(a), p),
                    p,
                    chRefCount)
                  connectable.onNext(PeerGroup.ServerEvent.ChannelCreated[BitVector, M](channel))
                } else ch.close().runAsyncAndForget
              })
              .runAsyncAndForget
          })
          .runAsyncAndForget
      })

  override def client(to: BitVector): Task[Channel[BitVector, M]] = {
    val p = BigInteger.probablePrime(256, random)
    val g = new BigInteger({ val arr = new Array[Byte](32); random.nextBytes(arr); arr })
    val a = new BigInteger({ val arr = new Array[Byte](32); random.nextBytes(arr); arr }).mod(p).abs()
    subnet
      .client(to)
      .flatMap(ch => {
        val publicNumber = g.modPow(a, p)
        val sign = crypto.ECDSASignature.sign(publicNumber.toByteArray ++ g.toByteArray ++ p.toByteArray, keyPair)
        ch.sendMessage(Seq(publicNumber, g, p, sign.r.bigInteger, sign.s.bigInteger))
          .flatMap(_ => ch.in.refCount.headL)
          .map(_publicKeyForain => {
            if (_publicKeyForain.length != 3) throw new RuntimeException("PROTOCOL BREAK");
            val publicKeyForain = _publicKeyForain.head
            val respSign = ECDSASignature(_publicKeyForain(1), _publicKeyForain(2))
            if (crypto.verify(_publicKeyForain.head.toByteArray, respSign, crypto.decodePublicKey(to.toByteArray)))
              new SecureECChannel(
                ch,
                g,
                publicKeyForain.modPow(a, p),
                publicKeyForain.modPow(p.subtract(BigInteger.valueOf(1)).subtract(a), p),
                p,
                ch.in.refCount)
            else throw new RuntimeException("SIGN CAN'T BE VERIFICATED")
          })
      })
  }

  val connectable = ConnectableSubject[PeerGroup.ServerEvent[BitVector, M]]()

  override def server(): ConnectableObservable[PeerGroup.ServerEvent[BitVector, M]] = connectable

  override def shutdown(): Task[Unit] = subnet.shutdown()
}
