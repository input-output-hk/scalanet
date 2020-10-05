package io.iohk.scalanet

import monix.eval.Task

package object peergroup {
  // Task that closes a PeerGroup or Channel.
  type Release = Task[Unit]
}
