package io.iohk.scalanet.experimental.observables

import java.net.InetSocketAddress

import monix.eval.Task
import monix.reactive.{Observable, Observer}

case class ObsEnvelope[S, M](channel: ObsChannel[M], sourceId: S, message: M)

trait ObsPeerGroup[A, M] {
  def processAddress: A
  def connect(): Task[Unit]
  def client(to: A): Task[ObsChannel[M]]
  def incomingConnections(): Observable[ObsConnection[M]]
  def onMessageReception(feedTo: Observer[ObsEnvelope[A, M]]): Unit
  def shutdown(): Task[Unit]
}

trait ObsChannel[M] {
  def to: InetSocketAddress
  def sendMessage(m: M): Task[Unit]
  def close(): Task[Unit]
}

trait ObsConnection[M] {
  def from: InetSocketAddress
  // Possibly an `accept` method could fit here better than
  // `sendMessage`. Netty accepts TCP connections automatically.
  // Maybe other libraries don't if we decide to switch the underlying
  // implementation one day
  def sendMessage(m: M): Task[Unit]
  def close(): Task[Unit]
}
