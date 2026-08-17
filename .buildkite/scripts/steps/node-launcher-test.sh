#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# The launcher tests must exercise the jar built FROM THIS TREE (see
# node-launcher-build-jar.sh), not a release downloaded from Maven Central — a
# downloaded release tests nothing about the repo and has flaked ~8% on a shipped
# dynamic-CA race that this tree already fixes. The jar is produced by the
# "build launcher jar" step and moved here as a Buildkite artifact (steps share
# no filesystem). Download it into the workspace, which is mounted at /build.
echo "--- :buildkite: Downloading launcher jar artifact"
buildkite-agent artifact download "mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar" "$REPO_ROOT"

shopt -s nullglob
JAR=""
for f in "$REPO_ROOT"/mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar; do
  case "$(basename "$f")" in
    *-sources.jar|*-javadoc.jar) continue ;;
  esac
  JAR="$f"
  break
done
shopt -u nullglob

# Fail CLOSED if the jar is absent. Silently falling back to a downloaded release
# would recreate the exact defect this change removes: a green launcher run that
# proved nothing about the tree (and could not catch a regression in it).
if [ -z "$JAR" ]; then
  echo "^^^ +++"
  echo ":x: Launcher jar not found after artifact download — the launcher tests cannot run against this tree."
  echo "    Expected mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar from the 'build launcher jar' step."
  echo "    Refusing to fall back to a downloaded release. Failing closed."
  exit 1
fi

JAR_REL="${JAR#"$REPO_ROOT"/}"
echo "Launcher jar present: $JAR_REL"

# MOCKSERVER_JAR_PATH points the launcher at this exact jar and disables the
# download entirely (index.js). The path is the in-container path under /build.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver-node \
  --cache npm \
  -e "MOCKSERVER_JAR_PATH=/build/$JAR_REL" \
  -- bash -c '/build/.buildkite/scripts/install-nodejs.sh && npm ci && npm test'
