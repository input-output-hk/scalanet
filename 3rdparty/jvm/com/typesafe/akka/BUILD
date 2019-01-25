load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
scala_import(
    name = "akka_actor",
    exports = [
        "//3rdparty/jvm/com/typesafe:config",
        "//3rdparty/jvm/org/scala_lang/modules:scala_java8_compat",
        "//3rdparty/jvm/org/scala_lang:scala_library"
    ],
    jars = [
        "//external:jar/com/typesafe/akka/akka_actor_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)



scala_import(
    name = "akka_actor_typed",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        ":akka_actor"
    ],
    jars = [
        "//external:jar/com/typesafe/akka/akka_actor_typed_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)



scala_import(
    name = "akka_slf4j",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        "//3rdparty/jvm/org/slf4j:slf4j_api",
        ":akka_actor"
    ],
    jars = [
        "//external:jar/com/typesafe/akka/akka_slf4j_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)



scala_import(
    name = "akka_testkit",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        ":akka_actor"
    ],
    jars = [
        "//external:jar/com/typesafe/akka/akka_testkit_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)



scala_import(
    name = "akka_testkit_typed",
    exports = [
        "//3rdparty/jvm/org/scala_lang:scala_library",
        ":akka_actor_typed",
        ":akka_testkit"
    ],
    jars = [
        "//external:jar/com/typesafe/akka/akka_testkit_typed_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)


