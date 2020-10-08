#!/bin/bash
./coursier bootstrap org.scalameta:scalafmt-cli_2.12:2.7.4 \
  -f \
  -r sonatype:snapshots --main org.scalafmt.cli.Cli \
  --standalone \
  -o scalafmt
