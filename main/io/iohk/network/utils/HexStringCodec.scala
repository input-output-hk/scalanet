package io.iohk.network.utils

import akka.util.ByteString
import org.bouncycastle.util.encoders.Hex

object HexStringCodec {

  def toHexString(bs: ByteString): String =
    Hex.toHexString(bs.toArray)

  def toHexString(ba: Array[Byte]): String =
    Hex.toHexString(ba)

  def fromHexString(s: String): ByteString =
    ByteString(Hex.decode(s))
}
