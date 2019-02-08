# Do not edit. bazel-deps autogenerates this file from dependencies.yaml.
def _jar_artifact_impl(ctx):
    jar_name = "%s.jar" % ctx.name
    ctx.download(
        output=ctx.path("jar/%s" % jar_name),
        url=ctx.attr.urls,
        sha256=ctx.attr.sha256,
        executable=False
    )
    src_name="%s-sources.jar" % ctx.name
    srcjar_attr=""
    has_sources = len(ctx.attr.src_urls) != 0
    if has_sources:
        ctx.download(
            output=ctx.path("jar/%s" % src_name),
            url=ctx.attr.src_urls,
            sha256=ctx.attr.src_sha256,
            executable=False
        )
        srcjar_attr ='\n    srcjar = ":%s",' % src_name

    build_file_contents = """
package(default_visibility = ['//visibility:public'])
java_import(
    name = 'jar',
    tags = ['maven_coordinates={artifact}'],
    jars = ['{jar_name}'],{srcjar_attr}
)
filegroup(
    name = 'file',
    srcs = [
        '{jar_name}',
        '{src_name}'
    ],
    visibility = ['//visibility:public']
)\n""".format(artifact = ctx.attr.artifact, jar_name = jar_name, src_name = src_name, srcjar_attr = srcjar_attr)
    ctx.file(ctx.path("jar/BUILD"), build_file_contents, False)
    return None

jar_artifact = repository_rule(
    attrs = {
        "artifact": attr.string(mandatory = True),
        "sha256": attr.string(mandatory = True),
        "urls": attr.string_list(mandatory = True),
        "src_sha256": attr.string(mandatory = False, default=""),
        "src_urls": attr.string_list(mandatory = False, default=[]),
    },
    implementation = _jar_artifact_impl
)

def jar_artifact_callback(hash):
    src_urls = []
    src_sha256 = ""
    source=hash.get("source", None)
    if source != None:
        src_urls = [source["url"]]
        src_sha256 = source["sha256"]
    jar_artifact(
        artifact = hash["artifact"],
        name = hash["name"],
        urls = [hash["url"]],
        sha256 = hash["sha256"],
        src_urls = src_urls,
        src_sha256 = src_sha256
    )
    native.bind(name = hash["bind"], actual = hash["actual"])


def list_dependencies():
    return [
    {"artifact": "io.monix:monix-catnap_2.12:3.0.0-RC2", "lang": "scala", "sha1": "bd0163ea6867cc62097b660076e694802f79e599", "sha256": "f3dfd136c7a10a0713f6530671e01fcae6ae1191285adc48e496877d2672d867", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-catnap_2.12/3.0.0-RC2/monix-catnap_2.12-3.0.0-RC2.jar", "source": {"sha1": "fff8806b829a19d7161d107b3b8661e4fe6989c1", "sha256": "735fd1ecc98c7da074caad9939e6dca2fd4a330ab7cdaee98b9cf19d71a39298", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-catnap_2.12/3.0.0-RC2/monix-catnap_2.12-3.0.0-RC2-sources.jar"} , "name": "io_monix_monix_catnap_2_12", "actual": "@io_monix_monix_catnap_2_12//jar:file", "bind": "jar/io/monix/monix_catnap_2_12"},
    {"artifact": "io.monix:monix-cats_2.12:2.3.3", "lang": "scala", "sha1": "5062253f8f67a09ff40f5887e60fbc1d24609231", "sha256": "faac662bbd57ca8e59c4ac58353267b18a8c41a550bde394ac44622d80ae82f1", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-cats_2.12/2.3.3/monix-cats_2.12-2.3.3.jar", "source": {"sha1": "df1192670f9a1f8e842697b5960d488eea36ebf7", "sha256": "4c6851224b4c86b9c5d660ac8323688607202a008b2c589156018e5ef770f6e1", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-cats_2.12/2.3.3/monix-cats_2.12-2.3.3-sources.jar"} , "name": "io_monix_monix_cats_2_12", "actual": "@io_monix_monix_cats_2_12//jar:file", "bind": "jar/io/monix/monix_cats_2_12"},
    {"artifact": "io.monix:monix-eval_2.12:3.0.0-RC2", "lang": "scala", "sha1": "b0fc9eefa12b07423f17f986b85d8561efdff566", "sha256": "1bf3c991433aa4e9df698f94b0ff8cce20e3de45d13b55f78a95cbf5f31e306f", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-eval_2.12/3.0.0-RC2/monix-eval_2.12-3.0.0-RC2.jar", "source": {"sha1": "386fd5e1073ceda7a9789592a833515fc24854ed", "sha256": "03171fded2901b8ee6699fcc9b67cb3ddeed99f602eafcd9b7c35cf779372e3c", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-eval_2.12/3.0.0-RC2/monix-eval_2.12-3.0.0-RC2-sources.jar"} , "name": "io_monix_monix_eval_2_12", "actual": "@io_monix_monix_eval_2_12//jar:file", "bind": "jar/io/monix/monix_eval_2_12"},
    {"artifact": "io.monix:monix-execution_2.12:3.0.0-RC2", "lang": "scala", "sha1": "8ebb614e5f859d6cf87fe0d01e90af05065fe905", "sha256": "18aa9fffc563c7926c8129b0e04bc100aa7e63adaa024d499750f621c194eb27", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-execution_2.12/3.0.0-RC2/monix-execution_2.12-3.0.0-RC2.jar", "source": {"sha1": "0758bc9112c24dc731305c2506ed42fe57e2c2ec", "sha256": "36f890764279a538329697ebf434927bfa42056f62e30a5000483d8837910527", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-execution_2.12/3.0.0-RC2/monix-execution_2.12-3.0.0-RC2-sources.jar"} , "name": "io_monix_monix_execution_2_12", "actual": "@io_monix_monix_execution_2_12//jar:file", "bind": "jar/io/monix/monix_execution_2_12"},
    {"artifact": "io.monix:monix-java_2.12:3.0.0-RC2", "lang": "scala", "sha1": "9757bb7a5335138ba841844523678583df7c1242", "sha256": "327dacc1f83fd0062b120e5affe83edf28200cfc305298164dc1c4534b784ce1", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-java_2.12/3.0.0-RC2/monix-java_2.12-3.0.0-RC2.jar", "source": {"sha1": "ea2aba90ff766e1facf634bae33536d2c5714078", "sha256": "7ddd10881d06ecd0c3c8159ba2fde6d06068e36ef7297c9c6ea03be7f72edbe6", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-java_2.12/3.0.0-RC2/monix-java_2.12-3.0.0-RC2-sources.jar"} , "name": "io_monix_monix_java_2_12", "actual": "@io_monix_monix_java_2_12//jar:file", "bind": "jar/io/monix/monix_java_2_12"},
    {"artifact": "io.monix:monix-reactive_2.12:3.0.0-RC2", "lang": "scala", "sha1": "1306df680b2ab8c2c954eec6a6bc47cefecb99ce", "sha256": "306e2e8d2b01427e5a7409075d98f6133c0afbe1c053e94abf251ce03db8a41b", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-reactive_2.12/3.0.0-RC2/monix-reactive_2.12-3.0.0-RC2.jar", "source": {"sha1": "7633e264cfcc5843c88c4654d0123f1a75973c15", "sha256": "0f22d2747380abc6e691027e77ddd163c3f5b1cd39f34342a611cbb357dad33e", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-reactive_2.12/3.0.0-RC2/monix-reactive_2.12-3.0.0-RC2-sources.jar"} , "name": "io_monix_monix_reactive_2_12", "actual": "@io_monix_monix_reactive_2_12//jar:file", "bind": "jar/io/monix/monix_reactive_2_12"},
    {"artifact": "io.monix:monix-tail_2.12:3.0.0-RC2", "lang": "scala", "sha1": "7fd3faec28fa59302ee7430734409888b75da469", "sha256": "60a0501ab0aa6aa89593b33ca82cb5693fb52c26ef2ef4e6dfd09955bf67db5b", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-tail_2.12/3.0.0-RC2/monix-tail_2.12-3.0.0-RC2.jar", "source": {"sha1": "ff327aa5c983d6dd5750a55ed0faa97780c06ed6", "sha256": "5bcd71d613b703b2e4edacc2931281c844ecb9e3cffd7a749cce4fbc9e8af258", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-tail_2.12/3.0.0-RC2/monix-tail_2.12-3.0.0-RC2-sources.jar"} , "name": "io_monix_monix_tail_2_12", "actual": "@io_monix_monix_tail_2_12//jar:file", "bind": "jar/io/monix/monix_tail_2_12"},
    {"artifact": "io.monix:monix-types_2.12:2.3.3", "lang": "scala", "sha1": "007df159e73f74ca04f0330d350d85fadb3e1d9d", "sha256": "619624432339bc30999a5faef08eecbd0bb85759bd4971451d587e667e0b7a0b", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-types_2.12/2.3.3/monix-types_2.12-2.3.3.jar", "source": {"sha1": "ca463f7b6779e030ce8ebeab2ea9fc3d5fae8747", "sha256": "d9b362d3860fa5c8ce2ba76ce6228a7ec950616c61fab32191003c10362ff602", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix-types_2.12/2.3.3/monix-types_2.12-2.3.3-sources.jar"} , "name": "io_monix_monix_types_2_12", "actual": "@io_monix_monix_types_2_12//jar:file", "bind": "jar/io/monix/monix_types_2_12"},
    {"artifact": "io.monix:monix_2.12:3.0.0-RC2", "lang": "scala", "sha1": "65e6bee72a0b5ae1162f11a0b252f79935b2799a", "sha256": "a669ea3d6353b5e25efbe6344410b8bedf37a85cf8dfca5246d361881116a18e", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix_2.12/3.0.0-RC2/monix_2.12-3.0.0-RC2.jar", "source": {"sha1": "64f048360e0797fa839181389d474bdff37b50c8", "sha256": "f420410a92cd04a5364bfaf795ce56eebed9acf8a2239119f250ac7f4235ee23", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/monix/monix_2.12/3.0.0-RC2/monix_2.12-3.0.0-RC2-sources.jar"} , "name": "io_monix_monix_2_12", "actual": "@io_monix_monix_2_12//jar:file", "bind": "jar/io/monix/monix_2_12"},
    {"artifact": "io.netty:netty-all:4.1.28.Final", "lang": "java", "sha1": "33ae3d109e16b8c591bdf343f6b52ccd0ef75905", "sha256": "375036f44a72a99b73aac3997b77229270c80f6531f2fea84bd869178c6ea203", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/netty/netty-all/4.1.28.Final/netty-all-4.1.28.Final.jar", "source": {"sha1": "eb551d5ad67954f637cffcc010fbd0a913089188", "sha256": "ad93898f4bdb6800c8f8e1fae2e36f84ee5a3637b990e527d677c54f74d016b3", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/io/netty/netty-all/4.1.28.Final/netty-all-4.1.28.Final-sources.jar"} , "name": "io_netty_netty_all", "actual": "@io_netty_netty_all//jar", "bind": "jar/io/netty/netty_all"},
    {"artifact": "org.jctools:jctools-core:2.1.1", "lang": "java", "sha1": "9a1a7e006fb11f64716694c30de243fdf973c62f", "sha256": "21d1f6c06bca41fc8ededed6dbc7972cff668299f1e4c79ca62a9cb39f2fb4f8", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/jctools/jctools-core/2.1.1/jctools-core-2.1.1.jar", "source": {"sha1": "ce302ce6fd1a195b46e53284287330ad8a1f399a", "sha256": "687843a61b6bd5160a3a1cabb29b42181d565e445a902832c8ca23026b48a0b9", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/jctools/jctools-core/2.1.1/jctools-core-2.1.1-sources.jar"} , "name": "org_jctools_jctools_core", "actual": "@org_jctools_jctools_core//jar", "bind": "jar/org/jctools/jctools_core"},
    {"artifact": "org.reactivestreams:reactive-streams:1.0.2", "lang": "java", "sha1": "323964c36556eb0e6209f65c1cef72b53b461ab8", "sha256": "cc09ab0b140e0d0496c2165d4b32ce24f4d6446c0a26c5dc77b06bdf99ee8fae", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/reactivestreams/reactive-streams/1.0.2/reactive-streams-1.0.2.jar", "source": {"sha1": "fb592a3d57b11e71eb7a6211dd12ba824c5dd037", "sha256": "963a6480f46a64013d0f144ba41c6c6e63c4d34b655761717a436492886f3667", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/reactivestreams/reactive-streams/1.0.2/reactive-streams-1.0.2-sources.jar"} , "name": "org_reactivestreams_reactive_streams", "actual": "@org_reactivestreams_reactive_streams//jar", "bind": "jar/org/reactivestreams/reactive_streams"},
    {"artifact": "org.scala-lang.modules:scala-collection-compat_2.12:0.1.1", "lang": "scala", "sha1": "0543065c9e7d94aa72810a9b32a01d7b25367c4b", "sha256": "acf79af5eac905edc6fd6dcc18f98562bf026a92f881946ceef068f7d36b9f0e", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/scala-lang/modules/scala-collection-compat_2.12/0.1.1/scala-collection-compat_2.12-0.1.1.jar", "source": {"sha1": "d2140e0e98630989b83d450725224cae4c712472", "sha256": "91b0c086d8390d57612d101a2b19f2d7bda8b06479b15648bd72993e30630354", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/scala-lang/modules/scala-collection-compat_2.12/0.1.1/scala-collection-compat_2.12-0.1.1-sources.jar"} , "name": "org_scala_lang_modules_scala_collection_compat_2_12", "actual": "@org_scala_lang_modules_scala_collection_compat_2_12//jar:file", "bind": "jar/org/scala_lang/modules/scala_collection_compat_2_12"},
    {"artifact": "org.scalactic:scalactic_2.12:3.0.5", "lang": "scala", "sha1": "edec43902cdc7c753001501e0d8c2de78394fb03", "sha256": "57e25b4fd969b1758fe042595112c874dfea99dca5cc48eebe07ac38772a0c41", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/scalactic/scalactic_2.12/3.0.5/scalactic_2.12-3.0.5.jar", "source": {"sha1": "e02d37e95ba74c95aa9063b9114db51f2810b212", "sha256": "0455eaecaa2b8ce0be537120c2ccd407c4606cbe53e63cb6a7fc8b31b5b65461", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/scalactic/scalactic_2.12/3.0.5/scalactic_2.12-3.0.5-sources.jar"} , "name": "org_scalactic_scalactic_2_12", "actual": "@org_scalactic_scalactic_2_12//jar:file", "bind": "jar/org/scalactic/scalactic_2_12"},
    {"artifact": "org.scalatest:scalatest_2.12:3.0.5", "lang": "scala", "sha1": "7bb56c0f7a3c60c465e36c6b8022a95b883d7434", "sha256": "b416b5bcef6720da469a8d8a5726e457fc2d1cd5d316e1bc283aa75a2ae005e5", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/scalatest/scalatest_2.12/3.0.5/scalatest_2.12-3.0.5.jar", "source": {"sha1": "ec414035204524d3d4205ef572075e34a2078c78", "sha256": "22081ee83810098adc9af4d84d05dd5891d7c0e15f9095bcdaf4ac7a228b92df", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/scalatest/scalatest_2.12/3.0.5/scalatest_2.12-3.0.5-sources.jar"} , "name": "org_scalatest_scalatest_2_12", "actual": "@org_scalatest_scalatest_2_12//jar:file", "bind": "jar/org/scalatest/scalatest_2_12"},
# duplicates in org.typelevel:cats-core_2.12 promoted to 1.3.1
# - io.monix:monix-cats_2.12:2.3.3 wanted version 0.9.0
# - org.typelevel:cats-effect_2.12:1.0.0 wanted version 1.3.1
    {"artifact": "org.typelevel:cats-core_2.12:1.3.1", "lang": "scala", "sha1": "9c10abeaace01a9b644c89118949ca00b4545502", "sha256": "39d276d66e2f58bc9f6fd07f0778e512e25bdc31ff7aa169ad8c88f24a33794b", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/typelevel/cats-core_2.12/1.3.1/cats-core_2.12-1.3.1.jar", "source": {"sha1": "b772e7220d70dac321907f2af4238ed49289eb37", "sha256": "c0bff97649d4d429256f84e7ec6a6275e9edddd2538b55fac580f809868a22eb", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/typelevel/cats-core_2.12/1.3.1/cats-core_2.12-1.3.1-sources.jar"} , "name": "org_typelevel_cats_core_2_12", "actual": "@org_typelevel_cats_core_2_12//jar:file", "bind": "jar/org/typelevel/cats_core_2_12"},
    {"artifact": "org.typelevel:cats-effect_2.12:1.0.0", "lang": "scala", "sha1": "be5a0f66d578726462d489d1835285664531c7a6", "sha256": "b4c58e58da2ac4a38ed1596d62d15ee4db75fdf6089e581157fb069ea76cf925", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/typelevel/cats-effect_2.12/1.0.0/cats-effect_2.12-1.0.0.jar", "source": {"sha1": "4d6d09f4ec56b58dab04578a3cadc211bc99d34a", "sha256": "4db834829c553372b13bea7d12340f17dd9770557db2a3f9987e266653e37e2e", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/typelevel/cats-effect_2.12/1.0.0/cats-effect_2.12-1.0.0-sources.jar"} , "name": "org_typelevel_cats_effect_2_12", "actual": "@org_typelevel_cats_effect_2_12//jar:file", "bind": "jar/org/typelevel/cats_effect_2_12"},
    {"artifact": "org.typelevel:cats-kernel_2.12:1.3.1", "lang": "scala", "sha1": "b231dd446f458db1e496227ca3f0b4d412b9b04b", "sha256": "85da929c1292c09fa109eb3cb94f79c3a8242b838547eaa1e0341c35c9a1a31c", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/typelevel/cats-kernel_2.12/1.3.1/cats-kernel_2.12-1.3.1.jar", "source": {"sha1": "3b98bd299988d7622eaa685b27ced2d594526737", "sha256": "eb22d0943ae08f3c300bc91787a7246a3220bc9bba2952d3dc675f6d293faedc", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/typelevel/cats-kernel_2.12/1.3.1/cats-kernel_2.12-1.3.1-sources.jar"} , "name": "org_typelevel_cats_kernel_2_12", "actual": "@org_typelevel_cats_kernel_2_12//jar:file", "bind": "jar/org/typelevel/cats_kernel_2_12"},
    {"artifact": "org.typelevel:cats-macros_2.12:1.3.1", "lang": "scala", "sha1": "fe3f2067893c5b02695ee0d3fa857a3f55602763", "sha256": "1285803f27b7f5d966cd16a4d66e03e87f660e72a50baf6be42e43d9161f8742", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/typelevel/cats-macros_2.12/1.3.1/cats-macros_2.12-1.3.1.jar", "source": {"sha1": "5e97dd8e00647e9b01c8f52e4e7054d78bf97b75", "sha256": "ee620b76673b38f2056c532d7ab78a239ae676c1d4f0299ef22b6f3562d0ae70", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/typelevel/cats-macros_2.12/1.3.1/cats-macros_2.12-1.3.1-sources.jar"} , "name": "org_typelevel_cats_macros_2_12", "actual": "@org_typelevel_cats_macros_2_12//jar:file", "bind": "jar/org/typelevel/cats_macros_2_12"},
    {"artifact": "org.typelevel:machinist_2.12:0.6.5", "lang": "scala", "sha1": "abf43203c83e1c39532e24e87d279712c0ddf823", "sha256": "9b449314637d967b8acf1bcb744b605e118fe6ac6c7d08e8db68b7f39267d8e5", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/typelevel/machinist_2.12/0.6.5/machinist_2.12-0.6.5.jar", "source": {"sha1": "614df5c0d01abed72913bafec207cbb1e4d0a941", "sha256": "a7439aa63f3c25bd108464e575653307e8688c3dfb868f4303e56a810eb5ea37", "repository": "https://repo.maven.apache.org/maven2/", "url": "https://repo.maven.apache.org/maven2/org/typelevel/machinist_2.12/0.6.5/machinist_2.12-0.6.5-sources.jar"} , "name": "org_typelevel_machinist_2_12", "actual": "@org_typelevel_machinist_2_12//jar:file", "bind": "jar/org/typelevel/machinist_2_12"},
    ]

def maven_dependencies(callback = jar_artifact_callback):
    for hash in list_dependencies():
        callback(hash)
