import mill._
import mill.modules._
import scalalib._
import ammonite.ops._
import coursier.maven.MavenRepository
import mill.scalalib.{PublishModule, ScalaModule}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import $ivy.`com.lihaoyi::mill-contrib-buildinfo:0.4.1`
import mill.contrib.scoverage.ScoverageModule

// ScoverageModule creates bug when using custom repositories:
// https://github.com/lihaoyi/mill/issues/620
//object scalanet extends ScalaModule with PublishModule with ScoverageModule {
object scalanet extends ScalaModule with PublishModule {

  def scalaVersion = "2.12.7"

  def publishVersion = "0.1-SNAPSHOT"

  override def repositories =
    super.repositories ++ Seq(
      MavenRepository("https://oss.sonatype.org/content/repositories/releases"),
      MavenRepository("https://oss.sonatype.org/content/repositories/snapshots")
    )

  override def scalacOptions = Seq(
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

  override def ivyDeps = Agg(
    ivy"io.monix::monix:3.0.0-RC1",
    ivy"com.chuusai::shapeless:2.3.3",
    ivy"org.slf4j:slf4j-api:1.7.25",
    ivy"io.netty:netty-all:4.1.31.Final",
    ivy"org.eclipse.californium:scandium:2.0.0-M15",
    ivy"org.eclipse.californium:element-connector:2.0.0-M15",
    ivy"io.iohk::decco:1.0-SNAPSHOT",
    ivy"io.iohk::decco-auto:1.0-SNAPSHOT"
  )

  def pomSettings = PomSettings(
    description =
      "Asynchronous, strongly typed, resource-managed networking library, written in Scala with support for a variety of network technologies",
    organization = "io.iohk",
    url = "https://github.com/input-output-hk/scalanet",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("input-output-hk", "scalanet"),
    developers = Seq()
  )

  def scoverageVersion = "1.3.1"

  // Scoverage disabled
  // object test extends ScoverageTests {
  object test extends Tests {
    override def ivyDeps = Agg(
      ivy"org.scalatest::scalatest:3.0.5",
      ivy"org.scalacheck::scalacheck:1.14.0",
      ivy"ch.qos.logback:logback-core:1.2.3",
      ivy"ch.qos.logback:logback-classic:1.2.3"
    )

    override def moduleDeps: Seq[JavaModule] = super.moduleDeps ++ Seq(scalanet)

    def testFrameworks = Seq("org.scalatest.tools.Framework")

    def single(args: String*) = T.command {
      super.runMain("org.scalatest.run", args: _*)
    }
  }
}
