package io.iohk.scalanet.crypto

import java.security.SecureRandom

import io.iohk.scalanet.peergroup.dynamictls.Secp256k1
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks._
import io.iohk.scalanet.testutils.GeneratorUtils
import org.scalacheck.Gen

class SignatureVerificationSpec extends FlatSpec with Matchers {
  val rnd = new SecureRandom()
  val minDataSize = 0
  val maxDataSize = 1024

  "Signature verification" should "success for all properly signed data" in {
    forAll(GeneratorUtils.randomSizeByteArrayGen(minDataSize, maxDataSize)) { data =>
      val key = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
      val sig = CryptoUtils.signEcdsa(data, key.getPrivate, rnd)
      val verification = CryptoUtils.verifyEcdsa(data, sig, key.getPublic)
      assert(verification)
    }
  }

  it should "success for bouncy castle converted keys" in {
    forAll(GeneratorUtils.randomSizeByteArrayGen(minDataSize, maxDataSize)) { data =>
      val bcKey = CryptoUtils.generateKeyPair(rnd)
      val key = CryptoUtils.convertBcToJceKeyPair(bcKey)
      val sig = CryptoUtils.signEcdsa(data, key.getPrivate, rnd)
      val verification = CryptoUtils.verifyEcdsa(data, sig, key.getPublic)
      assert(verification)
    }
  }

  it should "fail when verifying with wrong public key" in {
    forAll(GeneratorUtils.randomSizeByteArrayGen(minDataSize, maxDataSize)) { data =>
      val key = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
      val key1 = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
      val sig = CryptoUtils.signEcdsa(data, key.getPrivate, rnd)
      val verification = CryptoUtils.verifyEcdsa(data, sig, key1.getPublic)
      assert(!verification)
    }
  }

  it should "fail when verifying with changed signature " in {
    forAll(GeneratorUtils.randomSizeByteArrayGen(minDataSize, maxDataSize), Gen.choose(10, 40)) { (data, idx) =>
      val key = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
      val sig = CryptoUtils.signEcdsa(data, key.getPrivate, rnd)
      val newByte = (sig(idx) + 1).toByte
      sig(idx) = newByte
      val verification = CryptoUtils.verifyEcdsa(data, sig, key.getPublic)
      assert(!verification)
    }
  }

  it should "fail when verifying with changed data " in {
    forAll(GeneratorUtils.randomSizeByteArrayGen(50, maxDataSize), Gen.choose(10, 40)) { (data, idx) =>
      val key = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
      val sig = CryptoUtils.signEcdsa(data, key.getPrivate, rnd)
      val newByte = (data(idx) + 1).toByte
      data(idx) = newByte
      val verification = CryptoUtils.verifyEcdsa(data, sig, key.getPublic)
      assert(!verification)
    }
  }

}
