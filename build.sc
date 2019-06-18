import mill._
import mill.modules._
import scalalib._
import ammonite.ops._
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import $ivy.`com.lihaoyi::mill-contrib-buildinfo:0.4.1`
import mill.contrib.scoverage.ScoverageModule

import $file.decco.build

object scalanet extends ScoverageModule with PublishModule {

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

  def artifactName = "scalanet"

  def publishVersion = "HEAD"

  def pomSettings = PomSettings(
    description = "Scalanet",
    organization = "io.iohk",
    url = "https://github.com/input-output-hk/scalanet.git",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("input-output-hk", "scalanet.git"),
    developers = Seq(
      Developer("jtownson", "Jeremy Townson", "https://github.com/jtownson")
      // TODO add your entries here...
    )
  )

  def ivyDeps = Agg(
    ivy"io.monix::monix:3.0.0-RC1",
    ivy"com.chuusai::shapeless:2.3.3",
    ivy"org.slf4j:slf4j-api:1.7.25",
    ivy"io.netty:netty-all:4.1.31.Final",
    ivy"org.eclipse.californium:scandium:2.0.0-M15",
    ivy"org.eclipse.californium:element-connector:2.0.0-M15"
  )

  def moduleDeps = Seq(decco.build.src.io.iohk.decco) ++ super.moduleDeps

  def scoverageVersion = "1.3.1"

  object test extends ScoverageTests {
    def ivyDeps = Agg(
      ivy"org.scalatest::scalatest:3.0.5",
      ivy"org.scalacheck::scalacheck:1.14.0",
      ivy"ch.qos.logback:logback-core:1.2.3",
      ivy"ch.qos.logback:logback-classic:1.2.3"
    )

    def moduleDeps =
      super.moduleDeps ++ Seq(decco.build.src.io.iohk.decco, decco.build.src.io.iohk.decco.auto, scalanet)

    def testFrameworks = Seq("org.scalatest.tools.Framework")

    def single(args: String*) = T.command {
      super.runMain("org.scalatest.run", args: _*)
    }
  }
}
