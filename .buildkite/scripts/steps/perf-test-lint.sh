#!/usr/bin/env bash
set -euo pipefail

# Lint the k6 performance harness:
#   1. syntax-check the shell run scripts (bash on the agent), and
#   2. validate every k6 entry script in a pinned grafana/k6 container —
#      `k6 inspect` parses the JS, resolves the lib/ imports, and validates the
#      options/scenarios/thresholds without running any load.
#
# Reproduce locally: run this script from the repo root (needs docker).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
PERF_DIR="$REPO_ROOT/mockserver-performance-test"

echo "--- syntax-checking run scripts"
for s in scripts/runMockServer.sh scripts/runK6.sh scripts/runAll.sh; do
  echo "bash -n $s"
  bash -n "$PERF_DIR/$s"
done

echo "--- byte-compiling the SSE fidelity reader (item 12)"
# The reader is pure-stdlib python3 run on the perf agent by perf-test-run.sh; a
# syntax error would only surface mid-run, so compile it here. Skip (do not fail)
# if python3 is absent on the lint agent — the run step guards on it too.
if command -v python3 >/dev/null 2>&1; then
  python3 -m py_compile "$PERF_DIR/k6/tools/sse-fidelity-reader.py"
  echo "python3 -m py_compile k6/tools/sse-fidelity-reader.py OK"
else
  echo "python3 absent — skipping reader byte-compile (run step guards on it)"
fi

echo "--- validating k6 scripts (k6 inspect)"
# The single-quoted -c body is expanded by the container's sh, not the host —
# $f must NOT expand here, so SC2016 is intentional.
# shellcheck disable=SC2016
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f \
  --entrypoint sh \
  -w /build/mockserver-performance-test \
  -- -c 'set -e; for f in k6/smoke.js k6/load.js k6/stress.js k6/soak.js k6/regression.js k6/growth.js k6/sweep.js k6/forward.js k6/proxy.js k6/streaming.js k6/clustered_crossing.js; do echo "k6 inspect $f"; k6 inspect "$f" > /dev/null; done'
