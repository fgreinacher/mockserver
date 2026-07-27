#!/usr/bin/env bash
# Run the mockserver-client-node functional tests (node:test) against a
# MockServer Docker container, mirroring the python/ruby integration test
# pattern.  The tests are parameterised via MOCKSERVER_HOST / MOCKSERVER_PORT
# environment variables.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_NAME="mockserver-node-client-$$"
MOCKSERVER_NAME="mockserver-node-client-server-$$"

# Build a local MockServer image from the current checkout so the client's
# wire assertions run against HEAD, not the stale :snapshot image on Docker Hub.
# shellcheck source=../build-local-mockserver-image.sh
source "$SCRIPT_DIR/../build-local-mockserver-image.sh"

cleanup() {
  docker rm -f "$MOCKSERVER_NAME" 2>/dev/null || true
  docker network rm "$NETWORK_NAME" 2>/dev/null || true
}
trap cleanup EXIT

docker network create "$NETWORK_NAME"

docker run -d \
  --name "$MOCKSERVER_NAME" \
  --network "$NETWORK_NAME" \
  -e "MOCKSERVER_ENABLE_CORS_FOR_ALL_RESPONSES=true" \
  -e 'MOCKSERVER_CORS_ALLOW_METHODS=CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE' \
  -e 'MOCKSERVER_CORS_ALLOW_HEADERS=Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization' \
  -e "MOCKSERVER_CORS_ALLOW_CREDENTIALS=true" \
  -e "MOCKSERVER_CORS_MAX_AGE_IN_SECONDS=300" \
  -e "MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION=false" \
  "$MOCKSERVER_IMAGE"

# Wait for MockServer to be healthy via Docker HEALTHCHECK (the image is
# distroless — no shell, no curl — so `docker exec ... curl` cannot work).
echo "--- Waiting for MockServer to become healthy..."
DEADLINE=$((SECONDS + 120))
READY=false
while [ $SECONDS -lt $DEADLINE ]; do
  STATUS=$(docker inspect -f '{{.State.Health.Status}}' "$MOCKSERVER_NAME" 2>/dev/null || echo "unknown")
  case "$STATUS" in
    healthy)
      echo "MockServer is healthy"
      READY=true
      break
      ;;
    unhealthy)
      echo "ERROR: MockServer container reported unhealthy"
      break
      ;;
  esac
  sleep 2
done

if [ "$READY" != "true" ]; then
  echo "ERROR: MockServer failed to become healthy within the deadline (status: ${STATUS})"
  echo "--- :docker: MockServer container logs"
  docker logs "$MOCKSERVER_NAME"
  exit 1
fi

"$SCRIPT_DIR/../run-in-docker.sh" \
  -i node:22 \
  -w /build/mockserver-client-node \
  --cache npm \
  -e "MOCKSERVER_HOST=$MOCKSERVER_NAME" \
  -e "MOCKSERVER_PORT=1080" \
  --network "$NETWORK_NAME" \
  `# Runs npm run test:coverage rather than spelling out the node --test command,` \
  `# so CI, \`npm test\` and \`npm run test:external\` all go through ONE definition.` \
  `# This step used to carry its own copy of the invocation, which is how it came` \
  `# to name 7 files while the suite had 15 -- class_callback, sre_slo_chaos,` \
  `# control_plane_auth_tls, drift, a2a, dispose and setup_mock_server never ran` \
  `# in CI and their failures were invisible. Discovery now lives in` \
  `# test/discover_test_files.js and the run in test/run_node_tests.js.` \
  `#` \
  `# test/run_node_tests.js fails on a \`not ok\` TAP line as well as on a non-zero` \
  `# exit: node --test reports "# fail 0" AND exits 0 when a suite throws while` \
  `# being constructed (a throw inside describe()), so an exit-code-only gate` \
  `# passes a run in which no test executed at all.` \
  `#` \
  `# Coverage thresholds re-measured against the FULL suite (2026-07): actual is` \
  `# lines 86.91 / branches 84.11 / functions 92.92, so the gate sits ~2-3 points` \
  `# under each. The old 68/72/74 were calibrated for a 7-file list and left so` \
  `# much slack that the gate could not fail — a larger suite must not come with` \
  `# a weaker threshold. Re-measure and raise these when the suite grows again.` \
  -- bash -c 'npm ci && npm run test:coverage'
