load("//scala:rules.bzl", "scala_library", "scala_test")

scala_library(
    name = "telemetry",
    deps = [
        "//3rdparty/jvm/com/typesafe/akka:akka_actor",
        "//3rdparty/jvm/com/typesafe/akka:akka_actor_typed",
        "//3rdparty/jvm/io/micrometer:micrometer_registry_datadog"
    ],
)

scala_test(
    name = "tests",
    visibility = ["//visibility:public"],
    deps = [
        "telemetry",
        "//3rdparty/jvm/io/micrometer:micrometer_registry_datadog",
        "//3rdparty/jvm/org/scalatest"
    ],
)
