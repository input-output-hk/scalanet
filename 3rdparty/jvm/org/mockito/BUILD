load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
java_library(
    name = "mockito_core",
    exports = [
        "//3rdparty/jvm/net/bytebuddy:byte_buddy",
        "//3rdparty/jvm/net/bytebuddy:byte_buddy_agent",
        "//3rdparty/jvm/org/objenesis:objenesis",
        "//external:jar/org/mockito/mockito_core"
    ],
    visibility = [
        "//visibility:public"
    ]
)


