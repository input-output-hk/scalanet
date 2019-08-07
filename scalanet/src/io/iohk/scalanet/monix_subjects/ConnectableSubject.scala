package io.iohk.scalanet.monix_subjects

import monix.execution.Cancelable
import monix.reactive.subjects.Subject

abstract class ConnectableSubject[T] extends Subject[T, T] {
  def connect(): Cancelable
}
