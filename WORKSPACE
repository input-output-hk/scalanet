load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

rules_scala_version = "ca5a7acff4ff630f68f58b8e01e8c25dbf908fb7"

http_archive(
    name = "io_bazel_rules_scala",
    url = "https://github.com/bazelbuild/rules_scala/archive/%s.zip"%rules_scala_version,
    type = "zip",
    strip_prefix= "rules_scala-%s" % rules_scala_version
)

http_archive(
    name = "com_google_protobuf",
    sha256 = "9510dd2afc29e7245e9e884336f848c8a6600a14ae726adb6befdb4f786f0be2",
    urls = ["https://github.com/protocolbuffers/protobuf/archive/v3.6.1.3.zip"],
    strip_prefix = "protobuf-3.6.1.3",
)

load("@io_bazel_rules_scala//scala:scala.bzl", "scala_repositories")

scala_repositories((
    "2.12.7",
    {
        "scala_compiler": "6e80ef4493127214d31631287a6789170bf6c9a771d6094acd8dc785e8970270",
        "scala_library": "8f3dc6091db688464ad8b1ee6c7343d7aa5940d474ee8b90406c71e45dd74fc0",
        "scala_reflect": "7427d7ee5771e8c36c1db5a09368fa3078f6eceb77d7c797a322a088c5dddb76",
    },
))

load("@io_bazel_rules_scala//scala:toolchains.bzl", "scala_register_toolchains")

register_toolchains("//toolchains:cef_scala_toolchain")

load("//3rdparty:workspace.bzl", "maven_dependencies")

maven_dependencies()

load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

git_repository(
    name = "decco",
    commit = "93828d6cd38589aac43a52f02d88e9e23bd3a5f4",
    remote = "https://github.com/input-output-hk/decco.git",
)
