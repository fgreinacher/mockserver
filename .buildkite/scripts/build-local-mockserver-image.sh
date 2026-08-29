#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Build a local MockServer Docker image from the current checkout.
#
# This script is called by the Python and Ruby integration-test CI steps
# so that those tests run against the HEAD-built image, not the stale
# mockserver/mockserver:snapshot image on Docker Hub.
#
# It can also be run standalone for local development.
#
# Usage:
#   source .buildkite/scripts/build-local-mockserver-image.sh
#   # afterwards $MOCKSERVER_IMAGE is set to the locally-built tag
#
# The script:
#   1. Builds the shaded JAR (mockserver-netty-no-dependencies) via Maven
#      — skipped if the JAR already exists (e.g. from a prior build step)
#   2. Copies the JAR into docker/local/ as the Dockerfile expects
#   3. Runs `docker build` to produce a local image
#
# Environment (all optional):
#   MOCKSERVER_IMAGE   — override the tag (default: mockserver-under-test:local)
#   SKIP_JAR_BUILD     — set to "true" to skip the Maven build (JAR must exist)
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

_BLM_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_BLM_REPO_ROOT="$(cd "$_BLM_SCRIPT_DIR/../.." && pwd)"

MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver-under-test:local}"

# ── Step 1: Locate or build the shaded JAR ───────────────────────────
_find_shaded_jar() {
  local jar_dir="$_BLM_REPO_ROOT/mockserver/mockserver-netty-no-dependencies/target"
  shopt -s nullglob
  for f in "$jar_dir"/mockserver-netty-no-dependencies-*.jar; do
    case "$(basename "$f")" in
      *-sources.jar|*-javadoc.jar|original-*) continue ;;
    esac
    echo "$f"
    shopt -u nullglob
    return 0
  done
  shopt -u nullglob
  return 1
}

SHADED_JAR=""
if SHADED_JAR=$(_find_shaded_jar); then
  echo "--- :package: Using existing shaded JAR: $SHADED_JAR"
elif [[ "${SKIP_JAR_BUILD:-}" == "true" ]]; then
  echo "Error: SKIP_JAR_BUILD=true but no shaded JAR found" >&2
  exit 1
else
  echo "--- :java: Building shaded JAR from source (this may take a few minutes)"
  # Run Maven inside the CI Docker image so no JDK is needed on the host.
  # run-in-docker.sh mounts the repo at /build; the JAR lands on the shared
  # volume at the same host path, visible to _find_shaded_jar afterward.
  #
  # --cache maven mounts the workspace-local .buildkite-cache/maven into the
  # container at ~/.m2/repository. When a client-language pipeline restores the
  # maven cache first (`.buildkite/scripts/cache-restore.sh maven`), this jar
  # build resolves from a warm local repo instead of hammering Maven Central,
  # which is what previously made these jobs die on a transient Central
  # "Connection reset". If no cache was restored the mount is an empty dir
  # (run-in-docker creates it) and the build is cold — identical to before.
  "$_BLM_SCRIPT_DIR/run-in-docker.sh" \
    -i mockserver/mockserver:maven \
    -w /build/mockserver \
    --cache maven \
    -- ./mvnw package -pl mockserver-netty-no-dependencies -am \
      -DskipTests -Djacoco.skip=true -Dmaven.javadoc.skip=true \
      -Dmaven.gitcommitid.skip=true -P '!build-ui' \
      -q --batch-mode --no-transfer-progress
  if ! SHADED_JAR=$(_find_shaded_jar); then
    echo "Error: Maven build completed but shaded JAR not found" >&2
    exit 1
  fi
  echo "--- :package: Built shaded JAR: $SHADED_JAR"
fi

# ── Step 2: Copy JAR into docker/local/ build context ────────────────
cp "$SHADED_JAR" "$_BLM_REPO_ROOT/docker/local/mockserver-netty-jar-with-dependencies.jar"

# ── Step 3: Build local Docker image ─────────────────────────────────
echo "--- :docker: Building local image: $MOCKSERVER_IMAGE"
docker build --tag "$MOCKSERVER_IMAGE" "$_BLM_REPO_ROOT/docker/local"

echo "--- :white_check_mark: Local MockServer image ready: $MOCKSERVER_IMAGE"

export MOCKSERVER_IMAGE
