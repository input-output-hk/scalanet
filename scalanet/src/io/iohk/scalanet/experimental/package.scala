package io.iohk.scalanet

import scala.collection.mutable

package object experimental {

  def createSet[T]: mutable.Set[T] = {
    import scala.collection.JavaConverters._
    java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap[T, java.lang.Boolean]).asScala
  }

}
