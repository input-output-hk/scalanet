load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
java_library(
    name = "reflections",
    exports = [
        "//3rdparty/jvm/com/google/guava:guava",
        "//3rdparty/jvm/org/javassist:javassist",
        "//external:jar/org/reflections/reflections"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)


