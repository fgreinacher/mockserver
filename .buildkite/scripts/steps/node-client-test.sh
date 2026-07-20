#!/usr/bin/env bash
# Run the mockserver-client-node functional tests (node:test) against a
# MockServer Docker container, mirroring the python/ruby integration test
# pattern.  The tests are parameterised via MOCKSERVER_HOST / MOCKSERVER_PORT
# environment variables.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NETWORK_NAME="mockserver-node-client-$$"
MOCKSERVER_NAME="mockserver-node-client-server-$$"

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
  mockserver/mockserver:snapshot

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
  `# Test files are discovered, NOT hand-listed. A hand-maintained list silently` \
  `# drops any test file added later: this step previously named 7 files while the` \
  `# suite had 15, so class_callback, sre_slo_chaos, control_plane_auth_tls, drift,` \
  `# a2a, dispose and setup_mock_server never ran in CI and their failures were` \
  `# invisible. Globbing keeps CI and \`npm test\` in lockstep by construction.` \
  `# Thresholds re-measured against the FULL 15-file suite (2026-07): actual is` \
  `# lines 86.91 / branches 84.11 / functions 92.92, so the gate sits ~2-3 points` \
  `# under each. The old 68/72/74 were calibrated for a 7-file list and left so` \
  `# much slack that the gate could not fail — a larger suite must not come with` \
  `# a weaker threshold. Re-measure and raise these when the suite grows again.` \
  -- bash -c 'npm ci && npx c8 --check-coverage --lines 84 --functions 90 --branches 80 node --test --test-force-exit --test-concurrency=1 test/no_proxy/*_test.js test/with_proxy/*_test.js'
