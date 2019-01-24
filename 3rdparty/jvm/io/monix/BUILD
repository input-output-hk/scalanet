load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
scala_import(
    name = "monix",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        ":monix_eval",
        ":monix_execution",
        ":monix_java",
        ":monix_reactive",
        ":monix_tail"
    ],
    jars = [
        "//external:jar/io/monix/monix_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)



scala_import(
    name = "monix_eval",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        ":monix_execution"
    ],
    jars = [
        "//external:jar/io/monix/monix_eval_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



scala_import(
    name = "monix_execution",
    exports = [
        "//3rdparty/jvm/org/reactivestreams:reactive_streams",
        "//3rdparty/jvm/org/scala_lang:scala_library",
        "//3rdparty/jvm/org/typelevel:cats_effect"
    ],
    jars = [
        "//external:jar/io/monix/monix_execution_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



scala_import(
    name = "monix_java",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library"
    ],
    jars = [
        "//external:jar/io/monix/monix_java_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



scala_import(
    name = "monix_reactive",
    exports = [
        "//3rdparty/jvm/org/jctools:jctools_core",
        "//3rdparty/jvm/org/scala_lang:scala_library",
        ":monix_eval",
        ":monix_execution"
    ],
    jars = [
        "//external:jar/io/monix/monix_reactive_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)



scala_import(
    name = "monix_tail",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        ":monix_eval",
        ":monix_execution"
    ],
    jars = [
        "//external:jar/io/monix/monix_tail_2_12"
    ],
    visibility = [
        "//3rdparty/jvm:__subpackages__"
    ]
)


