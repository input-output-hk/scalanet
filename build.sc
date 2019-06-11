
import mill._, mill.modules._, scalalib._, ammonite.ops._
//import ammonite.ops.ImplicitWd._

def decco = T {
  %("git", "clone", "git@github.com:input-output-hk/decco.git")(wd = T.ctx().dest)
  %("bazel", "build", "//...")(wd = T.ctx().dest / "decco")

  val x = Jvm.createAssembly(
    Agg(
      T.ctx().dest / "decco" / "bazel-bin" / "src" / "io" / "iohk" / "decco" / "decco.jar",
      T.ctx().dest / "decco" / "bazel-bin" / "src" / "io" / "iohk" / "decco" / "auto" / "auto.jar"))

  println(x)
  x
//  T.ctx().dest / "decco-thenuts.jar"
}

object core extends ScalaModule {
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
  "utf-8",
  )

  def compile = T {
    println("Compiling...")
    decco()
    super.compile()
  }
//  bazel()

  def ivyDeps = Agg(
    ivy"io.monix::monix:3.0.0-RC1",
    ivy"com.chuusai::shapeless:2.3.3",
    ivy"org.slf4j:slf4j-api:1.7.25"
  )
}