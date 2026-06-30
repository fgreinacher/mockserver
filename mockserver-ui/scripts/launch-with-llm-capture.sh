#!/usr/bin/env bash
#
# launch-with-llm-capture.sh
# --------------------------
# One command to manually test LLM-traffic capture and its dashboard UX. Starts
# the MockServer backend (as an HTTPS proxy) + the UI dev server, then optionally
# drops you straight into a real coding-assistant CLI (claude / opencode / tabnine)
# already wired to proxy through MockServer with the proxy CA trusted — so every
# model call the tool makes shows up live in the Traffic, LLM Traces and LLM
# Optimise views.
#
# Sibling of launch-with-demo-data.sh: that one loads a rich SYNTHETIC dataset to
# screenshot every view; this one captures REAL traffic from real tools so you can
# exercise the genuine end-to-end UX. The UI is served from the vite dev server, so
# the latest dashboard code (e.g. provider detection) is live without rebuilding the
# jar; the jar is auto-(re)built only when a server-side Java source changed.
#
# Usage:
#   ./scripts/launch-with-llm-capture.sh [TOOL] [-- TOOL_ARGS...]
#   npm run capture -- [TOOL] [-- TOOL_ARGS...]
#
# TOOL (optional): claude | opencode | tabnine | none
#   Default 'none' — just starts the servers + prints the proxy env block so you
#   can launch any tool yourself (in this or another terminal). When a TOOL is
#   given it is launched interactively in the foreground with the proxy env set;
#   exit the tool (or Ctrl+C) to tear everything down.
#
# Options:
#   --rebuild        Force rebuild of the MockServer JAR even if one exists
#   --no-browser     Do not auto-open the dashboard
#   --keep-log       Do NOT clear the recorded request log on start (default: clear,
#                    so you see only your fresh session's traffic)
#   --port PORT      MockServer / proxy port (default: 1080)
#   --ui-port PORT   UI dev server port (default: 3000)
#   --ca PATH        Proxy CA cert the tool must trust (default: MockServer's repo test CA)
#   --help           Show this help
#
# Anything after a literal `--` is passed through to the launched tool.
# Press Ctrl+C (or exit the tool) to stop the servers.

set -euo pipefail

for cmd in java curl node npm; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: '$cmd' is required but not installed"; exit 1; }
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UI_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$UI_DIR/.." && pwd)"

MOCKSERVER_PORT=1080
UI_PORT=3000
REBUILD=false
NO_BROWSER=false
KEEP_LOG=false
CA_CERT="$REPO_ROOT/mockserver/mockserver-core/src/main/resources/org/mockserver/socket/CertificateAuthorityCertificate.pem"
TOOL="none"
TOOL_ARGS=()

# Parse: first non-flag token is the TOOL; everything after `--` is passed through.
while [[ $# -gt 0 ]]; do
  case "$1" in
    --rebuild) REBUILD=true; shift ;;
    --no-browser) NO_BROWSER=true; shift ;;
    --keep-log) KEEP_LOG=true; shift ;;
    --port) MOCKSERVER_PORT="$2"; shift 2 ;;
    --ui-port) UI_PORT="$2"; shift 2 ;;
    --ca) CA_CERT="$2"; shift 2 ;;
    --help|-h) sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    --) shift; TOOL_ARGS+=("$@"); break ;;
    claude|opencode|tabnine|none) TOOL="$1"; shift ;;
    *) echo "Unknown argument: $1 (use --help)"; exit 1 ;;
  esac
done

if [ "$TOOL" != "none" ] && ! command -v "$TOOL" >/dev/null 2>&1; then
  echo "ERROR: tool '$TOOL' is not installed / not on PATH."
  echo "       Install it, or run with no TOOL to just start the servers + print the env block."
  exit 1
fi
[ -f "$CA_CERT" ] || { echo "ERROR: CA cert not found: $CA_CERT (override with --ca)"; exit 1; }

echo "========================================"
echo "MockServer UI + live LLM capture"
echo "========================================"

# --- locate or build the runnable MockServer JAR (rebuild only if a server-side
#     Java source / pom is newer than the jar — UI edits are served live by vite) -----
find_jar() {
  local jar
  jar=$(ls -t "$REPO_ROOT"/mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar 2>/dev/null \
    | grep -Ev '(-sources|-javadoc|/original-)' | head -1)
  [ -n "$jar" ] && { echo "$jar"; return 0; } || return 1
}

NEED_BUILD=false; BUILD_REASON=""
if [ "$REBUILD" = true ]; then
  NEED_BUILD=true; BUILD_REASON="--rebuild requested"
elif ! find_jar >/dev/null; then
  NEED_BUILD=true; BUILD_REASON="no MockServer JAR found"
else
  STALE_SRC="$(find "$REPO_ROOT/mockserver" -not -path '*/target/*' \( \( -path '*/src/main/*' -name '*.java' \) -o -name 'pom.xml' \) -newer "$(find_jar)" -print 2>/dev/null | head -1)"
  if [ -n "$STALE_SRC" ]; then
    NEED_BUILD=true
    BUILD_REASON="JAR is stale — $(basename "$STALE_SRC") changed since it was built; rebuilding so server-side detection matches the checked-out code"
  fi
fi

if [ "$NEED_BUILD" = true ]; then
  BUILD_LOG="$UI_DIR/mockserver-build.log"
  echo "→ Building MockServer JAR ($BUILD_REASON)"
  echo "  (this can take a few minutes; full log: $BUILD_LOG)"
  set +e
  ( cd "$REPO_ROOT/mockserver" && ./mvnw clean install -DskipTests -pl mockserver-netty-no-dependencies -am ) 2>&1 \
    | tee "$BUILD_LOG" \
    | grep --line-buffered -E '\[INFO\] Building |\[INFO\] BUILD (SUCCESS|FAILURE)|\[ERROR\]'
  build_rc=${PIPESTATUS[0]}
  set -e
  if [ "$build_rc" -ne 0 ]; then
    echo "ERROR: MockServer build failed (exit $build_rc) — last 40 log lines:"; tail -40 "$BUILD_LOG"; exit 1
  fi
  echo "✓ Build complete"
fi
MOCKSERVER_JAR="$(find_jar)" || { echo "ERROR: MockServer JAR not found after build"; exit 1; }
echo "✓ MockServer JAR: $(basename "$MOCKSERVER_JAR")"

# --- install UI deps if needed --------------------------------------------
if [ ! -d "$UI_DIR/node_modules" ]; then
  echo "→ Installing UI dependencies..."; (cd "$UI_DIR" && npm install)
fi

# --- start MockServer (default built-in CA so NODE_EXTRA_CA_CERTS=repo CA is trusted) ---
MOCKSERVER_LOG="$UI_DIR/mockserver-capture.log"
DEMO_MAX_HEAP="${CAPTURE_MAX_HEAP:-1g}"
DEMO_MAX_LOG_ENTRIES="${CAPTURE_MAX_LOG_ENTRIES:-5000}"
echo "→ Starting MockServer (proxy) on port $MOCKSERVER_PORT (max heap: $DEMO_MAX_HEAP, log: $MOCKSERVER_LOG)..."
# forwardProxyHttp2Upgrade: forward the (HTTP/1.1) CLI's TLS-intercepted requests to the upstream over
# HTTP/2 via ALPN. Streaming SSE backends — notably the OpenAI Codex endpoint the opencode CLI uses —
# withhold the response head over HTTP/1.1 and only flush at completion, which surfaces as a streaming
# header timeout in the CLI; forwarding over HTTP/2 makes the head arrive immediately. ALPN falls back to
# HTTP/1.1 if the upstream does not offer HTTP/2, so this is safe for every provider.
java -Xmx"$DEMO_MAX_HEAP" -Dmockserver.maxLogEntries="$DEMO_MAX_LOG_ENTRIES" \
     -Dmockserver.metricsEnabled=true -Dmockserver.wasmEnabled=true \
     -Dmockserver.forwardProxyHttp2Upgrade=true \
     -jar "$MOCKSERVER_JAR" -serverPort "$MOCKSERVER_PORT" -logLevel INFO > "$MOCKSERVER_LOG" 2>&1 &
MOCKSERVER_PID=$!

UI_PID=""
cleanup() {
  echo ""
  echo "→ Stopping servers..."
  [ -n "${UI_PID:-}" ] && kill "$UI_PID" 2>/dev/null || true
  [ -n "${MOCKSERVER_PID:-}" ] && kill "$MOCKSERVER_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  echo "✓ Stopped"
}
trap cleanup INT TERM EXIT

wait_for() {
  local url="$1" name="$2" method="${3:-GET}" timeout=60 elapsed=0
  echo "  Waiting for $name..."
  until curl -sf -X "$method" "$url" >/dev/null 2>&1; do
    [ "$elapsed" -ge "$timeout" ] && { echo "ERROR: $name did not start within ${timeout}s"; return 1; }
    sleep 1; elapsed=$((elapsed + 1))
  done
}

wait_for "http://localhost:$MOCKSERVER_PORT/mockserver/status" "MockServer" PUT
echo "✓ MockServer ready (PID $MOCKSERVER_PID)"

if [ "$KEEP_LOG" = false ]; then
  curl -s -X PUT "http://localhost:$MOCKSERVER_PORT/mockserver/clear?type=LOG" \
    -H 'Content-Type: application/json' -d '{}' >/dev/null || true
fi

# --- start UI dev server (serves the latest dashboard code, proxied to MockServer) ---
echo "→ Starting UI dev server on port $UI_PORT..."
(cd "$UI_DIR" && MOCKSERVER_URL="http://localhost:$MOCKSERVER_PORT" npm run dev -- --port "$UI_PORT" >/dev/null 2>&1) &
UI_PID=$!
UI_URL="http://localhost:$UI_PORT/mockserver/dashboard/?port=$MOCKSERVER_PORT"
wait_for "http://localhost:$UI_PORT/mockserver/dashboard/" "UI dev server"
echo "✓ UI dev server ready (PID $UI_PID)"

if [ "$NO_BROWSER" = false ]; then
  if command -v open >/dev/null 2>&1; then open "$UI_URL"
  elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$UI_URL"; fi
fi

# --- proxy env every launched tool needs ----------------------------------
export HTTPS_PROXY="http://localhost:$MOCKSERVER_PORT" HTTP_PROXY="http://localhost:$MOCKSERVER_PORT"
export https_proxy="http://localhost:$MOCKSERVER_PORT" http_proxy="http://localhost:$MOCKSERVER_PORT"
export NODE_EXTRA_CA_CERTS="$CA_CERT" SSL_CERT_FILE="$CA_CERT" REQUESTS_CA_BUNDLE="$CA_CERT"
export NODE_USE_SYSTEM_CA=1 NODE_USE_ENV_PROXY=1

echo ""
echo "========================================"
echo "✓ Ready — watch traffic appear live"
echo "========================================"
echo "  Dashboard : $UI_URL"
echo "              (Traffic · LLM Traces · LLM Optimise tabs)"
echo "  MockServer log: $MOCKSERVER_LOG"
echo ""
echo "  To proxy a coding CLI through MockServer in any terminal, export:"
echo "    export HTTPS_PROXY=http://localhost:$MOCKSERVER_PORT"
echo "    export NODE_EXTRA_CA_CERTS=$CA_CERT"
echo "    export SSL_CERT_FILE=$CA_CERT"
echo "  then run:  claude   |   opencode   |   tabnine --skip-trust"
echo "========================================"
echo ""

if [ "$TOOL" = "none" ]; then
  echo "No tool selected — servers are running. Launch a CLI in another terminal with the"
  echo "env above, or re-run with a tool: npm run capture -- opencode"
  echo "Press Ctrl+C to stop."
  wait
else
  # tabnine refuses to run in an untrusted dir; default it to --skip-trust when the
  # caller passed no args of their own. (if/then, not `&&`, so set -e is not tripped.)
  if [ "$TOOL" = "tabnine" ] && [ "${#TOOL_ARGS[@]}" -eq 0 ]; then
    TOOL_ARGS=(--skip-trust)
  fi
  echo "→ Launching '$TOOL' through the proxy (interactive). Exit it — or press Ctrl+C — to stop the servers."
  echo ""
  # Foreground + interactive so you drive the real UX; the EXIT trap tears down the servers.
  # Guard the array expansion so it is safe on bash 3.2 (macOS) under `set -u`.
  if [ "${#TOOL_ARGS[@]}" -gt 0 ]; then
    "$TOOL" "${TOOL_ARGS[@]}" || true
  else
    "$TOOL" || true
  fi
fi
