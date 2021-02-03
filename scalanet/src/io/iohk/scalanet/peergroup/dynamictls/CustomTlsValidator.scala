package io.iohk.scalanet.peergroup.dynamictls

import java.security.cert.{CertificateException, X509Certificate}
import io.iohk.scalanet.crypto.CryptoUtils
import io.iohk.scalanet.peergroup.dynamictls.DynamicTLSExtension.SignedKey
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X962Parameters
import org.joda.time.DateTime
import scodec.bits.BitVector
import java.security.interfaces.ECPublicKey
import scala.util.{Failure, Success, Try}

private[scalanet] object CustomTlsValidator {
  sealed abstract class CertificateError(msg: String) extends CertificateException(msg)
  case object WrongNumberOfCertificates extends CertificateError("Number of certificates not equal 1")
  case object WrongCertificateDate extends CertificateError("Certificate is expired or not yet valid")
  case object NoCertExtension extends CertificateError("Certificate does not have required extension")
  case object WrongExtensionFormat extends CertificateError("Extension has invalid format")
  case object WrongExtensionSignature extends CertificateError("Signature on cert extension is invalid")
  case object ServerIdNotMatchExpected extends CertificateError("Server id do not match expected")
  case object WrongCertificateSelfSignature extends CertificateError("Certificate has wrong self-signature")
  case object NotKnownCriticalExtensions extends CertificateError("Certificate contains not known critical extensions")
  case object WrongCertificateKeyFormat
      extends CertificateError("Certificate key should be EC key in NamedCurve Format")
  case object WrongCertificateSignatureScheme extends CertificateError("Wrong signature scheme")

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
    Either.cond(certificates.length == 1, certificates.head, WrongNumberOfCertificates)
  }

  /**
    *
    * Endpoints MUST abort the connection attempt if more than one certificate is received,
    * or if the certificate’s self-signature is not valid.
    *
    */
  private def validateCertificateSelfSig(cert: X509Certificate): Either[CertificateError, Unit] = {
    Try(cert.verify(cert.getPublicKey)) match {
      case Failure(_) => Left(WrongCertificateSelfSignature)
      case Success(_) => Right(())
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
    * Similarly, hash functions with an output length less than 256 bits MUST NOT be used,
    * due to the possibility of collision attacks. In particular, MD5 and SHA1 MUST NOT be used
    *
    * Endpoints MUST choose a key that will allow the peer to verify the certificate
    * and SHOULD use a key type that (a) allows for efficient signature computation,
    * and (b) reduces the combined size of the certificate and the signature.
    * In particular, RSA SHOULD NOT be used unless no elliptic curve algorithms are supported
    *
    * Taking both into account we force using "SHA256withECDSA"
    */
  private def validateCertificateSignatureScheme(cert: X509Certificate): Either[CertificateError, Unit] = {
    val sigAlgName = cert.getSigAlgName
    Either.cond(sigAlgName.equalsIgnoreCase(CryptoUtils.SHA256withECDSA.name), (), WrongCertificateSignatureScheme)
  }

  /**
    *
    * Peers MUST verify the signature, and abort the connection attempt if signature verification fails
    *
    */
  private def validateCertificateSignature(
      signedKey: SignedKey,
      certPublicKey: ECPublicKey
  ): Either[CertificateError, Unit] = {
    (for {
      // Certificate keys have different implementation (sun), to use our other machinery it need to be converted to Bouncy
      // castle format
      pubKeyInBcFormat <- CryptoUtils.getBouncyCastlePubKey(
        certPublicKey.getEncoded,
        certPublicKey.getAlgorithm
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
    * Certificates MUST use the NamedCurve encoding for elliptic curve parameters.
    * Endpoints MUST abort the connection attempt if is not used.
    *
    */
  def validateCertificatePublicKey(cert: X509Certificate): Either[CertificateError, ECPublicKey] = {
    val certificatePublicKey = cert.getPublicKey
    Try {
      val ecPublicKey = certificatePublicKey.asInstanceOf[ECPublicKey]
      val subjectKeyInfo = SubjectPublicKeyInfo.getInstance(ASN1Primitive.fromByteArray(ecPublicKey.getEncoded))
      val x962Params = X962Parameters.getInstance(subjectKeyInfo.getAlgorithm.getParameters)
      (ecPublicKey, x962Params)
    } match {
      case Success((key, parameters)) if parameters.isNamedCurve => Right(key)
      case _ => Left(WrongCertificateKeyFormat)
    }
  }

  /**
    *
    * The certificate MUST contain the libp2p Public Key Extension. If this extension is missing,
    * endpoints MUST abort the connection attempt
    *
    */
  private def getCertificateExtension(
      cert: X509Certificate,
      extensionId: String
  ): Either[CertificateError, Array[Byte]] = {
    Option(cert.getExtensionValue(extensionId)).toRight(NoCertExtension)
  }

  /**
    *
    * Endpoints MUST abort the connection attempt if the certificate contains critical extensions
    * that the endpoint does not understand.
    */
  def validateOnlyKnownCriticalExtensions(cert: X509Certificate): Either[CertificateError, Unit] = {
    val criticalExtensions = cert.getCriticalExtensionOIDs

    val containsOnlyOneKnownCriticalExtension =
      criticalExtensions == null || criticalExtensions.size() == 0 ||
        (criticalExtensions.size() == 1 && criticalExtensions.contains(SignedKey.extensionIdentifier))

    Either.cond(containsOnlyOneKnownCriticalExtension, (), NotKnownCriticalExtensions)
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
      _ <- validateCertificateSignatureScheme(cert)
      _ <- validateCertificateSelfSig(cert)
      _ <- validateCertificateDate(cert)
      validPublicKey <- validateCertificatePublicKey(cert)
      _ <- validateOnlyKnownCriticalExtensions(cert)
      signedKeyExtension <- getCertificateExtension(cert, SignedKey.extensionIdentifier)
        .flatMap(validateSignedKeyExtension)
      _ <- validateCertificateSignature(signedKeyExtension, validPublicKey)
      _ <- if (connectingTo.isDefined) validateServerId(signedKeyExtension, connectingTo.get) else Right(())
    } yield {
      signedKeyExtension
    }
  }

  private def validateSignedKeyExtension(extension: Array[Byte]): Either[CertificateError, SignedKey] = {
    SignedKey.parseAsn1EncodedValue(extension).toEither.left.map(_ => WrongExtensionFormat)
  }
}
