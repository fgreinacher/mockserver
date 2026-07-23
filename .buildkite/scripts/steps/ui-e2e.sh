#!/usr/bin/env bash
# Dashboard end-to-end tests: drive the SERVED dashboard in a real browser
# (headless Chromium via Playwright) against a REAL MockServer over real REST and
# a real WebSocket — the browser-level backstop the jsdom/vitest suite cannot
# provide. Covers dashboard-driven expectation CRUD against the live control
# plane and the live WebSocket log stream.
#
# Hard CI gate — a failure here blocks master (fail-closed: Playwright exits
# non-zero on any failure AND when zero tests are found).
#
# Topology (same-origin, mirroring how a user runs the dashboard):
#   1. Build the CURRENT runnable JAR (the build-ui Maven profile bundles the
#      current UI source into it) using the Maven CI image.
#   2. Run that JAR in a JRE container on a private Docker network — it serves the
#      dashboard, the /mockserver/* control plane, and the WebSocket, all on one
#      origin.
#   3. Run the Playwright image (Chromium bundled) on the same network, pointed at
#      the server container (E2E_EXTERNAL_SERVER=1 so Playwright does NOT try to
#      boot its own JVM).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
cd "$REPO_ROOT"

NETWORK_NAME="mockserver-ui-e2e-$$"
SERVER_NAME="mockserver-ui-e2e-server-$$"
SERVER_PORT=1080

cleanup() {
  docker rm -f "$SERVER_NAME" 2>/dev/null || true
  docker network rm "$NETWORK_NAME" 2>/dev/null || true
}
trap cleanup EXIT

# --- 1. Build the current runnable JAR (bundles the current UI) -------------
echo "--- :maven: Building the runnable MockServer JAR (bundles current dashboard)"
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -m 7g \
  --cache maven \
  -w /build/mockserver \
  -- ./mvnw -q clean install -DskipTests -pl mockserver-netty-no-dependencies -am

# newest runnable jar (exclude -sources/-javadoc/original- variants); find+sort avoids `ls | grep` (SC2010)
JAR=$(find "$REPO_ROOT/mockserver/mockserver-netty-no-dependencies/target" -maxdepth 1 -type f \
  -name 'mockserver-netty-no-dependencies-*.jar' \
  ! -name '*-sources*' ! -name '*-javadoc*' ! -name 'original-*' \
  -printf '%T@\t%p\n' 2>/dev/null | sort -rn | head -1 | cut -f2-)
if [ -z "$JAR" ]; then
  echo "ERROR: runnable JAR not found after build"
  exit 1
fi
JAR_REL="${JAR#"$REPO_ROOT"/}"
echo "--- Built $JAR_REL"

# --- 2. Boot the JAR as the live server -------------------------------------
docker network create "$NETWORK_NAME"

echo "--- :mockserver: Starting MockServer (serves the dashboard) on the e2e network"
docker run -d \
  --name "$SERVER_NAME" \
  --network "$NETWORK_NAME" \
  -v "$REPO_ROOT":/build \
  -w /build \
  eclipse-temurin:17-jre \
  java -Xmx512m -Dmockserver.maxLogEntries=2000 -jar "/build/$JAR_REL" -serverPort "$SERVER_PORT" -logLevel WARN

echo "--- Waiting for the dashboard to answer..."
DEADLINE=$((SECONDS + 120))
READY=false
while [ $SECONDS -lt $DEADLINE ]; do
  if docker run --rm --network "$NETWORK_NAME" curlimages/curl:latest \
      -sf "http://$SERVER_NAME:$SERVER_PORT/mockserver/dashboard/" >/dev/null 2>&1; then
    READY=true
    break
  fi
  sleep 2
done
if [ "$READY" != "true" ]; then
  echo "ERROR: MockServer dashboard did not come up within the deadline"
  echo "--- :docker: MockServer container logs"
  docker logs "$SERVER_NAME" || true
  exit 1
fi
echo "MockServer dashboard is up"

# --- 3. Run the Playwright browser tests against it -------------------------
# Derive the Playwright image tag from the @playwright/test version in the
# lockfile so the bundled Chromium always matches the installed Playwright (a
# hardcoded tag drifts the moment Dependabot bumps @playwright/test).
PW_VERSION=$(python3 -c "import json;print(json.load(open('mockserver-ui/package-lock.json'))['packages']['node_modules/@playwright/test']['version'])")
echo "--- :playwright: Using Playwright image v${PW_VERSION}-noble (from mockserver-ui/package-lock.json)"

"$SCRIPT_DIR/../run-in-docker.sh" \
  -i "mcr.microsoft.com/playwright:v${PW_VERSION}-noble" \
  -w /build/mockserver-ui \
  --cache npm \
  -e "E2E_EXTERNAL_SERVER=1" \
  -e "E2E_MS_HOST=$SERVER_NAME" \
  -e "E2E_MS_PORT=$SERVER_PORT" \
  -e "CI=true" \
  --network "$NETWORK_NAME" \
  -- bash -c 'npm ci && npm run test:e2e'
