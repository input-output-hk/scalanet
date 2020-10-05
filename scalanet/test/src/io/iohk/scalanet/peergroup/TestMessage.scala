package io.iohk.scalanet.peergroup

import scodec.codecs.{Discriminated, Discriminator, uint4}

// A message we can use in tests with a format that's definitely not ambiguous with the encoding of a String.
sealed trait TestMessage[A]
object TestMessage {
  case class Foo[A](value: A) extends TestMessage[A]
  case class Bar[A](value: A) extends TestMessage[A]

  implicit def testMessageDiscriminator[A]: Discriminated[TestMessage[A], Int] =
    Discriminated[TestMessage[A], Int](uint4)

  implicit def fooDiscriminator[A]: Discriminator[TestMessage[A], Foo[A], Int] =
    Discriminator[TestMessage[A], Foo[A], Int](0)

  implicit def barDiscriminator[A]: Discriminator[TestMessage[A], Bar[A], Int] =
    Discriminator[TestMessage[A], Bar[A], Int](1)
}
