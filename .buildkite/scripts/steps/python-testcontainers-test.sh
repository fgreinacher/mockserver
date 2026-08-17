#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Run the mockserver-testcontainers Python suite against a real MockServer
# container started via Testcontainers.
#
# WHY THIS STEP ASSERTS EXPLICITLY (fail closed)
#
#   The @pytest.mark.docker integration tests start a real mockserver/mockserver
#   container and hit it over HTTP, but the fixture calls pytest.skip when Docker
#   is unavailable (matching the repo's assumeTrue(isDockerAvailable()) convention).
#   That graceful skip is correct off-CI, but in CI a skip that reads as green is
#   exactly the silent false positive the Java/Go/.NET/Rust testcontainer steps
#   exist to remove: the previous `pytest -m "not docker"` deselected every
#   container test, so the job passed having started NO container.
#
#   So this step mounts the Docker socket (-s), runs the WHOLE suite (config tests
#   plus the docker-marked integration tests) with output capture disabled so the
#   test's print reaches stdout, then greps for the PYTHON_TESTCONTAINERS_STATUS_OK
#   marker the test prints only after a real container answered PUT
#   /mockserver/status with 200. If the marker is absent — the tests were
#   deselected, renamed, or Docker was unusable — the step fails loudly instead of
#   passing green.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Testcontainers tests require Docker — mount the socket (-s).
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i python:3.12 \
  -w /build/mockserver-testcontainers/python \
  -s \
  --cache pip \
  -- bash -ec '
    pip install -e ".[test]"

    # Resolve an address this sibling container can use to reach the MockServer
    # container'"'"'s *published* port. testcontainers-python otherwise resolves it
    # to "localhost" — docker-py rewrites the bind-mounted unix socket to an
    # http+docker://localhost base_url, so testcontainers'"'"' inside-container
    # gateway fallback never fires — and "localhost" is not the Docker host from
    # here (Connection refused). The node/go/.NET Testcontainers libraries auto-
    # resolve this; testcontainers-python needs TC_HOST set explicitly.
    #
    #   * Prefer host.docker.internal: Docker Desktop (macOS/Windows) injects it and
    #     it reaches published ports there, where the docker0 gateway IP does NOT.
    #   * Fall back to the default-route gateway (the bridge host IP) on a plain
    #     Linux CI daemon, where host.docker.internal is absent but the gateway
    #     reaches published ports. Parsed from /proc/net/route (little-endian hex)
    #     to avoid depending on an ip(8) binary the image may not ship.
    if getent hosts host.docker.internal >/dev/null 2>&1; then
      export TC_HOST=host.docker.internal
    else
      gw_hex="$(awk "\$2==\"00000000\"{print \$3; exit}" /proc/net/route)"
      if [ -n "$gw_hex" ]; then
        export TC_HOST="$(printf "%d.%d.%d.%d" "0x${gw_hex:6:2}" "0x${gw_hex:4:2}" "0x${gw_hex:2:2}" "0x${gw_hex:0:2}")"
      fi
    fi
    echo "Resolved Docker host for Testcontainers TC_HOST=${TC_HOST:-<unset>}"

    # Disable the Testcontainers Ryuk reaper. With the socket bind-mounted into a
    # sibling container, testcontainers-python starts Ryuk and then tries to open a
    # TCP control socket to its mapped port across the bridge — which is refused
    # here, aborting every container start (testcontainers-node omits Ryuk entirely,
    # which is why the node step needs no equivalent). Ryuk only reaps orphaned
    # containers as a safety net; the integration tests stop their container via a
    # `with MockServerContainer() as ...:` context manager and the CI agent is
    # ephemeral, so disabling it is safe here.
    export TESTCONTAINERS_RYUK_DISABLED=true

    # Run the full suite: Docker-free config tests plus the @pytest.mark.docker
    # container-start tests. --capture=no lets the test print reach stdout. Capture
    # WITHOUT letting a non-zero exit abort the script (set +e around the
    # substitution) so the output is always echoed and the evidence-marker guard
    # below always runs, rather than trusting pytest'"'"'s exit code (the fixture
    # skips — a green pass — when Docker is unavailable).
    set +e
    out="$(pytest -v --capture=no 2>&1)"
    rc=$?
    set -e
    echo "$out"

    if ! echo "$out" | grep -q "PYTHON_TESTCONTAINERS_STATUS_OK"; then
      echo "+++ :bangbang: python container-start test did not emit its status marker — no real container was exercised, failing closed" >&2
      exit 1
    fi
    if [ "$rc" -ne 0 ]; then
      echo "+++ :bangbang: python suite exited ${rc} — failing" >&2
      exit "$rc"
    fi
  '
