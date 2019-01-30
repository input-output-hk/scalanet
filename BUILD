# //toolchains/BUILD
load("@io_bazel_rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")

scala_toolchain(
    name = "cef_toolchain_impl",
    scalacopts = [
       "-unchecked",
       "-language:postfixOps",
       "-deprecation",
       "-feature",
       "-Xfatal-warnings",
       "-Xlint:unsound-match",
       "-Ywarn-inaccessible",
       "-Ywarn-unused-import",
       "-Ypartial-unification",
       "-J-Xmx1.5G",
       "-J-Xms1.5G",
       "-J-XX:MaxMetaspaceSize=512m",
       "-encoding",
       "utf-8",
      ],
    #unused_dependency_checker_mode = "off",
    visibility = ["//visibility:public"]
)

toolchain(
    name = "cef_scala_toolchain",
    toolchain_type = "@io_bazel_rules_scala//scala:toolchain_type",
    toolchain = "cef_toolchain_impl",
    visibility = ["//visibility:public"]
)

