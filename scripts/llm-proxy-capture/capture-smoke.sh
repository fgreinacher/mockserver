#!/usr/bin/env bash
#
# capture-smoke.sh — LOCAL-ONLY smoke test for LLM-traffic capture via MockServer.
#
# Proves end-to-end that MockServer, used as an HTTPS proxy, both RECORDS and
# CLASSIFIES the LLM traffic of real coding-assistant CLIs — so the captured calls
# show up in the dashboard's Traffic view, the LLM Traces view, and the LLM Optimise
# view (the optimisation report).
#
# It drives whichever of the supported CLIs are installed AND already authenticated
# on this machine, sending each a LADDER of prompts through the proxy, then asserts,
# per (prompt, tool):
#   1. CAPTURE     — the tool's LLM endpoint appears in the recorded request log
#                    (proves the proxy + TLS interception works).
#   2. CLASSIFY    — that same call appears in the LLM optimisation report with the
#                    expected provider (proves it will render in Traces / Optimise).
# and REPORTS (does not gate on, unless CAPTURE_FAIL_ON_TIMEOUT=1):
#   3. TIMING      — wall-clock duration + the upstream response time, and whether the
#                    tool emitted any "timed out"/"retry" messages — so a SLOW response
#                    (reproducing the opencode header-timeout on complex questions) is
#                    visible, not hidden.
#
# The prompt ladder (CAPTURE_PROMPTS) covers the range you see by hand:
#   simple      — a one-word reply: the provider responds quickly (~seconds).
#   reasoning   — a heavy multi-step proof: the provider "thinks" for a long time
#                 before sending response headers, which is what makes opencode's
#                 10s header-timeout fire and retry on complex prompts.
#   multimodal  — a text+image question (an image is generated at run time and
#                 attached) so a real mixed-model request is captured. Image input is
#                 best-effort per tool: opencode via `-f`, claude/tabnine via a path
#                 reference in the prompt — a tool that ignores it still sends a text
#                 request, which is still captured.
#
# Supported tools and the LLM endpoint each is matched by:
#   claude   (Claude Code) — POST api.anthropic.com/v1/messages          -> ANTHROPIC
#   opencode (Codex)       — POST chatgpt.com/backend-api/codex/responses -> OPENAI_RESPONSES
#   tabnine  (Gemini fork) — POST <host>/.../chat/completions            -> OPENAI
#
# WHY LOCAL-ONLY: it invokes real, interactively-authenticated CLIs and makes real
# (small) calls to live model providers / your org's gateway. It therefore cannot
# run on CI and is skipped there. The CI-safe equivalent is the fixture-driven
# CodingCliLlmCaptureTest (mockserver-core) + llmTraffic.test.ts (mockserver-ui),
# which exercise the SAME detection without any network or credentials.
#
# NO SECRETS: this script contains no API keys and hard-codes no private hosts.
# The CLIs use their own stored credentials; tabnine's gateway host is whatever the
# tool itself is configured with (matched by path, never named here). The default CA
# is MockServer's public test Certificate Authority shipped in this repo.
#
# Usage:
#   scripts/llm-proxy-capture/capture-smoke.sh
#
# Environment overrides:
#   MOCKSERVER_URL    base URL of a RUNNING MockServer proxy (default http://localhost:1080)
#   MOCKSERVER_CA     path to the proxy CA cert (default: repo test CA, see below)
#   CAPTURE_PROMPTS   space-separated ladder to run (default: "simple reasoning multimodal";
#                     each runs against every installed tool — real model calls, so it costs
#                     quota; subset it, e.g. CAPTURE_PROMPTS="simple" for a quick check)
#   CAPTURE_PROMPT    legacy: if set, run ONLY this one custom (simple/text) prompt instead
#   CAPTURE_TIMEOUT   per-tool, per-prompt timeout in seconds (default 180)
#   CAPTURE_TOOLS     space-separated subset to consider (default: claude opencode tabnine)
#   CAPTURE_FAIL_ON_TIMEOUT  set to 1 to FAIL the run if any prompt timed out / retried
#                            (default 0: timeouts are reported but do not fail the gate)
#   FORCE             set to 1 to run even when a CI environment is detected
#
# Tool startup overhead (a tool's own MCP servers, plugins, retries) can dominate the
# wall-clock DUR — that is the TOOL's overhead, not the proxy's. The report's DUR-vs-UPSTREAM
# NOTE makes that explicit; to speed a tool up, fix/disable its MCP servers in its own config.
#
set -uo pipefail

# --- locate repo + defaults --------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

MOCKSERVER_URL="${MOCKSERVER_URL:-http://localhost:1080}"
MOCKSERVER_CA="${MOCKSERVER_CA:-$REPO_ROOT/mockserver/mockserver-core/src/main/resources/org/mockserver/socket/CertificateAuthorityCertificate.pem}"
CAPTURE_TIMEOUT="${CAPTURE_TIMEOUT:-180}"
CAPTURE_TOOLS="${CAPTURE_TOOLS:-claude opencode tabnine}"
CAPTURE_FAIL_ON_TIMEOUT="${CAPTURE_FAIL_ON_TIMEOUT:-0}"

note()  { printf '\033[36m[capture]\033[0m %s\n' "$*"; }
ok()    { printf '\033[32m[ ok  ]\033[0m %s\n' "$*"; }
warn()  { printf '\033[33m[warn ]\033[0m %s\n' "$*"; }
fail()  { printf '\033[31m[fail ]\033[0m %s\n' "$*"; }

# --- refuse to run on CI unless forced ---------------------------------------
if [ "${FORCE:-}" != "1" ] && { [ -n "${CI:-}" ] || [ -n "${BUILDKITE:-}" ] || [ -n "${GITHUB_ACTIONS:-}" ]; }; then
  warn "CI environment detected — this is a LOCAL-ONLY test (drives real authenticated CLIs)."
  warn "The CI-safe coverage is CodingCliLlmCaptureTest + llmTraffic.test.ts. Set FORCE=1 to override."
  exit 0
fi

# --- portable timeout (no coreutils 'timeout' on stock macOS) ----------------
run_with_timeout() {
  local secs="$1"; shift
  "$@" &
  local pid=$!
  ( sleep "$secs"; kill "$pid" 2>/dev/null ) &
  local watcher=$!
  wait "$pid" 2>/dev/null
  local rc=$?
  kill "$watcher" 2>/dev/null
  wait "$watcher" 2>/dev/null
  return $rc
}

# --- preconditions -----------------------------------------------------------
command -v curl >/dev/null    || { fail "curl not found"; exit 2; }
command -v python3 >/dev/null || { fail "python3 not found"; exit 2; }
[ -f "$MOCKSERVER_CA" ] || { fail "CA cert not found: $MOCKSERVER_CA"; exit 2; }

ms() { curl -s -X PUT "$MOCKSERVER_URL$1" -H 'Content-Type: application/json' --data "${2:-{}}"; }

note "MockServer proxy : $MOCKSERVER_URL"
note "CA cert          : $MOCKSERVER_CA"
if ! ms "/mockserver/retrieve?type=ACTIVE_EXPECTATIONS" >/dev/null 2>&1; then
  fail "Cannot reach MockServer at $MOCKSERVER_URL."
  cat <<EOF
Start it first, e.g.:
  java -jar mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar \\
       -serverPort 1080 -logLevel INFO
Then open the dashboard at $MOCKSERVER_URL/mockserver/dashboard
EOF
  exit 2
fi
ok "MockServer reachable"

note "Clearing recorded log so this run starts clean…"
ms "/mockserver/clear?type=LOG" >/dev/null

# --- proxy env exported to every CLI ----------------------------------------
export HTTPS_PROXY="$MOCKSERVER_URL"  HTTP_PROXY="$MOCKSERVER_URL"
export https_proxy="$MOCKSERVER_URL"  http_proxy="$MOCKSERVER_URL"
export NODE_EXTRA_CA_CERTS="$MOCKSERVER_CA"      # node CLIs (opencode, tabnine)
export SSL_CERT_FILE="$MOCKSERVER_CA"            # curl/openssl-based clients
export REQUESTS_CA_BUNDLE="$MOCKSERVER_CA"       # python-based clients
export NODE_USE_SYSTEM_CA=1 NODE_USE_ENV_PROXY=1 # tabnine honours these

# --- shared workspace (image + per-prompt captures live here) ----------------
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# NOTE on tool working dir / MCP: the CLIs run in the invocation directory and load
# their normal (project + global) config, including any MCP servers. We deliberately do
# NOT relocate them to a clean dir or force-disable MCP — opencode in particular needs a
# real project context to make its codex call (a clean dir made it no-op without
# capturing anything). A tool's own MCP startup can still add tens of seconds; that is
# the tool's overhead, not the proxy's — the report's DUR-vs-UPSTREAM NOTE makes that
# explicit rather than hiding it. (If you want a tool to start faster, fix/disable its MCP
# servers in the tool's own config.)

# --- prompt ladder: simple (fast) -> reasoning (slow) -> multimodal (text+image) ---
SIMPLE_PROMPT="Reply with exactly the single word: hello"
REASONING_PROMPT="Reason carefully and step by step, showing all working and skipping nothing: prove that there are infinitely many prime numbers using THREE distinct proofs (Euclid's classic proof, Euler's product/divergence argument, and the Fermat-number coprimality argument). Then compare the three proofs by the assumptions each requires and their relative elegance."
MULTIMODAL_PROMPT="Describe what is shown in this image in one short sentence, including the colours you see."

# Build the list of prompt entries: "label|kind|wantimage(0|1)|prompt".
PROMPT_ENTRIES=()
if [ -n "${CAPTURE_PROMPT:-}" ]; then
  PROMPT_ENTRIES+=("custom|text|0|$CAPTURE_PROMPT")
else
  for sel in ${CAPTURE_PROMPTS:-simple reasoning multimodal}; do
    case "$sel" in
      simple)     PROMPT_ENTRIES+=("simple|text|0|$SIMPLE_PROMPT") ;;
      reasoning)  PROMPT_ENTRIES+=("reasoning|text-heavy|0|$REASONING_PROMPT") ;;
      multimodal) PROMPT_ENTRIES+=("multimodal|text+image|1|$MULTIMODAL_PROMPT") ;;
      *) warn "unknown prompt set '$sel' — skipping" ;;
    esac
  done
fi
[ "${#PROMPT_ENTRIES[@]}" -gt 0 ] || { fail "no prompts selected (check CAPTURE_PROMPTS)"; exit 2; }

# Generate the multimodal test image (three colour stripes: red, green, blue) at run
# time so no binary blob is committed and the input is deterministic.
SAMPLE_IMAGE=""
if printf '%s\n' "${PROMPT_ENTRIES[@]}" | cut -d'|' -f3 | grep -q '^1$'; then
  SAMPLE_IMAGE="$work/sample.png"
  if python3 - "$SAMPLE_IMAGE" <<'PY' 2>/dev/null
import sys, zlib, struct
W, H = 120, 80
def colour(x):
    if x < W // 3:      return (220, 40, 40)   # red
    if x < 2 * W // 3:  return (40, 180, 70)   # green
    return (40, 90, 220)                       # blue
raw = bytearray()
for y in range(H):
    raw.append(0)  # PNG filter type 0 per scanline
    for x in range(W):
        raw += bytes(colour(x))
def chunk(typ, data):
    return (struct.pack(">I", len(data)) + typ + data
            + struct.pack(">I", zlib.crc32(typ + data) & 0xffffffff))
png = (b"\x89PNG\r\n\x1a\n"
       + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0))  # 8-bit RGB
       + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
       + chunk(b"IEND", b""))
open(sys.argv[1], "wb").write(png)
PY
  then
    note "Generated multimodal test image (red/green/blue stripes): $SAMPLE_IMAGE"
  else
    warn "could not generate the test image — multimodal prompt will run text-only"
    SAMPLE_IMAGE=""
  fi
fi

# --- which tools are installed -----------------------------------------------
INSTALLED_TOOLS=()
for tool in $CAPTURE_TOOLS; do
  if command -v "$tool" >/dev/null 2>&1; then
    INSTALLED_TOOLS+=("$tool")
  else
    warn "$tool not installed — skipping"
  fi
done
if [ "${#INSTALLED_TOOLS[@]}" -eq 0 ]; then
  warn "No supported CLI was installed — nothing to capture. Install claude, opencode, or tabnine."
  exit 0
fi

# --- run one tool with a prompt (+ optional image), capturing combined output ---
# Image input is best-effort: opencode via -f, claude/tabnine via an absolute path
# reference in the prompt — a tool that ignores it still sends a text request.
run_tool() { # $1=tool $2=prompt $3=image(path or "") $4=outfile
  local tool="$1" prompt="$2" image="$3" outfile="$4" p
  case "$tool" in
    claude)
      p="$prompt"; [ -n "$image" ] && p="$prompt The image to look at is the file at: $image"
      run_with_timeout "$CAPTURE_TIMEOUT" claude -p "$p" </dev/null >"$outfile" 2>&1 ;;
    opencode)
      if [ -n "$image" ]; then
        run_with_timeout "$CAPTURE_TIMEOUT" opencode run "$prompt" -f "$image" </dev/null >"$outfile" 2>&1
      else
        run_with_timeout "$CAPTURE_TIMEOUT" opencode run "$prompt" </dev/null >"$outfile" 2>&1
      fi ;;
    tabnine)
      p="$prompt"; [ -n "$image" ] && p="$prompt @$image"
      run_with_timeout "$CAPTURE_TIMEOUT" tabnine --prompt "$p" --skip-trust --approval-mode plan --output-format text </dev/null >"$outfile" 2>&1 ;;
  esac
}

# --- run the ladder; collect per (prompt,tool) facts -------------------------
facts="$work/facts.tsv"; : >"$facts"
labels=()
for entry in "${PROMPT_ENTRIES[@]}"; do
  label="${entry%%|*}"; rest="${entry#*|}"
  kind="${rest%%|*}"; rest="${rest#*|}"
  wantimg="${rest%%|*}"; prompt="${rest#*|}"
  img=""; [ "$wantimg" = "1" ] && img="$SAMPLE_IMAGE"
  labels+=("$label")
  note "Prompt '$label' ($kind)$([ -n "$img" ] && echo ' + image') — running ${#INSTALLED_TOOLS[@]} tool(s)…"
  ms "/mockserver/clear?type=LOG" >/dev/null
  for tool in "${INSTALLED_TOOLS[@]}"; do
    out="$work/out_${label}_${tool}.txt"
    start="$(date +%s)"
    run_tool "$tool" "$prompt" "$img" "$out"
    dur="$(( $(date +%s) - start ))"
    to=0; grep -qiE "timed out|timeout|ETIMEDOUT|headers timed out" "$out" 2>/dev/null && to=1
    re="$(grep -ioE "retry|retrying|attempt #[0-9]+" "$out" 2>/dev/null | wc -l | tr -d ' ')"
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$tool" "$label" "$kind" "$dur" "$to" "$re" >>"$facts"
    printf '    %-9s %-11s %4ss  %s\n' "$tool" "$label" "$dur" \
      "$([ "$to" = 1 ] && echo 'TIMEOUT/RETRY observed' || echo 'completed')"
  done
  sleep 2  # let async log flushes land
  ms "/mockserver/retrieve?type=REQUEST_RESPONSES" >"$work/traffic_${label}.json"
  curl -s "$MOCKSERVER_URL/mockserver/llm/optimisationReport?format=json" >"$work/report_${label}.json"
done

# --- analyse + render --------------------------------------------------------
RAN_CSV="$(IFS=,; echo "${INSTALLED_TOOLS[*]}")"
LABELS_CSV="$(IFS=,; echo "${labels[*]}")"
python3 - "$work" "$facts" "$RAN_CSV" "$LABELS_CSV" "$CAPTURE_FAIL_ON_TIMEOUT" <<'PY'
import json, sys

work, facts_path, ran_csv, labels_csv, fail_on_timeout = sys.argv[1:6]
ran = [t for t in ran_csv.split(",") if t]
labels = [l for l in labels_csv.split(",") if l]
fail_on_timeout = fail_on_timeout == "1"

# facts[(tool,label)] = {kind,dur,timedout,retries}
facts = {}
with open(facts_path) as fh:
    for line in fh:
        parts = line.rstrip("\n").split("\t")
        if len(parts) == 6:
            tool, label, kind, dur, to, re = parts
            facts[(tool, label)] = {"kind": kind, "dur": dur, "to": to == "1", "re": int(re or 0)}

def is_claude(p):   return (p or "").endswith("/v1/messages")
def is_opencode(p): return "/codex/responses" in (p or "")
def is_tabnine(p):  return (p or "").endswith("/chat/completions") and "/codex/" not in (p or "")
SIG = {"claude": (is_claude, "ANTHROPIC"),
       "opencode": (is_opencode, "OPENAI_RESPONSES"),
       "tabnine": (is_tabnine, "OPENAI")}

def hdr(headers, name):
    for k, v in (headers or {}).items():
        if k.lower() == name:
            return v[0] if isinstance(v, list) else v
    return None

def load(path):
    try:
        return json.load(open(path))
    except Exception:
        return []

print()
print(f"{'TOOL':9} {'PROMPT':11} {'KIND':11} {'DUR':5} {'TO/RTY':7} {'CAPTURED':9} {'CLASSIFIED':11} {'UPSTREAM':9} {'STREAM':6} RESULT")
print("-" * 100)

overall_ok = True
any_timeout = False
max_overhead = 0.0          # max (tool wall-clock − MockServer upstream time) across rows
overhead_row = None
for label in labels:
    traffic = load(f"{work}/traffic_{label}.json")
    report = load(f"{work}/report_{label}.json")
    calls = (report or {}).get("calls") or []
    for tool in ran:
        matcher, provider = SIG[tool]
        mine = [e for e in traffic if matcher((e.get("httpRequest") or {}).get("path"))]
        captured = len(mine)
        classified = any(matcher(c.get("path")) and (c.get("provider") or "").upper() == provider for c in calls)
        # Upstream response time (ms) MockServer measured, and whether it streamed.
        ups, streamed = [], False
        for e in mine:
            h = (e.get("httpResponse") or {}).get("headers")
            v = hdr(h, "x-mockserver-response-time-ms")
            if v:
                try: ups.append(int(v))
                except ValueError: pass
            if (hdr(h, "x-mockserver-streamed") or "").lower() == "true":
                streamed = True
        up_ms = max(ups) if ups else None
        f = facts.get((tool, label), {})
        dur = f.get("dur", "?"); to = f.get("to", False); re = f.get("re", 0)
        any_timeout = any_timeout or to
        # Overhead = how much of the wall-clock was NOT MockServer's forwarding.
        try:
            ov = float(dur) - (up_ms / 1000.0 if up_ms else 0.0)
            if ov > max_overhead:
                max_overhead = ov; overhead_row = (tool, label, dur, up_ms)
        except (TypeError, ValueError):
            pass
        cell_ok = captured >= 1 and classified
        overall_ok = overall_ok and cell_ok
        tort = (f"{re}x" if re else "yes") if to else "-"
        print(f"{tool:9} {label:11} {f.get('kind','?'):11} {str(dur)+'s':5} {tort:7} "
              f"{str(captured)+' req':9} {('yes' if classified else 'NO'):11} "
              f"{(str(up_ms)+'ms' if up_ms is not None else '-'):9} "
              f"{('yes' if streamed else 'no'):6} {'PASS' if cell_ok else 'FAIL'}")

print("-" * 100)
print("Columns: DUR=tool wall-clock · TO/RTY=tool reported timeout/retries · "
      "UPSTREAM=max provider response time MockServer saw · STREAM=relayed incrementally")
# DUR is dominated by the TOOL's own startup/MCP/retries, not MockServer: UPSTREAM is the
# only MockServer-attributable time. Flag when the gap is large so slowness is not misread
# as a proxy problem.
if overhead_row and max_overhead >= 10:
    t, lab, d, u = overhead_row
    print(f"NOTE: '{t}' on '{lab}' spent ~{max_overhead:.0f}s OUTSIDE MockServer "
          f"(wall {d}s vs MockServer upstream {u}ms). That gap is the tool's own "
          f"startup / MCP servers / retries — not the proxy. Compare DUR to UPSTREAM to "
          f"attribute slowness correctly; to speed the tool up, fix/disable its MCP servers.")
print()

gate_ok = overall_ok and (not (fail_on_timeout and any_timeout))
if not overall_ok:
    print("FAILED: a prompt/tool was not captured AND classified.")
    print("  - CAPTURED 0 req => proxy/TLS not intercepting this CLI (check HTTPS_PROXY + CA env / jar build).")
    print("  - CLASSIFIED NO  => recorded but not recognised as LLM traffic (detection gap / stale jar).")
if any_timeout:
    msg = "FAILED" if (fail_on_timeout and overall_ok) else "NOTE"
    print(f"{msg}: one or more prompts hit a timeout/retry (see TO/RTY) — typically the SLOW 'reasoning' "
          "prompt, where the provider is slow to send response headers. STREAM=yes + a high UPSTREAM "
          "confirms MockServer relayed it incrementally rather than buffering.")
if gate_ok and not any_timeout:
    print("PASSED: every prompt was captured and classified, with no timeouts.")
elif gate_ok:
    print("PASSED (capture+classify): every prompt was captured and classified. Timeouts above are reported only "
          "(set CAPTURE_FAIL_ON_TIMEOUT=1 to gate on them).")
sys.exit(0 if gate_ok else 1)
PY
rc=$?

echo
if [ "$rc" -eq 0 ]; then
  ok "Capture smoke test passed for: $RAN_CSV"
  note "View it: $MOCKSERVER_URL/mockserver/dashboard  →  Traffic / LLM Traces / LLM Optimise"
else
  fail "Capture smoke test failed — see table above."
fi
exit "$rc"
