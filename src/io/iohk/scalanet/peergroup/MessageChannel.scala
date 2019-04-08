package io.iohk.scalanet.peergroup

import java.nio.ByteBuffer

import io.iohk.decco.Codec
import io.iohk.decco.PartialCodec.{DecodeResult, Failure}
import monix.reactive.Observable
import org.slf4j.LoggerFactory

private[scalanet] class MessageChannel[A, M](peerGroup: PeerGroup[A, M])(
    implicit codec: Codec[M]
) {

  private val log = LoggerFactory.getLogger(getClass)

  private val subscribers = new Subscribers[(A, M)]()

  val inboundMessages: Observable[(A, M)] = subscribers.messageStream

  private[peergroup] def handleMessage(address: A)(nextIndex: Int, byteBuffer: ByteBuffer): Unit = {
    val messageE: Either[Failure, DecodeResult[M]] = codec.partialCodec.decode(nextIndex, byteBuffer)
    messageE match {
      case Left(Failure) =>
        log.info(
          s"Decode failed in typed channel for message from '$address' to '${peerGroup.processAddress}' " +
            s"using codec '${codec.typeCode}'"
        )
      case Right(decodeResult) =>
        log.debug(
          s"Successful decode in typed channel for message from '$address' to '${peerGroup.processAddress}' " +
            s"using codec '${codec.typeCode}'. Notifying subscribers."
        )
        subscribers.notify(address -> decodeResult.decoded)
    }
  }
}
