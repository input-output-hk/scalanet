load("@io_bazel_rules_scala//scala:scala.bzl", "scala_repl", native_scala_binary = "scala_binary", native_scala_library = "scala_library", native_scala_test = "scala_test")

_default_scalac_jvm_flags = [
    "-Xmx1536M",
    "-Xms1536M",
    "-Xss5M",
    "-XX:MaxMetaspaceSize=512m",
]

def scala_library(name, srcs = None, deps = [], scalac_jvm_flags = _default_scalac_jvm_flags, visibility = ["//visibility:public"], **kwargs):
    native_scala_library(
        name = name,
        deps = deps,
        srcs = native.glob(["**/*.scala"], exclude = ["test/**/*"]) if srcs == None else srcs,
        scalac_jvm_flags = scalac_jvm_flags,
        visibility = visibility,
        **kwargs
    )
    scala_repl(
        name = "%s_repl" % name,
        deps = deps + [name],
        scalac_jvm_flags = scalac_jvm_flags,
    )

def scala_binary(name, main_class, srcs = None, deps = [], scalac_jvm_flags = _default_scalac_jvm_flags, **kwargs):
    native_scala_binary(
        name = name,
        deps = deps,
        srcs = native.glob(["**/*.scala"], exclude = ["test/**/*"]) if srcs == None else srcs,
        scalac_jvm_flags = scalac_jvm_flags,
        main_class = main_class,
        **kwargs
    )
    native_scala_library(
        name = "__%s_binary_lib" % name,
        deps = deps,
        srcs = native.glob(["**/*.scala"], exclude = ["test/**/*"]) if srcs == None else srcs,
        scalac_jvm_flags = scalac_jvm_flags,
        **kwargs
    )
    scala_repl(
        name = "%s_repl" % name,
        deps = deps + ["__%s_binary_lib" % name],
        scalac_jvm_flags = scalac_jvm_flags,
    )

def scala_test(name, srcs = None, deps = [], resources = None, scalac_jvm_flags = _default_scalac_jvm_flags, **kwargs):
    native_scala_test(
        name = name,
        deps = deps,
        resources = native.glob(["test-resources/**/*"]) if resources == None else resources,
        srcs = native.glob(["test/**/*.scala"]) if srcs == None else srcs,
        scalac_jvm_flags = scalac_jvm_flags,
        **kwargs
    )