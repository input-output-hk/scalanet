package io.iohk.scalanet.discovery.ethereum.v4

import org.scalatest._

class PacketSpec extends FlatSpec {
  behavior of "encode"
  it should "fail if data exceeds the maximum size" in (pending)
  it should "fail if the hash has wrong size" in (pending)
  it should "fail if the signature has wrong size" in (pending)

  behavior of "decode"
  it should "fail if the data exceeds the maximum size" in (pending)
  it should "fail if there's less data than the hash size" in (pending)
  it should "fail if there's less data than the signature size" in (pending)

  behavior of "unpack"
  it should "fail if the hash is incorrect" in (pending)
  it should "fail if the signature is incorrect" in (pending)

  behavior of "pack"
  it should "calculate the signature based on the data" in (pending)
  it should "calculate the hash based on the signature and the data" in (pending)
}
