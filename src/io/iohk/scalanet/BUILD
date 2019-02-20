load("//scala:rules.bzl", "scala_library", "scala_test")

scala_library(
    name = "scalanet",
    external = ["io.monix:monix%", "io.netty:netty-all", "org.typelevel:cats-core%"]
)

scala_test(
    name = "tests",
    deps = [ "scalanet" ],
    external = ["org.scalatest:scalatest%"]
)
