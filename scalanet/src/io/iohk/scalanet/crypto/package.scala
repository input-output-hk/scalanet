package io.iohk.scalanet

import java.math.BigInteger
import java.security.SecureRandom

import org.spongycastle.asn1.sec.SECNamedCurves
import org.spongycastle.asn1.x9.X9ECParameters
import org.spongycastle.crypto.AsymmetricCipherKeyPair
import org.spongycastle.crypto.digests.SHA256Digest
import org.spongycastle.crypto.generators.ECKeyPairGenerator
import org.spongycastle.crypto.params.{ECDomainParameters, ECKeyGenerationParameters, ECPrivateKeyParameters, ECPublicKeyParameters}
import org.spongycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}

package  object crypto {

  val curveParams: X9ECParameters = SECNamedCurves.getByName("secp256k1")
  val curve: ECDomainParameters = new ECDomainParameters(curveParams.getCurve, curveParams.getG, curveParams.getN, curveParams.getH)

  def recuperateEncodedKey(key:Array[Byte] ): ECPublicKeyParameters ={
    new ECPublicKeyParameters(curve.getCurve.decodePoint(key),curve)
  }
  def keyPairToByteArrays(keyPair: AsymmetricCipherKeyPair): (Array[Byte], Array[Byte]) = {
    val prvKey = keyPair.getPrivate.asInstanceOf[ECPrivateKeyParameters].getD.toByteArray
    val pubKey = keyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail
    (prvKey, pubKey)
  }

  def encodeKey(key: ECPublicKeyParameters):Array[Byte] = {
   key.getQ.getEncoded(true)
  }

  def generateKeyPair(secureRandom: SecureRandom): (ECPrivateKeyParameters,ECPublicKeyParameters) = {
    val generator = new ECKeyPairGenerator
    generator.init(new ECKeyGenerationParameters(curve, secureRandom))
    val res = generator.generateKeyPair()
    (res.getPrivate.asInstanceOf[ECPrivateKeyParameters],res.getPublic.asInstanceOf[ECPublicKeyParameters])
  }

  private def canonicalise(s: BigInteger): BigInteger = {
    val halfCurveOrder: BigInteger = curveParams.getN.shiftRight(1)
    if (s.compareTo(halfCurveOrder) > 0) curve.getN.subtract(s)
    else s
  }

  def sign(data:Array[Byte],privateKey: ECPrivateKeyParameters):(BigInteger,BigInteger) = {
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest))
    signer.init(true, privateKey)
    val components = signer.generateSignature(data)
    (components(0),canonicalise(components(1)))
  }

  def verify(data:Array[Byte],r:BigInteger,s:BigInteger,publicKey:ECPublicKeyParameters): Boolean = {
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest))
    signer.init(false, publicKey)
    signer.verifySignature(data,r,s)
  }

}
