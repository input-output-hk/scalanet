load("//scala:rules.bzl", "scala_library", "scala_test")

scala_library(
    name = "scalanet",
    external = [
        "io.monix:monix%",
        "io.netty:netty-all",
        "org.typelevel:cats-core%",
        "com.chuusai:shapeless%",
    ],
    deps = ["@decco//src/io/iohk/decco"],
)

scala_test(
    name = "tests",
    external = [
        "org.scalatest:scalatest%",
        "org.mockito:mockito-core",
        "org.scalacheck:scalacheck%",
        "com.chuusai:shapeless%",
    ],
    deps = [
        "scalanet",
        "@decco//src/io/iohk/decco",
    ],
)
