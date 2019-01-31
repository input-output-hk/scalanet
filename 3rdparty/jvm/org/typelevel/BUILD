load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")

scala_import(
    name = "cats_core",
    jars = [
        "//external:jar/org/typelevel/cats_core_2_12",
    ],
    visibility = [
        "//visibility:public",
    ],
    exports = [
        ":cats_kernel",
        ":cats_macros",
        ":machinist",
        "//3rdparty/jvm/org/scala_lang:scala_library",
    ],
)

scala_import(
    name = "cats_effect",
    jars = [
        "//external:jar/org/typelevel/cats_effect_2_12",
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        ":cats_core",
        "//3rdparty/jvm/org/scala_lang:scala_library",
    ],
)

scala_import(
    name = "cats_kernel",
    jars = [
        "//external:jar/org/typelevel/cats_kernel_2_12",
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
    ],
)

scala_import(
    name = "cats_macros",
    jars = [
        "//external:jar/org/typelevel/cats_macros_2_12",
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        ":machinist",
        "//3rdparty/jvm/org/scala_lang:scala_library",
    ],
)

scala_import(
    name = "machinist",
    jars = [
        "//external:jar/org/typelevel/machinist_2_12",
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        "//3rdparty/jvm/org/scala_lang:scala_reflect",
    ],
)

scala_import(
    name = "macro_compat",
    jars = [
        "//external:jar/org/typelevel/macro_compat_2_12",
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__",
    ],
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
    ],
)
