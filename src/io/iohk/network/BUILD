load("//scala:rules.bzl", "scala_library", "scala_test")

scala_library(
    name = "network",
    deps = [
      "//src/io/iohk/network/telemetry",
      "@codecs//src/io/iohk/codecs",
      "@crypto//src/io/iohk/crypto"
    ],
    external = [
      "com.typesafe.akka:akka-actor%",
      "com.typesafe.akka:akka-actor-typed%",
      "io.monix:monix%",
      "org.bouncycastle:bcprov-jdk15on",
      "io.netty:netty-all",
    ]
)

scala_test(
    name = "tests",
    visibility = ["//visibility:public"],
    deps = [
        "network",
        "//src/io/iohk/network/telemetry",
        "@codecs//src/io/iohk/codecs",
        "@crypto//src/io/iohk/crypto"
    ],
    external = [
      "com.softwaremill.quicklens:quicklens%",
      "com.typesafe.akka:akka-slf4j%",
      "com.typesafe.akka:akka-testkit%",
      "com.typesafe.akka:akka-actor-testkit-typed%",
      "org.mockito:mockito-core",
      "org.scalacheck:scalacheck%",
      "org.scalatest:scalatest%",
      "javax.xml.bind:jaxb-api",
      "com.sun.xml.bind:jaxb-core",
      "com.sun.xml.bind:jaxb-impl",
      "javax.activation:activation",
    ],
)
