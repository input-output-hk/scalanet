load("//scala:rules.bzl", "scala_library", "scala_test")

scala_library(
    name = "scalanet",
    deps = [
        "//3rdparty/jvm/io/monix",
        "//3rdparty/jvm/io/netty:netty_all",
    ]
)

scala_test(
    name = "tests",
    visibility = ["//visibility:public"],
    deps = [
        "scalanet",
        "//3rdparty/jvm/io/monix",
        "//3rdparty/jvm/org/scalacheck",
        "//3rdparty/jvm/org/scalatest"]
)
