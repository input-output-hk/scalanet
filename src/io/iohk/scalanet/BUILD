load("//scala:rules.bzl", "scala_library", "scala_test")

scala_library(
    name = "scalanet",
    deps = [
        "//3rdparty/jvm/io/monix",
        "//3rdparty/jvm/io/netty:netty_all",
        "//3rdparty/jvm/org/typelevel:cats_core",
         "//3rdparty/jvm/com/typesafe/play:play_json",
         "@codecs//src/io/iohk/decco",
         "//3rdparty/jvm/com/chuusai:shapeless"

    ],
)

scala_test(
    name = "tests",
    deps = [
        "scalanet",
        "//3rdparty/jvm/org/mockito:mockito_core",
        "//3rdparty/jvm/io/monix",
        "//3rdparty/jvm/org/scalatest",
        "//3rdparty/jvm/com/typesafe/play:play_json",
        "//3rdparty/jvm/org/scalacheck",
        "@codecs//src/io/iohk/decco"
    ],
)
