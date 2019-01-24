load("//scala:rules.bzl", "scala_library", "scala_test")

scala_library(
    name = "network",
    deps = [
        "//3rdparty/jvm/com/typesafe/akka:akka_actor",
        "//3rdparty/jvm/com/typesafe/akka:akka_actor_typed",
        "//3rdparty/jvm/io/micrometer:micrometer_registry_datadog",
        "//3rdparty/jvm/io/monix",
        "//3rdparty/jvm/org/bouncycastle:bcprov_jdk15on",
        "//3rdparty/jvm/io/netty:netty_all",
        "//main/io/iohk/network/telemetry",
        "@codecs//main/io/iohk/codecs",
        "@crypto//main/io/iohk/crypto",
        #"//main/io/iohk/cef/error",
        #"//main/io/iohk/cef/utils",
    ],
)

scala_test(
    name = "tests",
    visibility = ["//visibility:public"],
    deps = [
        "network",
        "//3rdparty/jvm/com/softwaremill/quicklens",
        "//3rdparty/jvm/com/typesafe/akka:akka_actor",
        "//3rdparty/jvm/com/typesafe/akka:akka_actor_typed",
        "//3rdparty/jvm/com/typesafe/akka:akka_slf4j",
        "//3rdparty/jvm/com/typesafe/akka:akka_testkit",
        "//3rdparty/jvm/com/typesafe/akka:akka_testkit_typed",
        "//3rdparty/jvm/io/micrometer:micrometer_registry_datadog",
        "//3rdparty/jvm/io/monix",
        "//3rdparty/jvm/org/bouncycastle:bcprov_jdk15on",
        "//3rdparty/jvm/org/mockito:mockito_core",
        "//3rdparty/jvm/org/scalacheck",
        "//3rdparty/jvm/org/scalatest",
        "//3rdparty/jvm/javax/xml/bind:jaxb_api",
        "//3rdparty/jvm/com/sun/xml/bind:jaxb_core",
        "//3rdparty/jvm/com/sun/xml/bind:jaxb_impl",
        "//3rdparty/jvm/javax/activation:activation",
        "//main/io/iohk/network/telemetry",
        "@codecs//main/io/iohk/codecs",
        "@crypto//main/io/iohk/crypto",
        #"//main/io/iohk/cef/test",
        #"//main/io/iohk/cef/utils",
    ],
)
