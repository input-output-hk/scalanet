# scalanet
A scala libaray for network

## Branches

Two main branches will be maintained: `develop` and `master`. `master` contains the latest version of the code that was tested end-to-end. `develop` contains the latest version of the code that runs all the tests (unit and integration tests). Integration tests don't test all the integrations. Hence, any version in `develop` might have bugs when deployed for an end-to-end test.


## Working with the codebase

To build the codebase, we use [bazel](https://bazel.build/).

In order to keep the code format consistent, we use scalafmt and git hooks, follow these steps to configure it accordingly (otherwise, your changes are going to be rejected by CircleCI):
- Install [coursier](https://github.com/coursier/coursier#command-line), the `coursier` command must work.
- `./install-scalafmt.sh` (might require sudo).
- `cp pre-commit .git/hooks/pre-commit`

