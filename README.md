# CI status
[![CircleCI](https://circleci.com/gh/input-output-hk/scalanet.svg?style=svg&circle-token=de4aa64767f761c1f85c706500a5aca50074a244)](https://circleci.com/gh/input-output-hk/scalanet)

# scalanet

### Summary

Scalanet is an asynchronous, strongly typed, resource-managed networking library, written in Scala with support for a variety of network technologies.
What does all that mean?
 * Resource managed. Scalanet makes it as easy as possible to send and receive messages without having to open or close connections.
 * Asynchronous. Scalanet is non-blocking. In this regard, it is like [Netty](https://netty.io); however, unlike Netty, Scalanet uses *reactive*
 programming idioms.
 * Technology support. Out of the box, Scalanet supports TCP and UDP (with other internet and non-internet technologies to come) but through an abstraction called the _Peer Group_, allows for the addition of other transports or more complex p2p overlays (kademlia, ethereum, etc.). The _Peer Group_ provides a consistent interface whatever your networking approach.

It is well suited to peer-to-peer apps but supports client-server too.

### Peer groups
The foundation of Scalanet is the notion of a _Peer Group_. From a practical standpoint, a peer group
allows an application to use a variety of network technologies with a consistent interface. More abstractly, it defines
* an address space and
* a context in which communication can happen.
* TODO: quality of service

A Peer Group could be something like Scalanet's `UDPPeerGroup` where the addresses are IP:port combos and the set of
peers allowed to communicate is anybody on the IP network in question (the internet, an office network, etc.).
Equally, a Peer Group could be something like an Ethereum network where addresses are public keys and the peers
are anybody who talks the RLPx protocol. Equally, a peer group could be an integration test with the address space {Alice, Bob, Charlie}
and the peers are all in the same JVM. Scalanet will not limit you in this regard.

Peer groups can implement arbitrary enrolment and encryption schemes, so they are suitable for implementing secure messaging overlays.
Typically, on the internet, limiting the context of communication (aka _zoning_) is performed by firewalls. The idea
is so ubiquitous that it may not have struck you that this is a hack. Peer groups are designed to support more elegant
solutions, generally using cryptography instead of firewall provisioning.

### Structure of the library
Here is a picture of the structure of the library for a few sample applications:
![sample configurations](doc-resources/sample-configurations.png)

### Getting started
The easiest way to get started is to send and receive data over TCP, using the library just like Netty. Have a look at
the [TCPPeerGroupSpec](core/io/iohk/scalanet/test/peergroup/TCPPeerGroupSpec.scala) test case or the following code.

```scala
// import some peer group classes
import io.iohk.scalanet.peergroup._

// message sending can be controlled using either
// monix, cat-effect, cats EitherT or scala Futures
// depending on your taste.
import io.iohk.scalanet.peergroup.future._
import scala.concurrent.Future

import java.net.InetSocketAddress
import java.nio.ByteBuffer

val config = TCPPeerGroup.Config(new InetSocketAddress(???))

val tcp = TCPPeerGroup.createOrThrow(config)

// send a message
val messageF: Future[Unit] = tcp.sendMessage(new InetSocketAddress("example.com", 80), ByteBuffer.wrap("Hello!".getBytes))

// receive messages
tcp.messageStream.foreach((b: ByteBuffer) => ())

```

# Contributing

### Branches

Two main branches are maintained: `develop` and `master`.
`master` contains the latest stable version of the library.
`develop` is the place you want to merge to if submitting PRs.

### Building the codebase
Scalanet is capable of building against Scala 2.12.10 and 2.13.4
This guide will be using version 2.13.4 build: `mill csm[2.13.4]...` next to a multi-build `mill __.`

To build the codebase, we use [Mill](https://com-lihaoyi.github.io/mill/). Assuming you have Mill installed correctly, you can build and test the codebase with
```bash
mill csm[2.13.4].__.test   -or-
mill __.test
```

A single test suite can be executed with the `single` helper command, for example:
```bash
mill csm[2.13.4].scalanet.ut.single io.iohk.scalanet.crypto.SignatureVerificationSpec   -or-
mill __.scalanet.ut.single io.iohk.scalanet.crypto.SignatureVerificationSpec
```

### Publishing

Have a look [at these instructions](https://com-lihaoyi.github.io/mill/page/common-project-layouts.html#publishing) for how to publish multiple modules.
The latest build on the `develop` branch is always published to [Sonatype](https://oss.sonatype.org/) according to the [Circle CI config](./.circleci/config.yml).
To use it in a downstream project, add the snapshots to the resolvers, e.g. in `build.sbt`:

```
resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
```

To publish a new release to maven central:
1. Create a release branch `realease/versionFromVersionFile`
2. Create a PR from the release branch to `master` branch
3. Merge the release PR to `master`, tag, merge, commit, and push it
4. Create a merge back PR from `master` to `develop` bumping the appropriate version in `versionFile/version`, 
   e.g. `mill versionFile.setNextVersion --bump minor`, to make sure no more updates are sent to the released snapshot


### Formatting the codebase
To keep the code format consistent, we use scalafmt.

The CI build will fail if code is not formatted, but the project contains a githook that means you do not have to think
about it. To set this up:
- Install [coursier](https://github.com/coursier/coursier#command-line); the `coursier` command must work.
- `./install-scalafmt.sh` (might require sudo).
- `cp pre-commit .git/hooks/pre-commit`

### Reporting problems
You can also create issues in GitHub at https://github.com/input-output-hk/scalanet/issues.
