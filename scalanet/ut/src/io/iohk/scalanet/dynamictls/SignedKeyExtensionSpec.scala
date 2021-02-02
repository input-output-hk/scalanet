package io.iohk.scalanet.dynamictls

import java.security.SecureRandom
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.crypto.CryptoUtils.{SHA256withECDSA, Secp256r1}
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSExtension.{
  ExtensionPublicKey,
  SignedKey,
  SignedKeyExtensionNodeData
}
import io.iohk.scalanet.peergroup.dynamictls.Secp256k1
import io.iohk.scalanet.testutils.GeneratorUtils
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks._

class SignedKeyExtensionSpec extends FlatSpec with Matchers {
  val rnd = new SecureRandom()

  "SignedKeyExtension" should "create extension from correct data" in {
    forAll(GeneratorUtils.genKey(Secp256k1.curveName, rnd)) { keyPair =>
      val extension = ExtensionPublicKey(Secp256k1, keyPair.getPublic)
      assert(extension.isSuccess)
    }
  }

  it should "create extension from converted key" in {
    val keyPair = CryptoUtils.generateKeyPair(rnd)
    val converted = CryptoUtils.convertBcToJceKeyPair(keyPair)
    val extension = ExtensionPublicKey(Secp256k1, converted.getPublic)
    assert(extension.isSuccess)
  }

  it should "not create extension from wrong key" in {
    val keyPair = CryptoUtils.genEcKeyPair(rnd, Secp256r1.name)
    val extension = ExtensionPublicKey(Secp256k1, keyPair.getPublic)
    assert(extension.isFailure)
  }

  it should "encode and decode correct extension" in {
    forAll(GeneratorUtils.genKey(Secp256k1.curveName, rnd)) { keyPair =>
      val extension = ExtensionPublicKey(Secp256k1, keyPair.getPublic).get
      val enc = ExtensionPublicKey.extensionPublicKeyCodec.encode(extension).require
      val dec = ExtensionPublicKey.extensionPublicKeyCodec.decodeValue(enc).require
      assert(extension == dec)
    }
  }

  it should "successfully build extension node data" in {
    forAll(GeneratorUtils.genKey(Secp256k1.curveName, rnd)) { hostKey =>
      val nodeData = SignedKeyExtensionNodeData(Secp256k1, hostKey, Secp256r1, rnd, SHA256withECDSA)
      assert(nodeData.isSuccess)
    }
  }

  it should "successfully parse node data from extension" in {
    forAll(GeneratorUtils.genKey(Secp256k1.curveName, rnd)) { hostKey =>
      val nodeData = SignedKeyExtensionNodeData(Secp256k1, hostKey, Secp256r1, rnd, SHA256withECDSA).get
      val extensionBytes = nodeData.certWithExtension.getExtensionValue(SignedKey.extensionIdentifier)
      val recoveredSignedKey = SignedKey.parseAsn1EncodedValue(extensionBytes)
      assert(recoveredSignedKey.isSuccessful)
      assert(recoveredSignedKey.require.publicKey.getNodeId == nodeData.calculatedNodeId)
    }
  }

  it should "fail to parse random bytes without throwing exceptions" in {
    forAll(GeneratorUtils.randomSizeByteArrayGen(0, 1024)) { randomBytes =>
      val recoveredSignedKey = SignedKey.parseAsn1EncodedValue(randomBytes)
      assert(recoveredSignedKey.isFailure)
    }
  }

}
