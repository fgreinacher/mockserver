#!/usr/bin/env bash
#
# One-shot: capture a documentation screenshot of every MockServer dashboard tab,
# then tear the demo down. Intended for refreshing the website screenshots after
# UI changes.
#
#   bash scripts/capture-docs-screenshots.sh
#
# It runs the demo in TWO phases, because load injection and the screenshots want
# opposite things:
#
#   Phase 1 — content tabs, demo WITHOUT load injection.
#     The dashboard keeps only the most recent ~100 traffic items, so a load
#     scenario firing thousands of requests/sec evicts the seeded LLM
#     conversations (and can saturate the WebSocket so panels never fill). Run
#     the content tabs against a quiet demo so Traffic/Sessions/etc. show the rich
#     seeded data.
#
#   Phase 2 — chart tabs (Metrics, Performance), demo WITH load injection.
#     These tabs need live, sustained throughput to draw non-empty time-series.
#     A short warm-up lets the charts accumulate a few samples first.
#
# Capture is configurable via the env vars documented in
# capture-dashboard-screenshots.mjs (OUT_DIR=, THEME=, FULL_PAGE=, SCALE=, ...).
# Set SKIP_CHARTS=true to run only phase 1, or CHART_WARMUP_S=N to change the
# phase-2 warm-up (default 90s).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UI_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

UI_PORT="${UI_PORT:-3000}"
MS_PORT="${MS_PORT:-1080}"
DEMO_TIMEOUT="${DEMO_TIMEOUT:-300}"
CHART_WARMUP_S="${CHART_WARMUP_S:-90}"
SKIP_CHARTS="${SKIP_CHARTS:-false}"

CONTENT_TABS="get-started,dashboard,traffic,breakpoints,composer,chaos,optimise,mcp-health,async,grpc,sessions,library,drift,verification,contract,slo,cluster"

LOG_FILE="$(mktemp -t mockserver-demo-screenshots.XXXXXX.log)"
DEMO_PID=""

stop_demo() {
  if [ -n "$DEMO_PID" ] && kill -0 "$DEMO_PID" 2>/dev/null; then
    kill -- -"$DEMO_PID" 2>/dev/null || kill "$DEMO_PID" 2>/dev/null || true
    wait "$DEMO_PID" 2>/dev/null || true
  fi
  DEMO_PID=""
  # Belt-and-braces: free the ports for the next phase.
  pkill -f "mockserver-netty-no-dependencies" 2>/dev/null || true
  pkill -f "vite.*--port ${UI_PORT}" 2>/dev/null || true
  docker rm -f mockserver-demo-mqtt >/dev/null 2>&1 || true
  # Wait until the ports are actually free before the next phase launches — relaunching
  # while a previous MockServer/Vite is still dying causes the demo to abort on a port race.
  local waited=0
  while { lsof -ti "tcp:${MS_PORT}" >/dev/null 2>&1 || lsof -ti "tcp:${UI_PORT}" >/dev/null 2>&1; } && [ "$waited" -lt 30 ]; do
    sleep 1; waited=$((waited + 1))
  done
}
cleanup() { stop_demo; rm -f "$LOG_FILE"; }
trap cleanup EXIT INT TERM

# start_demo <extra demo flags...> — launch the demo in its own process group and
# block until it prints the ready line.
start_demo() {
  : > "$LOG_FILE"
  echo "→ Launching demo: npm run demo -- --no-browser --ui-port $UI_PORT --port $MS_PORT $*"
  set -m
  ( cd "$UI_DIR" && npm run demo -- --no-browser --ui-port "$UI_PORT" --port "$MS_PORT" "$@" ) \
    >"$LOG_FILE" 2>&1 &
  DEMO_PID=$!
  set +m

  local elapsed=0
  until grep -q "Ready — populated demo environment" "$LOG_FILE" 2>/dev/null; do
    if ! kill -0 "$DEMO_PID" 2>/dev/null; then
      echo "ERROR: demo exited before becoming ready. Last 40 log lines:" >&2; tail -40 "$LOG_FILE" >&2 || true; exit 1
    fi
    if [ "$elapsed" -ge "$DEMO_TIMEOUT" ]; then
      echo "ERROR: demo not ready within ${DEMO_TIMEOUT}s. Last 40 log lines:" >&2; tail -40 "$LOG_FILE" >&2 || true; exit 1
    fi
    sleep 2; elapsed=$((elapsed + 2))
  done
  echo "✓ Demo ready after ${elapsed}s"
}

capture() {  # capture <ONLY-list>
  ( cd "$UI_DIR" && ONLY="$1" UI_PORT="$UI_PORT" MS_PORT="$MS_PORT" node scripts/capture-dashboard-screenshots.mjs )
}

echo "=== Phase 1/2 — content tabs (no load injection) ==="
start_demo --with-broker
capture "$CONTENT_TABS"
stop_demo

if [ "$SKIP_CHARTS" = "true" ]; then
  echo "✓ Done (SKIP_CHARTS=true — chart tabs skipped)."
  exit 0
fi

echo "=== Phase 2/2 — load-injection tabs ==="
start_demo --with-broker --with-load-injection
# Performance FIRST, while the demo's load scenario is still ramping: at sustained
# peak the control plane is busy generating load and the status endpoint the panel
# polls is starved, so it falls back to the empty create form. Early = live charts.
echo "→ Capturing Performance early (during ramp) so the running scenario shows..."
capture "performance"
# Then let the time-series accumulate and capture Metrics at full load.
echo "→ Warming up charts for ${CHART_WARMUP_S}s so the time-series have data..."
sleep "$CHART_WARMUP_S"
capture "metrics"
stop_demo

echo "✓ Done."
