load("//scala:rules.bzl", "scala_library", "scala_test")

scala_library(
    name = "scalanet",
    deps = [
        "//3rdparty/jvm/io/monix",
        "//3rdparty/jvm/io/netty:netty_all",
        "//3rdparty/jvm/org/typelevel:cats_core",
    ],
)

scala_test(
    name = "tests",
    deps = [
        "scalanet",
        "//3rdparty/jvm/io/monix",
        "//3rdparty/jvm/org/scalatest",
    ],
)
