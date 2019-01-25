package io.iohk.network

import io.iohk.network.NodeId.nodeIdBytes
import io.iohk.codecs.nio._
import io.iohk.codecs.nio.auto._
import io.iohk.network.transport.tcp.NetUtils
import io.iohk.network.transport.{Frame, FrameHeader}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalacheck.Gen._
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatest.prop.GeneratorDrivenPropertyChecks._

class FrameCodecSpec extends FlatSpec {

  private val genNodeId: Gen[NodeId] = listOfN(nodeIdBytes, arbitrary[Byte]).map(NodeId(_))

  private val genFrame: Gen[Frame[Int]] = for {
    src <- genNodeId
    dst <- genNodeId
    content <- arbitrary[Int]
  } yield Frame(FrameHeader(src, dst), content)

  private val genFrames: Gen[List[Frame[Int]]] = listOfN(2, genFrame)

  private val codec = NioCodec[Frame[Int]]

  behavior of "FrameEncoder"

  forAll(genFrame) { frame: Frame[Int] =>
    it should s"encode and decode single frame $frame" in {
      codec.decodeStream(codec.encode(frame)) shouldBe Seq(frame)
    }
  }

  forAll(genFrames) { frames: List[Frame[Int]] =>
    it should s"encode and decode multiple frames: $frames" in {
      val buffs = frames.map(codec.encode)
      val bigBuff = NetUtils.concatenate(buffs)
      codec.decodeStream(bigBuff) shouldBe frames
    }
  }
}
