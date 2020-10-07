package io.iohk.scalanet.kademlia.codec

import io.iohk.scalanet.kademlia.KMessage
import io.iohk.scalanet.kademlia.KMessage.KRequest.{FindNodes, Ping}
import io.iohk.scalanet.kademlia.KMessage.KResponse.{Nodes, Pong}
import scodec.codecs.{Discriminated, Discriminator, uint4}

/** Encodings for scodec. */
object DefaultCodecs {
  implicit def kMessageDiscriminator[A]: Discriminated[KMessage[A], Int] =
    Discriminated[KMessage[A], Int](uint4)

  implicit def findNodesDiscriminator[A]: Discriminator[KMessage[A], FindNodes[A], Int] =
    Discriminator[KMessage[A], FindNodes[A], Int](0)

  implicit def pingDiscriminator[A]: Discriminator[KMessage[A], Ping[A], Int] =
    Discriminator[KMessage[A], Ping[A], Int](1)

  implicit def nodesDiscriminator[A]: Discriminator[KMessage[A], Nodes[A], Int] =
    Discriminator[KMessage[A], Nodes[A], Int](2)

  implicit def pongDiscriminator[A]: Discriminator[KMessage[A], Pong[A], Int] =
    Discriminator[KMessage[A], Pong[A], Int](3)

}
