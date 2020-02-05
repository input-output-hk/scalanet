package io.iohk.scalanet

import java.math.BigInteger
import java.security.SecureRandom

import org.spongycastle.asn1.sec.SECNamedCurves
import org.spongycastle.asn1.x9.X9ECParameters
import org.spongycastle.crypto.AsymmetricCipherKeyPair
import org.spongycastle.crypto.digests.SHA256Digest
import org.spongycastle.crypto.generators.ECKeyPairGenerator
import org.spongycastle.crypto.params.{
  AsymmetricKeyParameter,
  ECDomainParameters,
  ECKeyGenerationParameters,
  ECPrivateKeyParameters,
  ECPublicKeyParameters
}
import org.spongycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}

// Based in mantis cryptography package
// https://github.com/input-output-hk/mantis/blob/master/src/main/scala/io/iohk/ethereum/crypto/package.scala
package object crypto {

  val curveParams: X9ECParameters = SECNamedCurves.getByName("secp256k1")
  val curve: ECDomainParameters =
    new ECDomainParameters(curveParams.getCurve, curveParams.getG, curveParams.getN, curveParams.getH)

  def decodePublicKey(key: Array[Byte]): AsymmetricKeyParameter = {
    new ECPublicKeyParameters(curve.getCurve.decodePoint(key), curve)
  }

  def keyPairToByteArrays(
      keyPair: AsymmetricCipherKeyPair
  ): (Array[Byte], Array[Byte]) = {
    val prvKey =
      keyPair.getPrivate.asInstanceOf[ECPrivateKeyParameters].getD.toByteArray
    val pubKey = keyPair.getPublic
      .asInstanceOf[ECPublicKeyParameters]
      .getQ
      .getEncoded(false)
      .tail
    (prvKey, pubKey)
  }

  def encodeKey(key: AsymmetricKeyParameter): Array[Byte] = {
    key.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(true)
  }

  def generateKeyPair(secureRandom: SecureRandom): AsymmetricCipherKeyPair = {
    val generator = new ECKeyPairGenerator
    generator.init(new ECKeyGenerationParameters(curve, secureRandom))
    generator.generateKeyPair()
  }

  def verify(data: Array[Byte], sign: ECDSASignature, publicKey: AsymmetricKeyParameter): Boolean = {
    val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest))
    signer.init(false, publicKey.asInstanceOf[ECPublicKeyParameters])
    signer.verifySignature(data, sign.r.bigInteger, sign.s.bigInteger)
  }

}
