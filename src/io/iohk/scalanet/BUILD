load("//scala:rules.bzl", "scala_library", "scala_test")

scala_library(
    name = "scalanet",
    external = [
        "io.monix:monix%",
        "io.netty:netty-all",
        "com.chuusai:shapeless%",
        "org.slf4j:slf4j-api",
        "org.eclipse.californium:element-connector",
        "org.eclipse.californium:scandium"
    ],
    deps = ["@decco//src/io/iohk/decco",
            "@decco//src/io/iohk/decco/auto",],
)

scala_test(
    name = "tests",
    external = [
        "org.scalatest:scalatest%",
        "org.mockito:mockito-core",
        "org.scalacheck:scalacheck%",
        "com.chuusai:shapeless%",
        "ch.qos.logback:logback-classic",
    ],
    deps = [
        "scalanet",
        "@decco//src/io/iohk/decco",
        "@decco//src/io/iohk/decco/auto",
    ],
)
