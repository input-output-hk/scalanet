package io.iohk.network.test.utils

import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, TestSuite}

trait StopAfterAll extends BeforeAndAfterAll {
  this: TestKit with TestSuite =>

  override protected def afterAll(): Unit = {
    super.afterAll()
    system.terminate()
  }
}
