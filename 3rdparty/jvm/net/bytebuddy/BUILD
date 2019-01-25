load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
java_library(
    name = "byte_buddy",
    exports = [
        "//external:jar/net/bytebuddy/byte_buddy"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



java_library(
    name = "byte_buddy_agent",
    exports = [
        "//external:jar/net/bytebuddy/byte_buddy_agent"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)


