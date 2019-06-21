package io.iohk.scalanet.codec

import java.nio.ByteBuffer

import io.iohk.decco.BufferInstantiator.global.HeapByteBuffer
import io.iohk.scalanet.TaskValues._
import io.iohk.scalanet.codec.CodecTestUtils.{generateMessage, split, subset}
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.scalatest.FlatSpec
import org.scalatest.Matchers._

class MonixStreamCodecsSpec extends FlatSpec {

  behavior of "MonixStreamCodecs"

  it should "handle a message split over several packets" in {

    import MonixStreamCodecs._

    val sourceMessage = generateMessage(12)

    val packets: Seq[ByteBuffer] = split(sourceMessage, 4)

    val sourceObservable = Observable.fromIterable(packets)

    val sourceObservableFramed = sourceObservable.framed

    sourceObservableFramed.headL.evaluated shouldBe subset(4, 16, sourceMessage)
  }

}
