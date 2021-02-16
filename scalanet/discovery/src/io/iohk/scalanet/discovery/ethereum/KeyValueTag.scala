package io.iohk.scalanet.discovery.ethereum

import cats.implicits._
import java.nio.charset.StandardCharsets.UTF_8
import scodec.bits.ByteVector
import scala.util.{Try, Success, Failure}

/** Key value pairs that get added to the local ENR record as well as used
  * as a critera for accepting remote ENRs.
  */
trait KeyValueTag {
  def toAttr: (ByteVector, ByteVector)
  def toFilter: KeyValueTag.EnrFilter
}

object KeyValueTag {
  type EnrFilter = EthereumNodeRecord => Either[String, Unit]

  def toFilter(tags: List[KeyValueTag]): EnrFilter = {
    val filters = tags.map(_.toFilter)
    enr => filters.traverse(_(enr)).void
  }

  class StringEquals(key: String, value: String) extends KeyValueTag {
    private val keyBytes =
      EthereumNodeRecord.Keys.key(key)

    private val valueBytes =
      ByteVector(value.getBytes(UTF_8))

    override val toAttr =
      (keyBytes, valueBytes)

    override val toFilter = enr =>
      enr.content.attrs.get(keyBytes) match {
        case Some(`valueBytes`) =>
          Right(())

        case Some(other) =>
          Try(new String(other.toArray, UTF_8)) match {
            case Success(otherValue) =>
              Left(s"$key mismatch; $otherValue != $value")

            case Failure(_) =>
              Left(s"$key mismatch; $other != $valueBytes")
          }

        case None =>
          Left(s"$key is missing; expected $value")
      }
  }

  object NetworkId {
    def apply(networkId: String) =
      new StringEquals("network-id", networkId)
  }
}
