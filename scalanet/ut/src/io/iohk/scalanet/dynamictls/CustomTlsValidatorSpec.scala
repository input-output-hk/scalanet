package io.iohk.scalanet.dynamictls

import java.security.SecureRandom
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.crypto.CryptoUtils.Secp256r1
import io.iohk.scalanet.peergroup.dynamictls.CustomTlsValidator.{
  NoCertExtension,
  ServerIdNotMatchExpected,
  WrongCertificateDate,
  WrongExtensionFormat,
  WrongExtensionSignature,
  WrongNumberOfCertificates
}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSExtension.{Extension, SignedKey, SignedKeyExtensionNodeData}
import io.iohk.scalanet.peergroup.dynamictls.{CustomTlsValidator, DynamicTLSExtension, Secp256k1}
import io.iohk.scalanet.testutils.GeneratorUtils
import org.bouncycastle.asn1.DERGeneralString
import org.joda.time.DateTime
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks._
import org.scalatest.{FlatSpec, Matchers}
import scodec.bits.BitVector

class CustomTlsValidatorSpec extends FlatSpec with Matchers {
  val rnd = new SecureRandom()
  val interval = DynamicTLSExtension.getInterval()
  val beforeDate = interval.getStart.toDate
  val afterDate = interval.getEnd.toDate

  "CustomTlsValidator" should "successfully validate client certificates" in {
    forAll(GeneratorUtils.genKey(Secp256k1.curveName, rnd)) { keyPair =>
      val extension = SignedKeyExtensionNodeData(Secp256k1, keyPair, Secp256r1, rnd).get
      val result = CustomTlsValidator.validateCertificates(Array(extension.certWithExtension), None)
      assert(result.isRight)
    }
  }

  it should "successfully validate server certificates" in {
    forAll(GeneratorUtils.genKey(Secp256k1.curveName, rnd)) { keyPair =>
      val extension = SignedKeyExtensionNodeData(Secp256k1, keyPair, Secp256r1, rnd).get
      val result =
        CustomTlsValidator.validateCertificates(Array(extension.certWithExtension), Some(extension.calculatedNodeId))
      assert(result.isRight)
    }
  }

  it should "fail to validate server with bad id" in {
    forAll(GeneratorUtils.genKey(Secp256k1.curveName, rnd), GeneratorUtils.byteArrayOfNItemsGen(64)) {
      (keyPair, badId) =>
        val extension = SignedKeyExtensionNodeData(Secp256k1, keyPair, Secp256r1, rnd).get
        val result = CustomTlsValidator.validateCertificates(Array(extension.certWithExtension), Some(BitVector(badId)))
        assert(result == Left(ServerIdNotMatchExpected))
    }
  }

  it should "fail to validate wrong number of certificates" in {
    val keyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
    val extension = SignedKeyExtensionNodeData(Secp256k1, keyPair, Secp256r1, rnd).get

    val result = CustomTlsValidator.validateCertificates(Array(extension.certWithExtension), None)
    assert(result.isRight)

    val result1 = CustomTlsValidator.validateCertificates(Array(), None)
    assert(result1 == Left(WrongNumberOfCertificates))

    val result2 =
      CustomTlsValidator.validateCertificates(Array(extension.certWithExtension, extension.certWithExtension), None)
    assert(result2 == Left(WrongNumberOfCertificates))
  }

  it should "fail to validate certificate without required extension" in {
    val keyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
    val cer = CryptoUtils.buildCertificateWithExtensions(keyPair, rnd, List(), beforeDate, afterDate)
    val result = CustomTlsValidator.validateCertificates(Array(cer), None)
    assert(result == Left(NoCertExtension))
  }

  it should "fail to validate certificate with some wrong extension" in {
    val keyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
    val badExtension =
      Extension(SignedKey.signedKeyExtensionIdentifier, isCritical = true, new DERGeneralString("randomstring"))
    val cer = CryptoUtils.buildCertificateWithExtensions(keyPair, rnd, List(badExtension), beforeDate, afterDate)
    val result = CustomTlsValidator.validateCertificates(Array(cer), None)
    assert(result == Left(WrongExtensionFormat))
  }

  it should "fail to validate certificate with wrong dates" in {
    val hostkeyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
    val connectionKeyPair = CryptoUtils.genTlsSupportedKeyPair(rnd, Secp256r1)
    val connectionKeyPairPublicKeyAsBytes = CryptoUtils.getEcPublicKey(connectionKeyPair.getPublic).get
    val (_, extension) =
      SignedKey.buildSignedKeyExtension(Secp256k1, hostkeyPair, connectionKeyPairPublicKeyAsBytes, rnd).require

    val notValidBeforeDate = DateTime.now().plusYears(1).toDate
    val cer =
      CryptoUtils.buildCertificateWithExtensions(connectionKeyPair, rnd, List(extension), notValidBeforeDate, afterDate)
    val result = CustomTlsValidator.validateCertificates(Array(cer), None)
    assert(result == Left(WrongCertificateDate))

    val notValidAfterDate = DateTime.now().minusDays(1).toDate
    val cer1 =
      CryptoUtils.buildCertificateWithExtensions(connectionKeyPair, rnd, List(extension), beforeDate, notValidAfterDate)
    val result1 = CustomTlsValidator.validateCertificates(Array(cer1), None)
    assert(result1 == Left(WrongCertificateDate))
  }

  it should "fail to validate certificate with wrong extension signature" in {
    val hostkeyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
    val connectionKeyPair = CryptoUtils.genTlsSupportedKeyPair(rnd, Secp256r1)
    val fakeKey = BitVector(GeneratorUtils.byteArrayOfNItemsGen(64).sample.get)
    val (_, extension) = SignedKey.buildSignedKeyExtension(Secp256k1, hostkeyPair, fakeKey, rnd).require

    val cer = CryptoUtils.buildCertificateWithExtensions(connectionKeyPair, rnd, List(extension), beforeDate, afterDate)
    val result = CustomTlsValidator.validateCertificates(Array(cer), None)
    assert(result == Left(WrongExtensionSignature))
  }

}
