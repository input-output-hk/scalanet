package io.iohk.scalanet.peergroup.dynamictls

import java.security.cert.{CertificateException, X509Certificate}
import java.time.{Clock, LocalDateTime, ZoneOffset}
import java.util.Date

import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSExtension.SignedKey
import scodec.bits.BitVector

import scala.util.{Failure, Success, Try}

object CustomTlsValidator {
  sealed abstract class CertificateError(msg: String) extends CertificateException(msg)
  case object WrongNumberOfCertificates extends CertificateError("Number of certificates not equal 1")
  case object WrongCertificateDate extends CertificateError("Certificate is expired or not yet valid")
  case object NoCertExtension extends CertificateError("Certificate does not have required extension")
  case object WrongExtensionFormat extends CertificateError("Extension has invalid format")
  case object WrongExtensionSignature extends CertificateError("Signature on cert extension is invalid")
  case object SeverIdNotMatchExpected extends CertificateError("Server id do not match expected")

  private def validateServerId(
      receivedKey: SignedKey,
      expectedPeerInfo: BitVector
  ): Either[SeverIdNotMatchExpected.type, Unit] = {
    Either.cond(receivedKey.publicKey.getNodeId == expectedPeerInfo, (), SeverIdNotMatchExpected)
  }

  private def validateCertificatesQuantity(
      certificates: Array[X509Certificate]
  ): Either[CertificateError, X509Certificate] = {
    if (certificates.length != 1) {
      Left(WrongNumberOfCertificates)
    } else {
      Right(certificates.head)
    }
  }

  private def validateCertificateDate(cert: X509Certificate): Either[CertificateError, Unit] = {
    val todayUtcDate = Date.from(LocalDateTime.now(Clock.systemUTC()).toInstant(ZoneOffset.UTC))
    Try(cert.checkValidity(todayUtcDate)) match {
      case Failure(_) => Left(WrongCertificateDate)
      case Success(_) => Right(())
    }
  }

  private def validateCertificateSignature(
      signedKey: SignedKey,
      cert: X509Certificate
  ): Either[CertificateError, Unit] = {
    val certificatePublicKey = cert.getPublicKey
    (for {
      // Certificate keys have different implementation (sun), to use our other machinery it need to be converted to Bouncy
      // castle format
      pubKeyInBcFormat <- CryptoUtils.getBouncyCastlePubKey(
        certificatePublicKey.getEncoded,
        certificatePublicKey.getAlgorithm
      )
      keyBytes <- CryptoUtils.getEcPublicKey(pubKeyInBcFormat)
      result <- Try(SignedKey.verifySignature(signedKey, keyBytes))
    } yield result) match {
      case Failure(_) => Left(WrongExtensionSignature)
      case Success(value) =>
        if (value)
          Right(())
        else
          Left(WrongExtensionSignature)
    }
  }

  def validateCertificates(
      certificates: Array[X509Certificate],
      connectingTo: Option[BitVector]
  ): Either[CertificateError, SignedKey] = {
    for {
      cert <- validateCertificatesQuantity(certificates)
      _ <- validateCertificateDate(cert)
      extension <- validateCertificateExtension(cert, SignedKey.extensionIdentifier)
      signedKeyExtension <- validateExtension(extension)
      _ <- validateCertificateSignature(signedKeyExtension, cert)
      _ <- if (connectingTo.isDefined) validateServerId(signedKeyExtension, connectingTo.get) else Right(())
    } yield {
      signedKeyExtension
    }
  }

  private def validateCertificateExtension(
      cert: X509Certificate,
      extensionId: String
  ): Either[CertificateError, Array[Byte]] = {
    val extension = cert.getExtensionValue(extensionId)
    if (extension == null) {
      Left(NoCertExtension)
    } else {
      Right(extension)
    }
  }

  private def validateExtension(extension: Array[Byte]): Either[CertificateError, SignedKey] = {
    SignedKey.parseAsn1EncodedValue(extension).toEither.left.map(_ => WrongExtensionFormat)
  }
}
