package io.iohk.scalanet.experimental.handlers

import java.net.InetSocketAddress

import monix.eval.Task

case class Envelope[A, M](channel: EChannel[A, M], remoteAddress: A, msg: M)

trait EPeerGroup[A, M] {
  def processAddress: A
  def connect(): Task[Unit]
  def client(to: A): Task[EChannel[A, M]]
  def onConnectionArrival(connectionHandler: EConnection[M] => Unit): Unit
  def onMessageReception(messageHandler: Envelope[A, M] => Unit): Unit
  def shutdown(): Task[Unit]
}

trait EChannel[A, M] {
  def to: A
  def sendMessage(m: M): Task[Unit]
  def close(): Task[Unit]
}

trait EConnection[M] {
  def underlyingAddress: InetSocketAddress
  def replyWith(m: M): Task[Unit]
  def close(): Task[Unit]
}
