package io.iohk.scalanet.peergroup

import monix.eval.Task
import monix.execution.Scheduler

import scala.language.higherKinds

object PeerUtils {

  def apply[A, M, PG[_, _]](implicit tu: PeerUtils[A, M, PG]): PeerUtils[A, M, PG] = tu

  def instance[A, M, PG[_, _]](
      isLis: PG[A, M] => Boolean,
      generateRandomPG: () => PG[A, M],
      shutd: PG[A, M] => Task[Unit],
      init: PG[A, M] => Task[Unit]
  ): PeerUtils[A, M, PG] = new PeerUtils[A, M, PG] {
    override def isListening(peer: PG[A, M]): Boolean = isLis(peer)
    override def generateRandomPeerGroup(): PG[A, M] = generateRandomPG()
    override def shutdown(peer: PG[A, M]): Task[Unit] = shutd(peer)
    override def initialize(peer: PG[A, M]): Task[Unit] = init(peer)
  }
}

trait PeerUtils[A, M, PG[_, _]] {
  def isListening(peer: PG[A, M]): Boolean
  def generateRandomPeerGroup(): PG[A, M]
  def shutdown(peer: PG[A, M]): Task[Unit]
  def initialize(peer: PG[A, M]): Task[Unit]

  def withTwoRandomPeerGroups(
      testFunction: (PG[A, M], PG[A, M]) => Any
  )(implicit sc: Scheduler): Unit = {
    val alice = generateRandomPeerGroup()
    val bob = generateRandomPeerGroup()
    initialize(alice).runToFuture
    initialize(bob).runToFuture
    try {
      testFunction(alice, bob)
      ()
    } finally {
      shutdown(alice).runToFuture
      shutdown(bob).runToFuture
      ()
    }
  }
}
