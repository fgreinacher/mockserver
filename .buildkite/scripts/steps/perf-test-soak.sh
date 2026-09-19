#!/usr/bin/env bash
set -euo pipefail

# WEEKLY soak step (perf queue) — item 10 of the performance programme.
#
# Runs soak.js for a long duration (default 2h) against a MockServer with a
# BOUNDED heap, to surface slow degradation that a 2-minute regression run cannot:
# data-plane p99 DRIFT as the event log fills and stays full, connection/fd leaks
# over hours, and — item 10b — the COST of `verify` / `retrieveRecordedRequests`
# against a FULL log (the central-deployment pattern; an O(n) ring regression
# bites hardest here).
#
# Two INDEPENDENT loud-failure mechanisms (a scheduled-but-silent soak is the
# false green this step is built to prevent):
#   1. k6's own thresholds (p99 drift on op:match, error rate, checks) — a breach
#      makes k6 exit non-zero, which this step propagates -> RED build. THIS is the
#      soak gate.
#   2. a PRESENCE ASSERTION after k6: the soak-result.json must exist AND its
#      match / verify / retrieve arms must each carry samples, AND the SUT must
#      have received enough traffic that the log genuinely filled. A soak that
#      "ran" but produced no 10b measurement fails here, LOUD — it is never a
#      green with an empty result block (the item-8 lesson).
#
# NOTIFY-ONLY otherwise. The soak result is uploaded as its OWN artifact
# (perf-soak.json) and is DELIBERATELY NOT fed to perf-test-compare.sh: no soak
# budget keys exist yet, and the daily compare's fail-closed missing-budget rule
# would red the build on a soak metric it cannot resolve. Soak metrics stay
# notify-only until ~8 weekly runs of variance let a budget be derived (~2
# months). See docs/plans/performance-programme.md item 10.
#
# WHY WEEKLY, OUT OF THE DAILY'S SLOT: the perf queue is max_size=1. A 2h soak
# starting in the daily regression's 04:00 UTC window would block that day's
# regression entirely. This step is dispatched by a SEPARATE weekly schedule
# (terraform/buildkite-pipelines/pipelines.tf) at a time clear of 04:00 (daily
# regression) and 16:00 (baseline freshness).
#
# Reproduce a SHORT local soak (needs docker):
#   K6_SOAK_DURATION=100s K6_SOAK_WINDOW=20s K6_SOAK_LEAD=10s PERF_SERVER_MEMORY=1g \
#   MOCKSERVER_MAX_LOG_ENTRIES=2000 SOAK_MIN_RECEIVED=20000 \
#   .buildkite/scripts/steps/perf-test-soak.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# Pinned k6 image — identical ref to perf-test-run.sh / perf-test-lint.sh.
K6_IMAGE="grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f"
# Plain snapshot: the soak exercises match / create / verify / retrieve only — no
# JavaScript response templates — so the GraalJS variant is not needed here.
MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot}"

# Bounded heap so GC actually cycles and a slow leak / live-set floor is
# observable (an unbounded MaxRAMPercentage heap barely GCs and hides a leak).
SERVER_MEMORY="${PERF_SERVER_MEMORY:-2g}"
# Event-log ring: leave at the DEFAULT (never shrink it — a shrunk ring never
# fills at scale and would hide the very O(n) regression 10b exists to catch).
# Overridable ONLY for a short local proof run (a small ring fills in seconds).
MAX_LOG_ENTRIES="${MOCKSERVER_MAX_LOG_ENTRIES:-}"

# Soak shape (pass-through to soak.js via K6_* — defaults in k6/lib/config.js and
# soak.js). Two hours is the item-10 target.
SOAK_DURATION="${K6_SOAK_DURATION:-2h}"
SOAK_WINDOW="${K6_SOAK_WINDOW:-5m}"
SOAK_LEAD="${K6_SOAK_LEAD:-2m}"
SOAK_RATE="${K6_SOAK_RATE:-200}"
SOAK_VERIFY_RATE="${K6_SOAK_VERIFY_RATE:-1}"
SOAK_RETRIEVE_RATE="${K6_SOAK_RETRIEVE_RATE:-1}"
# PRESENCE-ASSERTION floor: the SUT must have received at least this many requests
# for the 10b measurement to be "against a full log". The default ring holds ~100k
# entries (~40k requests); a 2h soak at 200 rps sends ~1.4M, so 100000 is a wide
# safety floor that only trips when traffic never really flowed. Override LOW for a
# short local run.
SOAK_MIN_RECEIVED="${SOAK_MIN_RECEIVED:-100000}"
SAMPLE_INTERVAL="${PERF_SAMPLE_INTERVAL:-30}"

RUN_ID="${BUILDKITE_BUILD_ID:-local}-$$"
NETWORK="mockserver-soak-${RUN_ID}"
SERVER="mockserver-soak-${RUN_ID}"
SERVER_ALIAS="mockserver"

OUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-soak.XXXXXX")"
# k6 runs as uid 12345; world-write the /out bind mount so handleSummary can write.
chmod 0777 "$OUT_DIR"
K6_RESULT="$OUT_DIR/soak-result.json"
SAMPLE_LOG="$OUT_DIR/samples.csv"
SOAK_JSON="$OUT_DIR/perf-soak.json"

SAMPLER_PID=""
cleanup() {
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" >/dev/null 2>&1 || true
  docker rm -f "$SERVER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- fail loud: assemble a MINIMAL failed artifact + annotation, then exit 1 ----
# Used by the presence assertion so a soak that produced nothing is never a silent
# green. Records soak_attempted:true so a reader can tell "attempted and failed"
# from "not scheduled".
fail_soak() {
  local reason="$1"
  echo "ERROR: $reason" >&2
  # Stop the sampler so samples.csv is complete before we upload it as evidence.
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" >/dev/null 2>&1 || true; SAMPLER_PID=""
  if command -v buildkite-agent >/dev/null 2>&1; then
    jq -n --arg reason "$reason" \
      '{soak_attempted:true, soak_ok:false, failure_reason:$reason}' > "$SOAK_JSON" 2>/dev/null || true
    cp "$SOAK_JSON" "$REPO_ROOT/perf-soak.json" 2>/dev/null || true
    buildkite-agent artifact upload "perf-soak.json" 2>/dev/null || true
    # Best-effort: upload the evidence needed to diagnose WHY the soak produced
    # nothing (occupancy trajectory + any partial k6 summary) — it otherwise dies
    # in the mktemp dir with the agent, which is exactly the case failures need it.
    [ -f "$SAMPLE_LOG" ] && { cp "$SAMPLE_LOG" "$REPO_ROOT/perf-soak-samples.csv" 2>/dev/null && buildkite-agent artifact upload "perf-soak-samples.csv" 2>/dev/null; } || true
    [ -f "$K6_RESULT" ] && { cp "$K6_RESULT" "$REPO_ROOT/perf-soak-partial-result.json" 2>/dev/null && buildkite-agent artifact upload "perf-soak-partial-result.json" 2>/dev/null; } || true
    printf '%s\n' ":rotating_light: **Soak FAILED** — ${reason}. The weekly soak was scheduled and attempted but did not produce a valid result; this is a hard failure (a scheduled-but-silent soak must never read as green). Evidence (occupancy samples + any partial k6 summary) uploaded as artifacts." \
      | buildkite-agent annotate --style error --context perf-soak 2>/dev/null || true
  fi
  exit 1
}

echo "--- soak config"
echo "    image=$MOCKSERVER_IMAGE  memory=$SERVER_MEMORY  maxLogEntries=${MAX_LOG_ENTRIES:-default}"
echo "    duration=$SOAK_DURATION  window=$SOAK_WINDOW  matchRate=$SOAK_RATE  verifyRate=$SOAK_VERIFY_RATE  retrieveRate=$SOAK_RETRIEVE_RATE"

docker network create "$NETWORK" >/dev/null
echo "--- starting SUT ($MOCKSERVER_IMAGE, --memory=$SERVER_MEMORY)"
# shellcheck disable=SC2046
docker run -d --rm --name "$SERVER" --network "$NETWORK" --network-alias "$SERVER_ALIAS" \
  --memory="$SERVER_MEMORY" -p 127.0.0.1::1080 \
  -e MOCKSERVER_LOG_LEVEL=ERROR \
  -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
  -e MOCKSERVER_METRICS_ENABLED=true \
  ${MAX_LOG_ENTRIES:+-e MOCKSERVER_MAX_LOG_ENTRIES="$MAX_LOG_ENTRIES"} \
  "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null

# Readiness: poll PUT /mockserver/status (unauthenticated, all versions) rather
# than a port open — MockServer accepts-then-resets during init.
SERVER_HOSTPORT="$(docker port "$SERVER" 1080/tcp 2>/dev/null | head -1)"
STATUS_URL="http://${SERVER_HOSTPORT:-127.0.0.1:1080}/mockserver/status"
METRICS_URL="http://${SERVER_HOSTPORT:-127.0.0.1:1080}/mockserver/metrics"
ready=false
for _ in $(seq 1 60); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$STATUS_URL" 2>/dev/null || echo 000)" = "200" ]; then
    ready=true; break
  fi
  sleep 2
done
[ "$ready" = true ] || fail_soak "SUT did not become ready on $STATUS_URL"
echo "--- SUT ready on $STATUS_URL"

# --- background sampler: log inflow (unbounded) + heap + drops + threads --------
# CHEAP metrics reads only (no full-log retrieve, which would itself perturb the
# measurement). The ring-buffer BOUND is demonstrated by requests_received_count
# climbing unboundedly while the heap live-set floor plateaus and dropped_log_events
# stays flat — proof the count-bounded ring holds, not merely an assertion.
sampler() {
  echo "ts,requests_received,heap_bytes,dropped_log_events,threads" > "$SAMPLE_LOG"
  while true; do
    local m rr heap dropped threads
    m="$(curl -s --max-time 5 "$METRICS_URL" 2>/dev/null || echo '')"
    rr="$(printf '%s' "$m" | awk '/^requests_received_count /{print $2}')"
    heap="$(printf '%s' "$m" | awk -F' ' '/^jvm_memory_used_bytes\{area="heap"\}/{print $2}')"
    dropped="$(printf '%s' "$m" | awk '/^mock_server_dropped_log_events/{print $2}')"
    threads="$(printf '%s' "$m" | awk '/^jvm_threads_current/{print $2}')"
    printf '%s,%s,%s,%s,%s\n' "$(date -u +%s)" "${rr:-}" "${heap:-}" "${dropped:-0}" "${threads:-}" >> "$SAMPLE_LOG"
    sleep "$SAMPLE_INTERVAL"
  done
}
sampler & SAMPLER_PID=$!

# OPEN QUESTION for the first weekly run (not settled): local validation used a
# small (~2000-entry) ring, where a `type=REQUESTS` full scan is cheap. At the
# production default (~100k entries) that scan is roughly 50x heavier per call, so
# its effect on the GATED match p99 and on drift_ratio is UNVERIFIED. Gate headroom
# is large (local match p99 ~2.3 ms against the 100 ms limit), so a false red is
# unlikely — but the FIRST weekly run should be read as validating that assumption,
# not as an established baseline. If the retrieve arm ever perturbs match p99,
# lower K6_SOAK_RETRIEVE_RATE.
echo "--- soak.js ($SOAK_DURATION)"
SOAK_EXIT=0
# shellcheck disable=SC2046
docker run --rm --network "$NETWORK" \
  -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
  -v "$OUT_DIR:/out" \
  -e "BASE_URL=http://${SERVER_ALIAS}:1080" \
  -e "PROTO=http" \
  -e "K6_SOAK_DURATION=$SOAK_DURATION" \
  -e "K6_SOAK_WINDOW=$SOAK_WINDOW" \
  -e "K6_SOAK_LEAD=$SOAK_LEAD" \
  -e "K6_SOAK_RATE=$SOAK_RATE" \
  -e "K6_SOAK_VERIFY_RATE=$SOAK_VERIFY_RATE" \
  -e "K6_SOAK_RETRIEVE_RATE=$SOAK_RETRIEVE_RATE" \
  -e "K6_SOAK_RESULT_PATH=/out/soak-result.json" \
  "$K6_IMAGE" run /k6/soak.js || SOAK_EXIT=$?

kill "$SAMPLER_PID" >/dev/null 2>&1 || true; SAMPLER_PID=""

# --- ring-buffer bound + occupancy trajectory from the sample log ---------------
# requests_received_count is monotonic total requests received (UNBOUNDED) UNTIL
# soak.js's teardown() resets the SUT, which zeroes the gauge — so a few trailing
# samples can be post-reset. Everything is keyed off the PEAK-received row (the
# last load sample before teardown), never the literal last row, so the reset does
# not corrupt the figures. live_set_floor is the min heap over the last window of
# the LOAD phase (the GC saw-tooth floor ~ the retained set — flat = bounded).
# dropped_total > 0 means the disruptor ring saturated (recorded, not gated).
RECEIVED_END=0; LIVE_SET_FLOOR=0; HEAP_START=0; HEAP_END=0; DROPPED_TOTAL=0; THREADS_END=0; SAMPLE_ROWS=0
if [ -f "$SAMPLE_LOG" ]; then
  SAMPLE_ROWS="$(($(wc -l < "$SAMPLE_LOG") - 1))"
  # Single pass: track the row index P of the maximum requests_received (peak =
  # last pre-teardown load sample), and read heap/threads at P + the saw-tooth
  # floor over rows [P-4..P]. dropped is monotonic so its max is the total.
  read -r RECEIVED_END HEAP_START HEAP_END LIVE_SET_FLOOR DROPPED_TOTAL THREADS_END <<EOF
$(awk -F, '
  NR>1 {
    rr[n]=$2+0; hp[n]=$3+0; dr[n]=$4+0; th[n]=$5+0;
    if ($2!="" && rr[n]>maxrr){maxrr=rr[n]; P=n}
    if ($4!="" && dr[n]>maxdr){maxdr=dr[n]}
    if (NR==2) hs=$3+0;
    n++
  }
  END{
    floor=-1; s=(P>4?P-4:0);
    for(i=s;i<=P;i++){ if(hp[i]>0 && (floor<0||hp[i]<floor)) floor=hp[i] }
    printf "%d %d %d %d %d %d", maxrr+0, hs+0, hp[P]+0, (floor<0?0:floor), maxdr+0, th[P]+0
  }' "$SAMPLE_LOG")
EOF
fi

# --- PRESENCE ASSERTION (false-green guard) -------------------------------------
[ -f "$K6_RESULT" ] || fail_soak "k6 produced no soak-result.json (exit=$SOAK_EXIT) — the soak did not run to a summary"
if ! jq empty "$K6_RESULT" >/dev/null 2>&1; then
  fail_soak "soak-result.json is not valid JSON (exit=$SOAK_EXIT)"
fi
MATCH_SAMPLES="$(jq -r '.soak.match.samples // 0' "$K6_RESULT")"
VERIFY_SAMPLES="$(jq -r '.soak.verify.samples // 0' "$K6_RESULT")"
RETRIEVE_SAMPLES="$(jq -r '.soak.retrieve.samples // 0' "$K6_RESULT")"
[ "$MATCH_SAMPLES" -gt 0 ] 2>/dev/null || fail_soak "soak match arm produced 0 samples — the data-plane load never ran"
# The 10b arms are the whole point of this item; an empty verify/retrieve arm is a
# silent-nothing result, not a pass.
[ "$VERIFY_SAMPLES" -gt 0 ] 2>/dev/null || fail_soak "soak 10b verify arm produced 0 samples — event-log verification cost was NOT measured"
[ "$RETRIEVE_SAMPLES" -gt 0 ] 2>/dev/null || fail_soak "soak 10b retrieve arm produced 0 samples — event-log retrieval cost was NOT measured"
# The 10b measurement is only meaningful against a FULL log: assert enough traffic
# flowed that the ring genuinely filled and cycled.
[ "$RECEIVED_END" -ge "$SOAK_MIN_RECEIVED" ] 2>/dev/null || \
  fail_soak "SUT received only $RECEIVED_END requests (< SOAK_MIN_RECEIVED=$SOAK_MIN_RECEIVED) — the event log never filled, so the 10b latency was NOT measured against a full ring"
[ "$SAMPLE_ROWS" -gt 0 ] 2>/dev/null || fail_soak "no server metric samples captured — occupancy trajectory is empty"

# --- DIAGNOSTIC GUARD: "the SUT is answering, but with 404s" ---------------------
# Build 324's first-ever soak reported a 54.2% match error rate that was
# SELF-INFLICTED: the create arm PUT a distinct unbounded /simple expectation each
# call, which filled maxExpectations and then EVICTED the seeded /simple match
# expectation, so the SUT correctly 404'd most match requests. Every presence
# assertion above PASSED (all arms had samples; received cleared its floor) while
# three-quarters of the match arm was a 404 — a false-green shape the presence
# checks cannot see. This guard catches exactly it: a HIGH match failure rate
# (http_req_failed, which counts completed non-2xx responses) combined with ~ZERO
# transport errors (requests that never completed) means the SUT was UP and
# ANSWERING but returning non-2xx (almost always 404). A genuinely DOWN SUT shows a
# high transport-error rate instead and is left to the k6 error-rate gate; this
# guard deliberately does NOT fire on that shape.
MATCH_ERR_RATE="$(jq -r '.soak.match.error_rate // 0' "$K6_RESULT")"
MATCH_TRANSPORT_ERRORS="$(jq -r '.soak.match.transport_errors // 0' "$K6_RESULT")"
# Float comparison via awk — a shell/jq STRING compare on rates is a known footgun
# ("0.542" <= "0.05" mis-orders lexically). "High" = >=5% (the healthy match arm is
# ~0%, well under the 1% k6 gate); "~zero transport" = transport-error rate <=0.5%.
ANSWERING_WITH_404S="$(awk -v er="$MATCH_ERR_RATE" -v te="$MATCH_TRANSPORT_ERRORS" -v n="$MATCH_SAMPLES" \
  'BEGIN{ ter=(n>0 ? te/n : 0); print (((er+0)>=0.05) && (ter<=0.005)) ? "yes" : "no" }')"
if [ "$ANSWERING_WITH_404S" = "yes" ]; then
  fail_soak "match arm failed ${MATCH_ERR_RATE} of requests with only ${MATCH_TRANSPORT_ERRORS} transport errors across ${MATCH_SAMPLES} samples — the SUT was UP and ANSWERING but returning non-2xx, NOT a transport/connection failure. Read the per-status breakdown in the uploaded partial k6 result before assuming a cause; this guard classifies the SHAPE, it does not diagnose. Two known causes of this shape, in order of likelihood: (1) the harness evicting its own match seed — the build-324 bug, where an unbounded 'create' arm filled maxExpectations and evicted the seeded /simple expectation, giving 404s; check createSimpleExpectation() still uses a stable id and a distinct path (/churn), and do NOT paper over it by raising maxExpectations. (2) the SUT genuinely returning completed 5xx under load, which is a real regression and looks nothing like (1) in the status breakdown."
fi

echo "--- ring-buffer bound: received_end=$RECEIVED_END (unbounded) live_set_floor=$LIVE_SET_FLOOR bytes dropped=$DROPPED_TOTAL threads_end=$THREADS_END"

# --- assemble the notify-only soak artifact ------------------------------------
# Wraps the k6 soak block with run metadata + the ring/occupancy block. NOT the
# daily-regression result shape and NOT downloaded by perf-test-compare.sh, so it
# cannot perturb the fail-closed missing-budget rule. When soak metrics graduate
# to the baseline (~8 weekly runs), a `.soak` enumeration in compare.sh maps this
# straight onto soak.<arm>.<metric> budget keys.
BRANCH="${BUILDKITE_BRANCH:-$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}"
COMMIT="${BUILDKITE_COMMIT:-$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -n \
  --arg branch "$BRANCH" --arg commit "$COMMIT" --arg ts "$TS" \
  --arg image "$MOCKSERVER_IMAGE" --arg mem "$SERVER_MEMORY" \
  --argjson k6exit "$SOAK_EXIT" \
  --argjson received "$RECEIVED_END" --argjson liveset "$LIVE_SET_FLOOR" \
  --argjson heap_start "$HEAP_START" --argjson heap_end "$HEAP_END" \
  --argjson dropped "$DROPPED_TOTAL" --argjson threads "$THREADS_END" \
  --argjson min_received "$SOAK_MIN_RECEIVED" \
  --slurpfile k6 "$K6_RESULT" \
  '{
     soak_attempted: true,
     soak_ok: ($k6exit == 0),
     k6_exit: $k6exit,
     branch: $branch, commit: $commit, timestamp_utc: $ts,
     config: { image: $image, server_memory: $mem, min_received: $min_received },
     ring: {
       requests_received_total: $received,
       live_set_floor_bytes: $liveset,
       heap_start_bytes: $heap_start,
       heap_end_bytes: $heap_end,
       dropped_log_events: $dropped,
       threads_end: $threads
     }
   } + $k6[0]' > "$SOAK_JSON"

echo "--- perf-soak.json"
cat "$SOAK_JSON"

# --- human annotation (NOTIFY-ONLY) --------------------------------------------
if command -v buildkite-agent >/dev/null 2>&1; then
  cp "$SOAK_JSON" "$REPO_ROOT/perf-soak.json"
  buildkite-agent artifact upload "perf-soak.json" || true
  # Upload the per-30s occupancy/latency trajectory on EVERY outcome, not only the
  # fail_soak path. Build 324 exited 99 on a k6 threshold — which skips fail_soak
  # and reaches here — and its samples.csv died in the mktemp dir with the agent,
  # so the trajectory that would have shown WHEN the match arm began 404-ing was
  # lost. Same artifact name as fail_soak, so the evidence is at one path always.
  [ -f "$SAMPLE_LOG" ] && { cp "$SAMPLE_LOG" "$REPO_ROOT/perf-soak-samples.csv" 2>/dev/null && buildkite-agent artifact upload "perf-soak-samples.csv" 2>/dev/null; } || true
  ANNOT="$(jq -r '
    "**Weekly soak** (\(.config.image), \(.config.server_memory) heap, k6_exit=\(.k6_exit))\n\n" +
    "| arm | samples | p50 ms | p95 ms | p99 ms | drift (late/early p99) | err |\n" +
    "|---|---|---|---|---|---|---|\n" +
    "| match | \(.soak.match.samples) | \(.soak.match.p50_ms) | \(.soak.match.p95_ms) | \(.soak.match.p99_ms) | \(.soak.match.drift_ratio) | \(.soak.match.error_rate) |\n" +
    "| verify (10b) | \(.soak.verify.samples) | \(.soak.verify.p50_ms) | \(.soak.verify.p95_ms) | \(.soak.verify.p99_ms) | \(.soak.verify.drift_ratio) | \(.soak.verify.error_rate) |\n" +
    "| retrieve (10b) | \(.soak.retrieve.samples) | \(.soak.retrieve.p50_ms) | \(.soak.retrieve.p95_ms) | \(.soak.retrieve.p99_ms) | \(.soak.retrieve.drift_ratio) | \(.soak.retrieve.error_rate) |\n\n" +
    ( if .ring.dropped_log_events == 0
      then ":lock: **Ring-buffer bound held**: 0 dropped log events across \(.ring.requests_received_total) requests received — the count-bounded event log evicted rather than grew (live-set floor \(.ring.live_set_floor_bytes) bytes, heap \(.ring.heap_start_bytes)→\(.ring.heap_end_bytes)).\n\n"
      else ":warning: **Event-log ring SATURATED**: \(.ring.dropped_log_events) dropped log events (\(.ring.requests_received_total) received) — the disruptor could not keep up; raise ringBufferSize or reduce log verbosity. (live-set floor \(.ring.live_set_floor_bytes) bytes, heap \(.ring.heap_start_bytes)→\(.ring.heap_end_bytes)).\n\n"
      end ) +
    "_Read the verify/retrieve **drift** column with care: those arms run at 1/s, so each drift window holds only ~300 samples and their p99 can swing on a single GC pause. The match arm runs at 200/s and its drift is the trustworthy one. All three are notify-only and cannot red the build on drift alone._\n\n" +
    "_Notify-only: soak metrics are not gated and not fed to the daily baseline compare until ~8 weekly runs of variance exist (~2 months)._"
  ' "$SOAK_JSON")"
  STYLE="info"; [ "$SOAK_EXIT" -ne 0 ] && STYLE="error"
  printf '%s\n' "$ANNOT" | buildkite-agent annotate --style "$STYLE" --context perf-soak || true
else
  cp "$SOAK_JSON" "$REPO_ROOT/perf-soak.json"
  [ -f "$SAMPLE_LOG" ] && cp "$SAMPLE_LOG" "$REPO_ROOT/perf-soak-samples.csv" 2>/dev/null || true
  echo "(local run) soak artifact -> $REPO_ROOT/perf-soak.json (+ perf-soak-samples.csv)"
fi

# Propagate the k6 threshold verdict: a p99-drift / error-rate breach IS the soak
# failure signal and must red the build.
if [ "$SOAK_EXIT" -ne 0 ]; then
  echo "ERROR: soak thresholds crossed (k6 exit=$SOAK_EXIT) — data-plane p99 drift or error-rate gate tripped" >&2
  exit "$SOAK_EXIT"
fi
echo "--- soak complete (thresholds held)"
