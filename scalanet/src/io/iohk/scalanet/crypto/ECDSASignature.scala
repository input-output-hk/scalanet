package io.iohk.scalanet.crypto

import org.spongycastle.crypto.AsymmetricCipherKeyPair
import org.spongycastle.crypto.digests.SHA256Digest
import org.spongycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}

object ECDSASignature {

  val SLength = 32
  val RLength = 32
  val EncodedLength: Int = RLength + SLength

  def apply(r: Array[Byte], s: Array[Byte]): ECDSASignature = {
    ECDSASignature(BigInt(1, r), BigInt(1, s))
  }

  def sign(message: Array[Byte], keyPair: AsymmetricCipherKeyPair, chainId: Option[Byte] = None): ECDSASignature = {
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest))
    signer.init(true, keyPair.getPrivate)
    val components = signer.generateSignature(message)
    val r = components(0)
    val s = ECDSASignature.canonicalise(components(1))

    ECDSASignature(r, s)
  }

  private def canonicalise(s: BigInt): BigInt = {
    val halfCurveOrder: BigInt = curveParams.getN.shiftRight(1)
    if (s > halfCurveOrder) BigInt(curve.getN) - s
    else s
  }
}

/**
  * ECDSASignature r and s are same as in documentation where signature is represented by tuple (r, s)
  * @param r - x coordinate of ephemeral public key modulo curve order N
  * @param s - part of the signature calculated with signer private key
  */
case class ECDSASignature(r: BigInt, s: BigInt)
