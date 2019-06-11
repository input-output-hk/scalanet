import mill._
import mill.modules._
import scalalib._
import ammonite.ops._

def decco = T {
  %("git", "clone", "git@github.com:input-output-hk/decco.git")(T.ctx().dest)
  %("bazel", "build", "//...")(T.ctx().dest / "decco")

  val deccoJar = T.ctx.dest / "decco.jar"

  mv(
    Jvm
      .createAssembly(
        Agg(
          T.ctx().dest / "decco" / "bazel-bin" / "src" / "io" / "iohk" / "decco" / "decco.jar",
          T.ctx().dest / "decco" / "bazel-bin" / "src" / "io" / "iohk" / "decco" / "auto" / "auto.jar"
        )
      )
      .path,
    deccoJar
  )

  PathRef(deccoJar)
}

trait ScalanetModule extends ScalaModule {

  def scalaVersion = "2.12.7"

  def scalacOptions = Seq(
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
    "utf-8"
  )
}

object library extends ScalanetModule {

  def ivyDeps = Agg(
    ivy"io.monix::monix:3.0.0-RC1",
    ivy"com.chuusai::shapeless:2.3.3",
    ivy"org.slf4j:slf4j-api:1.7.25",
    ivy"io.netty:netty-all:4.1.31.Final"
  )

  def unmanagedClasspath = Agg(decco())

  object test extends Tests {
    def ivyDeps = Agg(
      ivy"org.scalatest::scalatest:3.0.5",
      ivy"ch.qos.logback:logback-core:1.2.3",
      ivy"ch.qos.logback:logback-classic:1.2.3"
    )

    def unmanagedClasspath = Agg(decco())

    def testFrameworks = Seq("org.scalatest.tools.Framework")

    def single(args: String*) = T.command {
      super.runMain("org.scalatest.run", args: _*)
    }
  }
}