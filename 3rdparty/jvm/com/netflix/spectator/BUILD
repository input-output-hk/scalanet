load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
java_library(
    name = "spectator_api",
    exports = [
        "//3rdparty/jvm/org/slf4j:slf4j_api",
        "//external:jar/com/netflix/spectator/spectator_api"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)


