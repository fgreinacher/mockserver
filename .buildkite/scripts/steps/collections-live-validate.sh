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
# and the python runs against it on a shared user-defined bridge network with --base-url. No socket is
# mounted, so this behaves identically on PR and mainline builds.
#
# WHY A USER-DEFINED BRIDGE NETWORK RATHER THAN HOST NETWORKING (--network=host)
# -----------------------------------------------------------------------------
# The infra Buildkite agents run a user-namespace-remapped Docker daemon, which rejects host networking
# ("cannot share the host's network namespace when user namespaces are enabled") and privileged
# containers — the same hazard AGENTS.md documents for the Ryuk reaper. A user-defined bridge network
# works under user-namespace remapping AND on a plain daemon, so it is correct in both environments.
# MockServer joins the bridge under a known NAME; the python checker joins the SAME bridge and reaches
# it as http://<name>:1080 via Docker's embedded DNS (container-name resolution only works on a
# user-defined bridge, not the default bridge). The host port publish is kept solely for the host-side
# readiness curl below, which runs directly on the agent, not in a container.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:snapshot}"
PORT="${MOCKSERVER_PORT:-11080}"
NET="collections-net-$$"
CONTAINER_NAME="mockserver-live-$$"

# Install the cleanup trap BEFORE the fallible `docker run` (and right after the network exists), not
# after: under `set -e` a failing `docker run` — image pull failure, host port already bound, a daemon
# rejection, exactly the infra-failure class this step keeps hitting — aborts the script on the
# assignment below, so a trap installed afterwards would never run and the freshly-created bridge
# network would leak. Leaked user-defined bridges exhaust Docker's address pool and eventually brick
# the agent's daemon. CONTAINER_ID starts empty and the cleanup guards on it so the trap is safe under
# `set -u` even when it fires before the container is created.
echo "=== creating bridge network ($NET) ==="
docker network create "$NET" >/dev/null

CONTAINER_ID=""
cleanup() {
  if [ -n "$CONTAINER_ID" ]; then
    echo ""
    echo "=== MockServer logs (tail) ==="
    docker logs --tail 50 "$CONTAINER_ID" 2>&1 || true
    docker rm -f "$CONTAINER_ID" >/dev/null 2>&1 || true
  fi
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "=== starting MockServer ($IMAGE) as $CONTAINER_NAME on $NET, host port $PORT ==="
CONTAINER_ID="$(docker run --rm -d --name "$CONTAINER_NAME" --network "$NET" -p "${PORT}:1080" "$IMAGE")"

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
  --network "$NET" \
  -- bash -c "
    set -eu
    python3 scripts/collections/test_collections.py --base-url 'http://${CONTAINER_NAME}:1080'
  "
