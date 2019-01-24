load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
load("@io_bazel_rules_scala//scala:scala_import.bzl", "scala_import")
scala_import(
    name = "swagger_akka_http",
    exports = [
        "//3rdparty/jvm/com/fasterxml/jackson/dataformat:jackson_dataformat_yaml",
        "//3rdparty/jvm/com/fasterxml/jackson/module:jackson_module_scala",
        "//3rdparty/jvm/com/typesafe/akka:akka_http",
        "//3rdparty/jvm/com/typesafe/akka:akka_stream",
        "//3rdparty/jvm/io/swagger:swagger_annotations",
        "//3rdparty/jvm/io/swagger:swagger_core",
        "//3rdparty/jvm/io/swagger:swagger_jaxrs",
        "//3rdparty/jvm/io/swagger:swagger_models",
        "//3rdparty/jvm/org/scala_lang/modules:scala_java8_compat",
        "//3rdparty/jvm/org/scala_lang:scala_library",
        "//3rdparty/jvm/org/slf4j:slf4j_api"
    ],
    jars = [
        "//external:jar/com/github/swagger_akka_http/swagger_akka_http_2_12"
    ],
    visibility = [
        "//visibility:public"
    ]
)


