#!/usr/bin/env bash
set -euo pipefail

# Build the mockserver-netty jar-with-dependencies FROM THIS TREE so the Node
# launcher integration tests run against the code in the repo, not a published
# release downloaded from Maven Central.
#
# WHY THIS EXISTS AS ITS OWN STEP: the launcher tests live in the independent
# `mockserver-node` pipeline, which cannot see the `mockserver-java` pipeline's
# build artifacts (they are separate Buildkite builds with no shared filesystem).
# The jar therefore has to be produced within THIS pipeline. It is built once
# here and handed to the launcher-test step as a Buildkite artifact (see
# node-launcher-test.sh); the pipeline's artifact_paths upload the jar this
# produces under mockserver/mockserver-netty/target/.
#
# `-pl mockserver-netty -am` builds only the shaded runnable jar and its upstream
# reactor modules (core, client-java, async, ...); `-DskipTests -Dmaven.test.skip`
# skips all test compilation/execution — the Java tests are the Java pipeline's
# job, this step only needs the assembled jar.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver \
  -m 7g \
  --cache maven \
  -- bash -c './mvnw -B --no-transfer-progress -T 1C -DskipTests -Dmaven.test.skip=true -pl mockserver-netty -am package'
