#!/bin/bash
./coursier bootstrap org.scalameta:scalafmt-cli_2.12:2.7.4 \
  -f \
  -r bintray:scalameta/maven \
  -o ./scalafmt --standalone --main org.scalafmt.cli.Cli
