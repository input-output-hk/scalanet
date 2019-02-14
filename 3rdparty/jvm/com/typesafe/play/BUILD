load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
scala_import(
    name = "play_functional",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library"
    ],
    jars = [
        "//external:jar/com/typesafe/play/play_functional_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



scala_import(
    name = "play_json",
    exports = [
        "//3rdparty/jvm/com/fasterxml/jackson/core:jackson_annotations",
        "//3rdparty/jvm/com/fasterxml/jackson/core:jackson_core",
        "//3rdparty/jvm/com/fasterxml/jackson/core:jackson_databind",
        "//3rdparty/jvm/com/fasterxml/jackson/datatype:jackson_datatype_jdk8",
        "//3rdparty/jvm/com/fasterxml/jackson/datatype:jackson_datatype_jsr310",
        "//3rdparty/jvm/joda_time:joda_time",
        "//3rdparty/jvm/org/scala_lang:scala_library",
        "//3rdparty/jvm/org/scala_lang:scala_reflect",
        "//3rdparty/jvm/org/typelevel:macro_compat",
        ":play_functional"
    ],
    jars = [
        "//external:jar/com/typesafe/play/play_json_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)


