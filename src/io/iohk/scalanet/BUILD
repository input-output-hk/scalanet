load("//scala:rules.bzl", "scala_library", "scala_test")

scala_library(
    name = "scalanet",
    deps = [
        "//3rdparty/jvm/com/chuusai:shapeless",
        "//3rdparty/jvm/com/typesafe/play:play_json",
        "//3rdparty/jvm/io/monix",
        "//3rdparty/jvm/io/netty:netty_all",
        "//3rdparty/jvm/org/typelevel:cats_core",
        "@decco//src/io/iohk/decco",
    ],
)

scala_test(
    name = "tests",
    deps = [
        "scalanet",
        "//3rdparty/jvm/com/typesafe/play:play_json",
        "//3rdparty/jvm/io/monix",
        "//3rdparty/jvm/org/mockito:mockito_core",
        "//3rdparty/jvm/org/scalacheck",
        "//3rdparty/jvm/org/scalatest",
        "@decco//src/io/iohk/decco",
    ],
)
