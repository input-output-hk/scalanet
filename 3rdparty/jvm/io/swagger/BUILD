load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
java_library(
    name = "swagger_annotations",
    exports = [
        "//external:jar/io/swagger/swagger_annotations"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



java_library(
    name = "swagger_core",
    exports = [
        "//3rdparty/jvm/com/fasterxml/jackson/core:jackson_annotations",
        "//3rdparty/jvm/com/fasterxml/jackson/core:jackson_databind",
        "//3rdparty/jvm/com/fasterxml/jackson/dataformat:jackson_dataformat_yaml",
        "//3rdparty/jvm/com/google/guava:guava",
        "//3rdparty/jvm/javax/validation:validation_api",
        "//3rdparty/jvm/org/apache/commons:commons_lang3",
        "//3rdparty/jvm/org/slf4j:slf4j_api",
        "//external:jar/io/swagger/swagger_core",
        ":swagger_models"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



java_library(
    name = "swagger_jaxrs",
    exports = [
        "//3rdparty/jvm/com/fasterxml/jackson/core:jackson_databind",
        "//3rdparty/jvm/com/google/guava:guava",
        "//3rdparty/jvm/javax/ws/rs:jsr311_api",
        "//3rdparty/jvm/org/reflections:reflections",
        "//external:jar/io/swagger/swagger_jaxrs",
        ":swagger_core"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



java_library(
    name = "swagger_models",
    exports = [
        "//3rdparty/jvm/com/fasterxml/jackson/core:jackson_annotations",
        "//3rdparty/jvm/org/slf4j:slf4j_api",
        "//external:jar/io/swagger/swagger_models",
        ":swagger_annotations"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)


