package io.iohk.scalanet.discovery.hash

import org.scalatest._
import scodec.bits._

class Keccak256Spec extends FlatSpec with Matchers {
  behavior of "Keccak256"

  it should "hash empty data" in {
    Keccak256(BitVector("".getBytes)).toByteVector shouldBe hex"c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"
  }

  it should "hash non-empty data" in {
    Keccak256(BitVector("abc".getBytes)).toByteVector shouldBe hex"4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45"
  }
}
