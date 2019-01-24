load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
scala_import(
    name = "scalikejdbc",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        ":scalikejdbc_core",
        ":scalikejdbc_interpolation"
    ],
    jars = [
        "//external:jar/org/scalikejdbc/scalikejdbc_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)



scala_import(
    name = "scalikejdbc_config",
    exports = [
        "//3rdparty/jvm/com/typesafe:config",
        "//3rdparty/jvm/org/scala_lang:scala_library",
        "//3rdparty/jvm/org/slf4j:slf4j_api",
        ":scalikejdbc_core"
    ],
    jars = [
        "//external:jar/org/scalikejdbc/scalikejdbc_config_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)



scala_import(
    name = "scalikejdbc_core",
    exports = [
        "//3rdparty/jvm/org/apache/commons:commons_dbcp2",
        "//3rdparty/jvm/org/scala_lang/modules:scala_parser_combinators",
        "//3rdparty/jvm/org/scala_lang:scala_library",
        "//3rdparty/jvm/org/slf4j:slf4j_api"
    ],
    jars = [
        "//external:jar/org/scalikejdbc/scalikejdbc_core_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



scala_import(
    name = "scalikejdbc_interpolation",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        "//3rdparty/jvm/org/slf4j:slf4j_api",
        ":scalikejdbc_core",
        ":scalikejdbc_interpolation_macro"
    ],
    jars = [
        "//external:jar/org/scalikejdbc/scalikejdbc_interpolation_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



scala_import(
    name = "scalikejdbc_interpolation_macro",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        "//3rdparty/jvm/org/scala_lang:scala_reflect",
        ":scalikejdbc_core"
    ],
    jars = [
        "//external:jar/org/scalikejdbc/scalikejdbc_interpolation_macro_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



scala_import(
    name = "scalikejdbc_test",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        "//3rdparty/jvm/org/slf4j:slf4j_api",
        ":scalikejdbc"
    ],
    jars = [
        "//external:jar/org/scalikejdbc/scalikejdbc_test_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)


