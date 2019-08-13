package io.iohk.scalanet.experimental

import java.net.InetSocketAddress

import monix.eval.Task

trait EPeerGroup[A, M] {
  def processAddress: A
  def connect(): Task[Unit]
  def client(to: A): Task[EClientChannel[A, M]]
  def onConnectionArrival(connectionHandler: EConnection[M] => Unit): Unit
  def onMessageReception(messageHandler: Envelope[A, M] => Unit): Unit
  def shutdown(): Task[Unit]
}

trait EClientChannel[A, M] {
  def to: A
  def sendMessage(m: M): Task[Unit]
  def close(): Task[Unit]
}

trait EConnection[M] {
  def underlyingAddress: InetSocketAddress
  def replyWith(m: M): Task[Unit]
  def close(): Task[Unit]
}
