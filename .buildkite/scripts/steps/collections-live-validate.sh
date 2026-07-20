#!/usr/bin/env bash
set -euo pipefail

# Fires every example in the generated Postman collection at a LIVE MockServer and fails if any
# example body is rejected as malformed (400/415/500).
#
# WHY THIS EXISTS SEPARATELY FROM collections-validate.sh
# -------------------------------------------------------
# collections-validate.sh regenerates the collections and diffs them against the committed copies.
# That proves the generator is deterministic and the committed artifacts are current. It proves
# NOTHING about whether the documented examples actually work: an endpoint whose requestBody is
# `required: true` with no `example` generates a bodyless request, the committed collection contains
# that bodyless request, regeneration reproduces it exactly, and the gate is green — while every user
# who imports the collection and hits that endpoint gets a 400. That is not hypothetical; it shipped
# for `/mockserver/baseline/compare` and `/mockserver/pact/import` and was caught only by a reviewer
# running the examples by hand. A gate that can be green while the artifact it guards is broken is
# the failure mode this check closes.
#
# WHY IT DOES NOT USE run-in-docker.sh -s
# ---------------------------------------
# test_collections.py can start its own MockServer via `docker run`, which would need the Docker
# socket (-s) mounted into the python container. run-in-docker.sh ALWAYS withholds -s from PR builds
# by design, so that shape would silently degrade to "cannot start a server" on exactly the builds
# that most need checking — the same class of defect as the cloud-storage contract suites that
# skipped on 100% of CI builds because a step script omitted -s and reported green anyway.
#
# Instead the MockServer container is started HERE, by the step script running directly on the agent,
# and the python runs against it over the host network with --base-url. No socket is mounted, so this
# behaves identically on PR and mainline builds.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:snapshot}"
PORT="${MOCKSERVER_PORT:-11080}"

echo "=== starting MockServer ($IMAGE) on port $PORT ==="
CONTAINER_ID="$(docker run --rm -d -p "${PORT}:1080" "$IMAGE")"
cleanup() {
  echo ""
  echo "=== MockServer logs (tail) ==="
  docker logs --tail 50 "$CONTAINER_ID" 2>&1 || true
  docker rm -f "$CONTAINER_ID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Wait for readiness rather than sleeping a fixed interval: /mockserver/status answers as soon as the
# port binds, which is what we need before firing requests at it.
echo "=== waiting for MockServer to accept requests ==="
ready=0
for _ in $(seq 1 60); do
  if curl -fsS -X PUT "http://localhost:${PORT}/mockserver/status" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "FAIL: MockServer did not become ready within 60s — this is an infrastructure failure, NOT a"
  echo "      collection failure. Do not interpret it as the examples being valid or invalid."
  exit 1
fi

echo ""
echo "=== firing every generated example at the live server ==="
# Deliberately NOT `exec`: exec replaces this shell, which discards the `trap cleanup EXIT` above, so
# the MockServer container and host port would leak and the failure-log dump would never print. Run it
# as a child instead — `set -e` propagates a non-zero result (firing the trap on the way out), and a
# success falls through to the end of the script (trap still fires).
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i python:3.12 \
  --network host \
  -- bash -c "
    set -eu
    python3 scripts/collections/test_collections.py --base-url 'http://localhost:${PORT}'
  "
