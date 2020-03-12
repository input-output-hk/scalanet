package io.iohk.scalanet.peergroup.dynamictls

import java.security.cert.{CertificateException, X509Certificate}
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSExtension.SignedKey
import org.joda.time.DateTime
import scodec.bits.BitVector

import scala.util.{Failure, Success, Try}

object CustomTlsValidator {
  sealed abstract class CertificateError(msg: String) extends CertificateException(msg)
  case object WrongNumberOfCertificates extends CertificateError("Number of certificates not equal 1")
  case object WrongCertificateDate extends CertificateError("Certificate is expired or not yet valid")
  case object NoCertExtension extends CertificateError("Certificate does not have required extension")
  case object WrongExtensionFormat extends CertificateError("Extension has invalid format")
  case object WrongExtensionSignature extends CertificateError("Signature on cert extension is invalid")
  case object ServerIdNotMatchExpected extends CertificateError("Server id do not match expected")

  /**
    *
    * Clients MUST verify that the peer ID derived from the certificate matches the peer ID they
    * intended to connect to, and MUST abort the connection if there is a mismatch.
    *
    */
  private def validateServerId(
      receivedKey: SignedKey,
      expectedPeerInfo: BitVector
  ): Either[ServerIdNotMatchExpected.type, Unit] = {
    Either.cond(receivedKey.publicKey.getNodeId == expectedPeerInfo, (), ServerIdNotMatchExpected)
  }

  /**
    *
    * Endpoints MUST NOT send a certificate chain that contains more than one certificate
    *
    */
  private def validateCertificatesQuantity(
      certificates: Array[X509Certificate]
  ): Either[CertificateError, X509Certificate] = {
    if (certificates.length != 1) {
      Left(WrongNumberOfCertificates)
    } else {
      Right(certificates.head)
    }
  }

  /**
    *
    * The certificate MUST have NotBefore and NotAfter fields set such that the certificate is valid
    * at the time it is received by the peer
    *
    */
  private def validateCertificateDate(cert: X509Certificate): Either[CertificateError, Unit] = {
    Try(cert.checkValidity(DateTime.now().toDate)) match {
      case Failure(_) => Left(WrongCertificateDate)
      case Success(_) => Right(())
    }
  }

  /**
    *
    * Peers MUST verify the signature, and abort the connection attempt if signature verification fails
    *
    */
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
      case Failure(_) | Success(false) => Left(WrongExtensionSignature)
      case Success(true) => Right(())
    }
  }

  /**
    *
    * Performs all validations required by libp2p spec
    *
    */
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

  /**
    *
    * The certificate MUST contain the libp2p Public Key Extension. If this extension is missing,
    * endpoints MUST abort the connection attempt
    *
    */
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
