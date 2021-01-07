import mill._
import mill.modules._
import scalalib._
import ammonite.ops._
import coursier.maven.MavenRepository
import mill.scalalib.{PublishModule, ScalaModule}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}

object csm extends Cross[ScalanetModule]("2.12.10", "2.13.4")

class ScalanetModule(crossVersion: String) extends Module {

  // A cross build is now a `root` of the module:
  // > mill show csm[2.13.4].scalanet.sources
  // [1/1] show
  // [1/1] show > [1/1] csm[2.13.4].scalanet.sources
  // [
  //     "ref:c984eca8: ../csm/2.13.4/scalanet/src"
  // ]
  // As soon as there is no such folder as /csm/2.13.4/
  // We have to navigate up two times on the module tree.
  //
  // > mill show csm[2.13.4].scalanet.sources
  //            // some output omitted //
  //     "ref:c984eca8: ../scalanet/src"
  override def millSourcePath = super.millSourcePath / os.up / os.up

  // scala 2.12 - only options
  // it causes errors when built against 2.13 (and vice-versa):
  // [error] bad option: '-Ywarn-unused-import'
  private val `scala 2.12 options`: Seq[String] = Seq(
    "-Xlint:unsound-match",
    "-Ywarn-inaccessible",
    "-Ywarn-unused-import",
    "-Ypartial-unification",
    "-Ywarn-value-discard"
  )

  // scala 2.13 might have another set of options
  private val `scala 2.13 options`: Seq[String] = Nil

  trait ScalanetModule extends ScalaModule {
    override def scalaVersion = crossVersion

    override def repositories =
      super.repositories ++ Seq(
        MavenRepository("https://oss.sonatype.org/content/repositories/releases"),
        MavenRepository("https://oss.sonatype.org/content/repositories/snapshots")
      )

    private val commonScalacOptions = Seq(
      "-unchecked",
      "-language:postfixOps",
      "-deprecation",
      "-feature",
      "-Xfatal-warnings",
      "-J-Xmx1.5G",
      "-J-Xms1.5G",
      "-J-XX:MaxMetaspaceSize=512m",
      "-encoding",
      "utf-8"
    )

    private val versionOptions = crossVersion.take(4) match {
      case "2.12" => `scala 2.12 options`
      case "2.13" => `scala 2.13 options`
    }

    override def scalacOptions = commonScalacOptions ++ versionOptions

    // `extends Tests` uses the context of the module in which it's defined,
    // which is why the trait is defined here not within `scalanet`, otherwise
    // it wouldn't work for `kademlia` for example.
    trait TestModule extends Tests {
      override def scalacOptions = ScalanetModule.this.scalacOptions

      override def testFrameworks =
        Seq("org.scalatest.tools.Framework")

      override def ivyDeps = Agg(
        ivy"org.scalatest::scalatest:3.0.9",
        ivy"org.scalacheck::scalacheck:1.14.0",
        ivy"ch.qos.logback:logback-core:1.2.3",
        ivy"ch.qos.logback:logback-classic:1.2.3",
        ivy"org.mockito:mockito-core:2.21.0",
        ivy"com.github.mike10004:fengyouchao-sockslib:1.0.6"
      )

      override def moduleDeps: Seq[JavaModule] =
        Seq(scalanet)

      def single(args: String*) = T.command {
        super.runMain("org.scalatest.run", args: _*)
      }
    }
  }

  // In objects inheriting this trait, use `override def moduleDeps: Seq[PublishModule]`
  // to point at other modules that also get published. In other cases such as tests
  // it can be `override def moduleDeps: Seq[JavaModule]`.
  trait ScalanetPublishModule extends PublishModule {
    def description: String

    override def publishVersion = "0.6.0-SNAPSHOT"

    override def pomSettings = PomSettings(
      description = description,
      organization = "io.iohk",
      url = "https://github.com/input-output-hk/scalanet",
      licenses = Seq(License.`Apache-2.0`),
      versionControl = VersionControl.github("input-output-hk", "scalanet"),
      developers = Seq()
    )
  }

  // ScoverageModule creates bug when using custom repositories:
  // https://github.com/lihaoyi/mill/issues/620
  //object scalanet extends ScalaModule with PublishModule with ScoverageModule {
  object scalanet extends ScalanetModule with ScalanetPublishModule {

    override def artifactName = "scalanet"

    override val description =
      "Asynchronous, strongly typed, resource-managed networking library, written in Scala with support for a variety of network technologies"

    override def ivyDeps = Agg(
      ivy"io.monix::monix:3.3.0",
      ivy"com.github.nscala-time::nscala-time:2.22.0",
      ivy"com.chuusai::shapeless:2.3.3",
      ivy"com.typesafe.scala-logging::scala-logging:3.9.2",
      ivy"com.github.jgonian:commons-ip-math:1.32",
      ivy"org.slf4j:slf4j-api:1.7.25",
      ivy"io.netty:netty-all:4.1.51.Final",
      ivy"org.eclipse.californium:scandium:2.0.0-M15",
      ivy"org.eclipse.californium:element-connector:2.0.0-M15",
      ivy"org.scodec::scodec-bits:1.1.12",
      ivy"org.scodec::scodec-core:1.11.7",
      ivy"org.bouncycastle:bcprov-jdk15on:1.64",
      ivy"org.bouncycastle:bcpkix-jdk15on:1.64",
      ivy"org.bouncycastle:bctls-jdk15on:1.64"
    )

    def scoverageVersion = "1.3.1"

    // Scoverage disabled
    // object test extends ScoverageTests {
    object ut extends TestModule

    object discovery extends ScalanetModule with ScalanetPublishModule {

      override def artifactName = "scalanet-discovery"

      override val description =
        "Implementation of peer-to-peer discovery algorithms such as that of Ethereum."

      override def moduleDeps: Seq[PublishModule] =
        Seq(scalanet)

      object ut extends TestModule {
        override def moduleDeps: Seq[JavaModule] =
          super.moduleDeps ++ Seq(scalanet.discovery, scalanet.ut)
      }

      object it extends TestModule {
        override def moduleDeps: Seq[JavaModule] =
          super.moduleDeps ++ Seq(scalanet.discovery.ut)
      }
    }

    object examples extends ScalanetModule {
      override def ivyDeps = Agg(
        ivy"ch.qos.logback:logback-core:1.2.3",
        ivy"ch.qos.logback:logback-classic:1.2.3",
        ivy"org.mockito:mockito-core:2.21.0",
        ivy"com.github.pureconfig::pureconfig:0.11.1",
        ivy"com.github.scopt::scopt:3.7.1",
        ivy"io.monix::monix:3.2.2",
        ivy"org.scala-lang.modules::scala-parser-combinators:1.1.2"
      )

      override def moduleDeps: Seq[JavaModule] =
        Seq(scalanet, scalanet.discovery)

      object ut extends TestModule {
        override def moduleDeps: Seq[JavaModule] =
          super.moduleDeps ++ Seq(scalanet.examples)
      }
    }
  }
}
