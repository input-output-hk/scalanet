load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
scala_import(
    name = "playsonify_akka_http",
    exports = [
        "//3rdparty/jvm/com/typesafe/akka:akka_http",
        "//3rdparty/jvm/com/typesafe/akka:akka_stream",
        "//3rdparty/jvm/com/typesafe/play:play_json",
        "//3rdparty/jvm/de/heikoseeberger:akka_http_play_json",
        "//3rdparty/jvm/org/scalactic:scalactic",
        ":playsonify_core"
    ],
    jars = [
        "//external:jar/com/alexitc/playsonify_akka_http_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)



scala_import(
    name = "playsonify_core",
    exports = [
        "//3rdparty/jvm/org/scalactic:scalactic"
    ],
    jars = [
        "//external:jar/com/alexitc/playsonify_core_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)


