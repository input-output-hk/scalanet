#!/bin/bash
coursier bootstrap org.scalameta:scalafmt-cli_2.12:2.0.0-RC4 \
  -r bintray:scalameta/maven \
  -o /usr/local/bin/scalafmt --standalone --main org.scalafmt.cli.Cli
