load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
java_library(
    name = "commons_dbcp2",
    exports = [
        "//3rdparty/jvm/commons_logging:commons_logging",
        "//external:jar/org/apache/commons/commons_dbcp2",
        ":commons_pool2"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



java_library(
    name = "commons_lang3",
    exports = [
        "//external:jar/org/apache/commons/commons_lang3"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



java_library(
    name = "commons_pool2",
    exports = [
        "//external:jar/org/apache/commons/commons_pool2"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)


