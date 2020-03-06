package io.iohk.scalanet.peergroup.dynamictls

import java.security.cert.X509Certificate
import java.security.{KeyPair, PublicKey, SecureRandom}
import java.time.{Clock, LocalDateTime, ZoneOffset}
import java.util.Date

import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.crypto.CryptoUtils.SupportedCurves
import org.bouncycastle.asn1._
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.math.ec.custom.sec.SecP256K1Curve
import scodec.bits.BitVector
import scodec.codecs.{Discriminated, Discriminator, ascii, bits, uint8}
import scodec.{Attempt, Codec, DecodeResult, SizeBound}

import scala.util.{Failure, Success, Try}

sealed trait KeyType {
  def n: Int
}
object KeyType {
  implicit val d = Discriminated[KeyType, Int](uint8)
}

/**
  *  Standard Ethereum EC encoding for Secp256k1 public and private keys i.e 65 bytes public key, where
  *  first indicated if key is compressed or not, and last 64 bytes are public uncompressed point on Secp256k1 curve
  */
case object Secp256k1 extends KeyType {
  val curveName = "secp256k1"
  val n = 2
  implicit val Secp256k1disc = Discriminator[KeyType, Secp256k1.type, Int](n)
}

private[scalanet] object DynamicTLSExtension {

  /**
    * Prefix is defined in libp2p spec: `The peer signs the concatenation of the string libp2p-tls-handshake:`
    */
  val prefix = "libp2p-tls-handshake:"

  val prefixAsBytes = ascii.encode(prefix).require

  /**
    * In specs extension is defined as protobuf:
    * message PublicKey {
    *  required KeyType Type = 1;
    *  required bytes Data = 2;
    * }
    *
    */
  case class ExtensionPublicKey private (keyType: KeyType, encodedPublicKey: PublicKey)

  object ExtensionPublicKey {
    private val keyCodec = Codec[KeyType]

    val extensionPublicKeyCodec = new Codec[ExtensionPublicKey] {
      override def encode(value: ExtensionPublicKey): Attempt[BitVector] = {
        for {
          key <- keyCodec.encode(value.keyType)
          public <- value.keyType match {
            case Secp256k1 => Attempt.fromTry(CryptoUtils.getEcPublicKey(value.encodedPublicKey))
          }
        } yield key ++ public
      }

      override def decode(bits: BitVector): Attempt[DecodeResult[ExtensionPublicKey]] = {
        for {
          keyTypeResult <- keyCodec.decode(bits)
          rest <- keyTypeResult.value match {
            case Secp256k1 => Attempt.fromTry(CryptoUtils.getKeyFromBytes(keyTypeResult.remainder.toByteArray))
          }
        } yield DecodeResult(new ExtensionPublicKey(keyTypeResult.value, rest), BitVector.empty)
      }

      override def sizeBound: SizeBound = SizeBound.unknown
    }

    implicit class ExtensionPublicKeyOps(key: ExtensionPublicKey) {
      def getNodeId: BitVector = {
        key.keyType match {
          case Secp256k1 =>
            // we can .get as we are passing key from properly constructed ExtensionPublicKey object
            CryptoUtils.getEcPublicKey(key.encodedPublicKey).get.drop(8)
        }
      }
    }

    def apply(keyType: KeyType, encodedPublicKey: PublicKey): Try[ExtensionPublicKey] = {
      keyType match {
        case Secp256k1 =>
          for {
            bouncPubKey <- CryptoUtils.getBouncyCastlePubKey(encodedPublicKey.getEncoded, encodedPublicKey.getAlgorithm)
            ecpublicKey <- Try(bouncPubKey.asInstanceOf[org.bouncycastle.jce.interfaces.ECPublicKey].getParameters)
            curve = ecpublicKey.getCurve
            _ <- if (curve.isInstanceOf[SecP256K1Curve]) Success(())
            else Failure(new RuntimeException("Key type do not match provided key"))
          } yield new ExtensionPublicKey(Secp256k1, bouncPubKey)
      }
    }
  }

  case class Extension(oid: ASN1ObjectIdentifier, isCritical: Boolean, value: ASN1Encodable)

  case class SignedKey(publicKey: ExtensionPublicKey, signature: BitVector)

  object SignedKey {

    private case class SignedKeyBytes(publicKey: Array[Byte], signature: Array[Byte])

    /**
      *  Identifier defined in libp2p specs which has been, allocated by IANA to the libp2p project at Protocol Labs.
      */
    val extensionIdentifier = "1.3.6.1.4.1.53594.1.1"

    val signedKeyExtensionIdentifier = new ASN1ObjectIdentifier(extensionIdentifier)

    /**
      *  Return the SignedKey extension contained in a byte[] returned by a X509Certificate.getExtensionValue() call.
      */
    private def parseAsn1EncodedBytes(bytes: Array[Byte]): Attempt[SignedKeyBytes] = {
      Attempt.fromTry {
        Try {
          val extensionValue = JcaX509ExtensionUtils
            .parseExtensionValue(bytes)
            .asInstanceOf[DLSequence]
          val hostPublicKey = extensionValue.getObjectAt(0).asInstanceOf[DERBitString].getBytes
          val signature = extensionValue.getObjectAt(1).asInstanceOf[DERBitString].getBytes
          SignedKeyBytes(hostPublicKey, signature)
        }
      }
    }

    /**
      * Encodes Signed key extension as ANS.1-encoded data structure:
      *
      * SignedKey ::= SEQUENCE {
      *    publicKey BIT STRING,
      *    signature BIT STRING
      * }
      *
      */
    private def toASN1Encodable(signedKey: SignedKey): Attempt[ASN1Encodable] = {
      for {
        publicKey <- ExtensionPublicKey.extensionPublicKeyCodec.encode(signedKey.publicKey)
        signature <- scodec.codecs.bits.encode(signedKey.signature)
      } yield {
        val pubKeyAsBitString = new DLBitString(publicKey.toByteArray)
        val sigAsBitString = new DLBitString(signature.toByteArray)
        val encVector = new ASN1EncodableVector(2)
        encVector.add(pubKeyAsBitString)
        encVector.add(sigAsBitString)
        new DLSequence(encVector)
      }
    }

    private def toCertExtension(signedKey: SignedKey): Attempt[Extension] = {
      toASN1Encodable(signedKey).map(
        asEncodable => Extension(signedKeyExtensionIdentifier, isCritical = true, asEncodable)
      )
    }

    def parseAsn1EncodedValue(bytes: Array[Byte]): Attempt[SignedKey] = {
      for {
        signedKeyBytes <- parseAsn1EncodedBytes(bytes)
        pub <- ExtensionPublicKey.extensionPublicKeyCodec.decodeValue(BitVector(signedKeyBytes.publicKey))
        sig <- bits.decodeValue(BitVector(signedKeyBytes.signature))
      } yield SignedKey(pub, sig)
    }

    /**
      *  From libp2p tls spec:
      *  The peer signs the concatenation of the string `libp2p-tls-handshake:` and the public key that it used to generate
      *  the certificate carrying the libp2p Public Key Extension, using its private host key.
      *
      */
    private def buildSignedKey(
        keyType: KeyType,
        hostKeyPair: KeyPair,
        connectionPublicKey: BitVector,
        secureRandom: SecureRandom
    ): Attempt[SignedKey] = {
      val bytesToSign = prefixAsBytes ++ connectionPublicKey
      val signature = BitVector(CryptoUtils.signEcdsa(bytesToSign.toByteArray, hostKeyPair.getPrivate, secureRandom))
      Attempt.fromTry(
        ExtensionPublicKey(keyType, hostKeyPair.getPublic).map(extPublicKey => SignedKey(extPublicKey, signature))
      )
    }

    def buildSignedKeyExtension(
        keyType: KeyType,
        hostKeyPair: KeyPair,
        connectionPublicKey: BitVector,
        secureRandom: SecureRandom
    ): Attempt[(SignedKey, Extension)] =
      for {
        signedKey <- buildSignedKey(keyType, hostKeyPair, connectionPublicKey, secureRandom)
        encoded <- toCertExtension(signedKey)
      } yield (signedKey, encoded)

    def verifySignature(signedKey: SignedKey, certPublicKey: BitVector): Boolean = {
      val bytes = (prefixAsBytes ++ certPublicKey).toByteArray
      CryptoUtils.verifyEcdsa(bytes, signedKey.signature.toByteArray, signedKey.publicKey.encodedPublicKey)
    }

  }

  case class SignedKeyExtensionNodeData(
      calculatedNodeId: BitVector,
      certWithExtension: X509Certificate,
      generatedConnectionKey: KeyPair
  )

  object SignedKeyExtensionNodeData {
    def apply(
        hostKeyType: KeyType,
        hostKeyPair: KeyPair,
        connectionKeyType: SupportedCurves,
        secureRandom: SecureRandom
    ): Try[SignedKeyExtensionNodeData] = {
      // key must be one from 5.1.1. Supported Elliptic Curves Extension rfc4492, we only support subset which is also
      // available in tls 1.3 it will ease up migration in the future
      val connectionKeyPair = CryptoUtils.genTlsSupportedKeyPair(secureRandom, connectionKeyType)

      // safe to call get, as key is generated by us
      val connectionKeyPairPublicKeyAsBytes = CryptoUtils.getEcPublicKey(connectionKeyPair.getPublic).get

      // Certificate will be valid for next 100 years, the same value is used in libp2p go implementation
      val today = LocalDateTime.now(Clock.systemUTC())
      val beforeDate = Date.from(today.minusMonths(1).toInstant(ZoneOffset.UTC))
      val afterDate = Date.from(today.plusYears(100).toInstant(ZoneOffset.UTC))

      SignedKey
        .buildSignedKeyExtension(Secp256k1, hostKeyPair, connectionKeyPairPublicKeyAsBytes, secureRandom)
        .map {
          case (signedKey, signedKeyExtension) =>
            val nodeId = signedKey.publicKey.getNodeId

            val cert =
              CryptoUtils.buildCertificateWithExtensions(
                connectionKeyPair,
                secureRandom,
                List(signedKeyExtension),
                beforeDate,
                afterDate
              )

            new SignedKeyExtensionNodeData(nodeId, cert, connectionKeyPair)
        }
        .toTry
    }
  }

}
