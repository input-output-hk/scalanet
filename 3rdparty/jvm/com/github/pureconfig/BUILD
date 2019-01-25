load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
scala_import(
    name = "pureconfig",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        ":pureconfig_core",
        ":pureconfig_generic"
    ],
    jars = [
        "//external:jar/com/github/pureconfig/pureconfig_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)



scala_import(
    name = "pureconfig_core",
    exports = [
        "//3rdparty/jvm/com/typesafe:config",
        "//3rdparty/jvm/org/scala_lang:scala_library",
        ":pureconfig_macros"
    ],
    jars = [
        "//external:jar/com/github/pureconfig/pureconfig_core_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



scala_import(
    name = "pureconfig_generic",
    exports = [
        "//3rdparty/jvm/com/chuusai:shapeless",
        "//3rdparty/jvm/org/scala_lang:scala_library",
        ":pureconfig_core"
    ],
    jars = [
        "//external:jar/com/github/pureconfig/pureconfig_generic_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



scala_import(
    name = "pureconfig_macros",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library"
    ],
    jars = [
        "//external:jar/com/github/pureconfig/pureconfig_macros_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)


