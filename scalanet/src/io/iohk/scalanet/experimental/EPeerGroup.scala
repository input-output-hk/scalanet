package io.iohk.scalanet.experimental

import monix.eval.Task

trait EPeerGroup[A, M] {
  def processAddress: A
  def connect(): Task[Unit]
  def client(to: A): Task[EClientChannel[A, M]]
  def onReception(handler: Handler[A, M]): Unit
  def shutdown(): Task[Unit]
}

trait EClientChannel[A, M] {
  def to: A
  def sendMessage(m: M): Task[Unit]
  def close(): Task[Unit]
}

trait EConnection[M] {
  def replyWith(m: M): Task[Unit]
  def close(): Task[Unit]
}
