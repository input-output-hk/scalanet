package io.iohk.network

//How many ledgers do we need to support?
case class Capabilities(byte: Byte) {

  /**
    * Tells if this object satisfies the other object's capabilities. In other words, it has at least the other object's
    * capabilities
    */
  def satisfies(other: Capabilities): Boolean = {
    (byte & other.byte) == other.byte
  }
}
