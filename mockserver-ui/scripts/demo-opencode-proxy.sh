#!/usr/bin/env bash
#
# demo-opencode-proxy.sh
# ----------------------
# OPTIONAL companion to `npm run demo`. Generates REAL, non-deterministic LLM
# traffic by running OpenCode (https://opencode.ai) headless with its HTTPS
# traffic proxied through MockServer, so MockServer captures the actual LLM API
# calls. You can then open the dashboard Optimise tab — or curl the optimisation
# report — to see cost-optimisation signals computed from your own agent run.
#
# This is DELIBERATELY NOT part of `npm run demo`: the default demo is offline
# and deterministic (mocked traffic). This script needs network access and YOUR
# OWN LLM API key, and produces traffic that varies run to run.
#
# What it does:
#   1. Starts MockServer (Docker) as an HTTPS forward proxy with a dynamically
#      generated, machine-local CA (so no published private key is trusted).
#   2. Extracts that CA certificate to a local file.
#   3. Prints the exact environment to run OpenCode headless through the proxy
#      (HTTPS_PROXY + NODE_EXTRA_CA_CERTS / SSL_CERT_FILE), and — if you pass a
#      prompt — runs `opencode run` for you.
#   4. Tells you how to view the captured traffic and the optimisation report.
#
# Requirements: docker, curl. For step 3: opencode on your PATH and a provider
# API key exported in your shell (e.g. OPENAI_API_KEY / ANTHROPIC_API_KEY).
# This script NEVER hardcodes or logs your API key.
#
# Usage:
#   ./scripts/demo-opencode-proxy.sh                 # start proxy + print run instructions
#   ./scripts/demo-opencode-proxy.sh "refactor the foo() function and add a test"
#                                                    # start proxy + run OpenCode with that prompt
#   ./scripts/demo-opencode-proxy.sh --port 1080 "summarise the README"
#   ./scripts/demo-opencode-proxy.sh --stop          # stop and remove the proxy container
#   ./scripts/demo-opencode-proxy.sh --help
#
# Press Ctrl+C (or run with --stop) to tear down the proxy.

set -euo pipefail

PORT=1080
CONTAINER="mockserver-opencode-proxy"
IMAGE="mockserver/mockserver:latest"
CA_FILE="${TMPDIR:-/tmp}/mockserver-opencode-ca.pem"
PROMPT=""
STOP_ONLY=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --stop) STOP_ONLY=true; shift ;;
    --help|-h) sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "Unknown option: $1 (use --help)"; exit 1 ;;
    *) PROMPT="$1"; shift ;;
  esac
done

command -v docker >/dev/null 2>&1 || { echo "ERROR: 'docker' is required"; exit 1; }
command -v curl   >/dev/null 2>&1 || { echo "ERROR: 'curl' is required"; exit 1; }

stop_proxy() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }

if [ "$STOP_ONLY" = true ]; then
  echo "→ Stopping proxy container '$CONTAINER'..."
  stop_proxy
  echo "✓ Stopped"
  exit 0
fi

echo "========================================"
echo "MockServer ⟷ OpenCode real-traffic proxy"
echo "========================================"
echo "NOTE: this generates REAL, non-deterministic LLM traffic and needs network"
echo "      access + your own provider API key. It is NOT run by 'npm run demo'."
echo ""

# --- start MockServer as an HTTPS proxy with a machine-local dynamic CA -------
# dynamicallyCreateCertificateAuthorityCertificate=true generates a unique CA on
# this machine (the default CA's private key is public — never trust it for real
# traffic). directoryToSaveDynamicSSLCertificate is where the CA is written.
echo "→ Starting MockServer proxy on port $PORT (Docker container: $CONTAINER)..."
stop_proxy
docker run -d --name "$CONTAINER" -p "$PORT:$PORT" "$IMAGE" \
  -serverPort "$PORT" -logLevel WARN \
  -Dmockserver.dynamicallyCreateCertificateAuthorityCertificate=true \
  -Dmockserver.directoryToSaveDynamicSSLCertificate=/dynamic-certs >/dev/null

trap 'echo; echo "→ Stopping proxy..."; stop_proxy; echo "✓ Stopped"' INT TERM EXIT

# Wait for the control plane to answer (PUT /mockserver/status).
elapsed=0
until curl -sf -X PUT "http://localhost:$PORT/mockserver/status" >/dev/null 2>&1; do
  [ "$elapsed" -ge 60 ] && { echo "ERROR: MockServer did not start within 60s"; docker logs "$CONTAINER" 2>&1 | tail -20; exit 1; }
  sleep 1; elapsed=$((elapsed + 1))
done
echo "✓ MockServer proxy ready"

# --- extract the dynamically generated CA certificate ------------------------
# A TLS handshake to the proxy triggers CA generation; then copy it out of the
# container. (The dynamic CA's private key never leaves your machine.)
curl -sk "https://localhost:$PORT/" >/dev/null 2>&1 || true
echo "→ Extracting the machine-local CA certificate → $CA_FILE"
ca_elapsed=0
until docker exec "$CONTAINER" cat /dynamic-certs/CertificateAuthorityCertificate.pem > "$CA_FILE" 2>/dev/null && [ -s "$CA_FILE" ]; do
  [ "$ca_elapsed" -ge 20 ] && { echo "ERROR: could not extract the dynamic CA certificate"; exit 1; }
  sleep 1; ca_elapsed=$((ca_elapsed + 1))
done
echo "✓ CA certificate saved ($(wc -c < "$CA_FILE" | tr -d ' ') bytes)"

# --- the environment OpenCode needs to route + trust the proxy ---------------
# OpenCode is a Node.js tool, so NODE_EXTRA_CA_CERTS makes Node trust the CA;
# SSL_CERT_FILE covers any Python/other helpers. HTTPS_PROXY routes the calls.
export HTTPS_PROXY="http://localhost:$PORT"
export HTTP_PROXY="http://localhost:$PORT"
export NODE_EXTRA_CA_CERTS="$CA_FILE"
export SSL_CERT_FILE="$CA_FILE"

echo ""
echo "→ Proxy environment (exported for this script; copy these to run OpenCode yourself):"
echo "    export HTTPS_PROXY=$HTTPS_PROXY"
echo "    export HTTP_PROXY=$HTTP_PROXY"
echo "    export NODE_EXTRA_CA_CERTS=$NODE_EXTRA_CA_CERTS   # Node tools (OpenCode)"
echo "    export SSL_CERT_FILE=$SSL_CERT_FILE               # Python helpers"
echo ""
echo "  Point OpenCode at a REAL provider and supply YOUR OWN API key, e.g.:"
echo "    export OPENAI_API_KEY=sk-...        # or ANTHROPIC_API_KEY=sk-ant-..."
echo "  (This script never reads, stores, or logs your API key.)"
echo ""

# --- run OpenCode (only if a prompt was supplied) ----------------------------
if [ -n "$PROMPT" ]; then
  if ! command -v opencode >/dev/null 2>&1; then
    echo "ERROR: a prompt was supplied but 'opencode' is not on your PATH."
    echo "       Install it from https://opencode.ai, then re-run, or run it yourself"
    echo "       in another shell with the environment printed above."
    exit 1
  fi
  echo "→ Running OpenCode headless through the proxy:"
  echo "    opencode run \"$PROMPT\""
  echo "  (its LLM calls are now captured by MockServer)"
  echo ""
  opencode run "$PROMPT" || echo "(opencode exited non-zero — its captured traffic is still available below)"
else
  echo "→ No prompt supplied. In ANOTHER shell, export the variables above, then run"
  echo "  OpenCode headless, for example:"
  echo "    opencode run \"summarise the README and suggest one improvement\""
fi

echo ""
echo "========================================"
echo "View the captured REAL traffic + optimisation report"
echo "========================================"
echo "  Dashboard Optimise tab:"
echo "    http://localhost:$PORT/mockserver/dashboard  → Optimise"
echo "  Or curl the report directly:"
echo "    curl -s \"http://localhost:$PORT/mockserver/llm/optimisationReport?format=markdown\""
echo "    curl -s \"http://localhost:$PORT/mockserver/llm/optimisationReport?format=json\" | python3 -m json.tool"
echo ""
echo "  Press Ctrl+C to stop the proxy (or: $0 --stop)."

# Keep the proxy alive so you can run OpenCode in another shell and inspect the
# report, unless we already ran a one-shot prompt above.
if [ -z "$PROMPT" ]; then
  echo "  Proxy is running; waiting… (Ctrl+C to stop)"
  while grep -q "^${CONTAINER}$" <<<"$(docker ps --format '{{.Names}}' || true)"; do sleep 2; done
fi
