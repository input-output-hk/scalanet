package io.iohk.scalanet.discovery

import shapeless.tag, tag.@@

/** Helper class to make it easier to tag raw types such as BitVector
  * to specializations so that the compiler can help make sure we are
  * passign the right values to methods.
  *
  * Using it like so:
  *
  * ```
  * trait MyTypeTag
  * object MyType extends Tagger[ByteVector, MyTypeTag]
  * type MyType = MyType.Tagged
  *
  * val myThing = MyType(ByteVector.empty)
  * ```
  *
  */
trait Tagger[U, T] {
  type Tagged = U @@ T
  def apply(underlying: U): Tagged =
    tag[T][U](underlying)
}
