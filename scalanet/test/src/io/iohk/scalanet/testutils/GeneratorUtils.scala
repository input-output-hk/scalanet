package io.iohk.scalanet.testutils

import java.security.{KeyPair, SecureRandom}

import io.iohk.scalanet.crypto.CryptoUtils
import org.scalacheck.{Arbitrary, Gen}

object GeneratorUtils {
  def genKey(curveName: String, rnd: SecureRandom): Gen[KeyPair] = {
    Gen.resultOf { s: String =>
      CryptoUtils.genEcKeyPair(rnd, curveName)
    }
  }

  def randomSizeByteArrayGen(minSize: Int, maxSize: Int): Gen[Array[Byte]] =
    Gen.choose(minSize, maxSize).flatMap(byteArrayOfNItemsGen)

  def byteArrayOfNItemsGen(n: Int): Gen[Array[Byte]] = Gen.listOfN(n, Arbitrary.arbitrary[Byte]).map(_.toArray)

}
