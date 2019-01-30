load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
java_library(
    name = "micrometer_core",
    exports = [
        "//external:jar/io/micrometer/micrometer_core"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



java_library(
    name = "micrometer_registry_datadog",
    exports = [
        "//3rdparty/jvm/com/netflix/spectator:spectator_api",
        "//external:jar/io/micrometer/micrometer_registry_datadog",
        ":micrometer_core"
    ],
    visibility = [
        "//visibility:public"
    ]
)


