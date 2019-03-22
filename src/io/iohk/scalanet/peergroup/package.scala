package io.iohk.scalanet

import scala.collection.mutable

package object peergroup {
  implicit class MapExtension[K, V](map: Map[K, List[V]]) {
    def swap: Map[V, K] = map.flatMap { case (k, vs) => vs.map(v => (v, k)).toMap }
  }
  implicit class MutableMapExtension[K, V](map: mutable.Map[K, List[V]]) {
    def swap: mutable.Map[V, K] = map.flatMap { case (k, vs) => vs.map(v => (v, k)).toMap }
  }
}
