load("//scala:rules.bzl", "scala_library", "scala_test")

scala_library(
    name = "telemetry",
    deps = [ ],
    external = [
      "com.typesafe.akka:akka-actor%",
      "com.typesafe.akka:akka-actor-typed%",
      "io.micrometer:micrometer-registry-datadog",
    ]
)

scala_test(
    name = "tests",
    visibility = ["//visibility:public"],
    deps = [ "telemetry" ],
    external = [ "org.scalatest:scalatest%" ],
)
