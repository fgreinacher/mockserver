#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Run the mockserver-testcontainers Node suite against a real MockServer
# container started via Testcontainers.
#
# WHY THIS STEP ASSERTS EXPLICITLY (fail closed)
#
#   The integration test (tests/integration) starts a real mockserver/mockserver
#   container and hits it over HTTP, but it returns EARLY — a green Jest pass —
#   when Docker is unavailable (matching the repo's assumeTrue(isDockerAvailable())
#   convention). That graceful skip is correct off-CI, but in CI a skip that reads
#   as green is exactly the silent false positive the Java/Go/.NET/Rust
#   testcontainer steps exist to remove: `npm test` alone can exit 0 having started
#   NO container.
#
#   So this step mounts the Docker socket, runs the integration suite, captures its
#   output, and greps for the NODE_TESTCONTAINERS_STATUS_OK marker the test prints
#   only after a real container answered PUT /mockserver/status with 200. If the
#   marker is absent — the test was filtered out, renamed, or Docker was unusable —
#   the step fails loudly instead of passing green.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Testcontainers tests require Docker — mount the socket.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i node:20 \
  -w /build/mockserver-testcontainers/node \
  --docker-socket \
  --cache npm \
  -- bash -ec '
    npm ci
    npm run build

    # Fast, Docker-free unit tests (URL/port shaping, config).
    npm run test:unit

    # Container-start integration suite: starts a real MockServer container and
    # hits it over HTTP. Capture output WITHOUT letting a non-zero exit abort the
    # script (set +e around the substitution) so the output is always echoed and
    # the evidence-marker guard below always runs, rather than trusting Jest'"'"'s
    # exit code (the suite returns early — a green pass — when Docker is
    # unavailable).
    set +e
    out="$(npm run test:integration 2>&1)"
    rc=$?
    set -e
    echo "$out"

    if ! echo "$out" | grep -q "NODE_TESTCONTAINERS_STATUS_OK"; then
      echo "+++ :bangbang: node container-start test did not emit its status marker — no real container was exercised, failing closed" >&2
      exit 1
    fi
    if [ "$rc" -ne 0 ]; then
      echo "+++ :bangbang: node integration suite exited ${rc} — failing" >&2
      exit "$rc"
    fi
  '
