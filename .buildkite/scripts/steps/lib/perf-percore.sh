#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# perf-percore.sh — req/s per core for the SERVING path (performance-programme
# item 18). Emits a self-describing `serving_percore` JSON block (stdout, or the
# file named by $1) that perf-test-run.sh merges into result.json under
# `.serving_percore` and copies out as the `serving-percore.json` artifact.
#
# WHAT IT MEASURES (and how it differs from the injector per-core curve).
# `inject-percore.json` (perf-test-inject.sh, chart_inject_percore) is the
# per-core ceiling of the LOAD GENERATOR. This is its mirror for the SERVER: pin
# ONE MockServer SUT to C cores, drive the sweep ladder against it from a k6 on
# DISJOINT cores, and record, per C:
#   * peak_achieved_rps   — max achieved over RIG-VALID rungs (client had CPU
#                           headroom, no dropped iterations, low errors). This is
#                           the SAME rig-valid peak definition perf-test-run.sh's
#                           saturation block uses; a rung where k6 (not the SUT)
#                           was the bottleneck is EXCLUDED, or the C-point would
#                           measure the injector, not the serving path.
#   * healthy_ceiling_rps — the highest rung where achieved stayed >= keep*offered
#                           with ZERO errors AND p50 within lat_mult x the flat-
#                           region p50. This is NOT re-implemented here: each C's
#                           sweep is fed as a synthetic run to the ONE authoritative
#                           implementation, lib/perf-website-figures.jq (Finding 1;
#                           the same definition render_perf_charts.py carries), and
#                           .headline.healthy_ceiling_rps is read back out. Reusing
#                           the filter, not copying its arithmetic, is deliberate:
#                           the programme forbids a third divergent copy of the
#                           ceiling rule.
#   * rps_per_core        — healthy_ceiling_rps / C. The HEALTHY ceiling, not the
#                           degraded peak, is the honest "req/s per core you can
#                           actually serve". (peak_per_core is also emitted.)
#
# THE C=16 PREREQUISITE (item 18 calls it easy to miss). At C=16 the SUT wants 16
# cores and the k6 client needs its OWN disjoint cores, so the rung is only
# feasible on a box with >=16 + K6_MIN_CORES cores. On anything smaller the curve
# STOPS at the largest feasible C and SAYS SO: every requested-but-infeasible C is
# recorded in `.serving_percore.skipped[]` with a reason, and `max_cores_measured`
# / `curve_complete_to_16` make the limit explicit in the artifact and annotation.
# A ladder that silently ends early reads as "measured to 16" to a skimmer — so it
# must never silently end early.
#
# PINNING (item 17's lever, reconfirmed here). `--cpuset-cpus` is the reliable
# pin: it changes what the container's JVM sees via Runtime.availableProcessors(),
# which sizes actionHandlerThreadCount()=max(5,availableProcessors()) and the pools
# derived from it. `--cpus` (CFS quota) does NOT surface in the processor count, so
# it would leave the pools sized for the whole host — not what we mean to measure.
# We PROVE the pin took: for each C a one-shot probe container on the IDENTICAL
# image and cpuset prints availableProcessors (recorded as .available_processors);
# same image runtime + same cgroup cpuset as the SUT, so it is definitionally the
# count the SUT's own JVM computed. A C whose probe != C fails the run loudly.
#
# WARM-UP IS BIAS, NOT NOISE. Before each measured sweep a short warm-up drive
# runs (never measured), so the sweep's first rung is not paying JIT/first-touch
# cost. The warm-up's own p50 and the first vs second rung p50 are recorded under
# .warmup so a reader can SEE the transient was removed rather than averaged in.
#
# EVENT-LOG RETENTION, PER RUNG. MockServer keeps full request/response bodies in
# a COUNT-bounded ring (maxLogEntries), so retained bytes ~ maxLogEntries x body
# is roughly CONSTANT with rate, but an entry's RESIDENCE time = maxLogEntries /
# achieved_rps LENGTHENS as throughput falls. At C=1 the SUT is slow, so residence
# is long — the arithmetic is done PER RUNG (.ladder[].retention_residence_s), not
# once. maxLogEntries has no metrics endpoint, so it is an ASSUMED input
# (PERF_PERCORE_MAX_LOG_ENTRIES, honestly labelled `retention.assumed_max_log_entries`).
#
# NON-GATING. Every serving_percore.* metric is notify-only (see perf-budgets.json
# and perf-test-compare.sh). This harness only measures; it never fails a build on
# a throughput number.
# ---------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${PERF_PERCORE_REPO_ROOT:-$(cd "$SCRIPT_DIR/../../../.." && pwd)}"
FIGURES_JQ="$SCRIPT_DIR/perf-website-figures.jq"

OUT_FILE="${1:-/dev/stdout}"

# --- inputs (all overridable) --------------------------------------------------
MOCKSERVER_IMAGE="${PERF_PERCORE_IMAGE:-${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot-graaljs}}"
K6_IMAGE="${PERF_PERCORE_K6_IMAGE:-grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f}"
PROBE_JDK_IMAGE="${PERF_PERCORE_JDK_IMAGE:-eclipse-temurin:17-jdk}"
K6_DIR="$REPO_ROOT/mockserver-performance-test/k6"

HOST_CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"

# The core ladder item 18 names. C=16 is included so the artifact records it as
# skipped-with-reason on a box that cannot host it, rather than omitting the rung.
CORE_LADDER="${PERF_PERCORE_CORES:-1,2,4,8,16}"
# k6 needs its own cores, and to measure the SERVER's ceiling the client must have
# MORE capacity than the server — otherwise the ladder measures the client, not the
# serving path (proven the hard way: an earlier 6-core client cap made k6 itself the
# bottleneck at C=1, drove k6 to 85% of its pin with dropped iterations, and produced
# a non-monotonic curve). So the client gets ALL spare cores by default: every core
# not pinned to the SUT, minus a small reserve for the kernel/docker/sampler. It is
# still disjoint from the SUT's cpuset. K6_MIN_CORES is the floor below which a rung
# is skipped (the client would be too weak to be trusted); K6_MAX_CORES defaults to
# the host count (no artificial cap).
K6_MIN_CORES="${PERF_PERCORE_K6_MIN_CORES:-2}"
K6_MAX_CORES="${PERF_PERCORE_K6_MAX_CORES:-$HOST_CORES}"
# Cores held back from BOTH server and client for the kernel, dockerd and the CPU
# sampler, so neither the SUT nor k6 contends with the box's own overhead.
K6_RESERVE="${PERF_PERCORE_RESERVE_CORES:-1}"

# Sweep ladder + timing per C. Kept overridable; the CI default climbs past the
# per-core knee at high C while staying short enough for 4-5 SUTs in one step.
SWEEP_RATES="${PERF_PERCORE_SWEEP_RATES:-250,500,1000,2000,4000,8000,16000,32000}"
SWEEP_STEP="${PERF_PERCORE_SWEEP_STEP:-12s}"
SWEEP_GAP="${PERF_PERCORE_SWEEP_GAP:-4s}"
SWEEP_PRE_VUS="${PERF_PERCORE_SWEEP_PRE_VUS:-200}"
SWEEP_MAX_VUS="${PERF_PERCORE_SWEEP_MAX_VUS:-4000}"
WARMUP_RATE="${PERF_PERCORE_WARMUP_RATE:-500}"
WARMUP_DURATION="${PERF_PERCORE_WARMUP_DURATION:-8s}"

SERVER_MEMORY="${PERF_PERCORE_MEMORY:-1g}"
# Event-log retention parameters for the per-rung residence arithmetic.
ASSUMED_MAX_LOG_ENTRIES="${PERF_PERCORE_MAX_LOG_ENTRIES:-100000}"

SWEEP_SETTLE_S="${PERF_PERCORE_SETTLE_S:-3}"
SWEEP_ERR_EPS="${PERF_PERCORE_ERROR_EPS:-0.01}"
SWEEP_SAMPLE_INTERVAL="${PERF_PERCORE_SAMPLE_INTERVAL:-2}"
# The MIN_TAIL_SAMPLES floor the k6 harnesses use (regression.js et al.).
MIN_TAIL_SAMPLES="${PERF_PERCORE_MIN_TAIL_SAMPLES:-30}"

RUN_ID="${BUILDKITE_BUILD_ID:-local}-$$-percore"
NETWORK="mockserver-percore-${RUN_ID}"
SERVER="mockserver-percore-sut-${RUN_ID}"
K6_NAME="mockserver-percore-k6-${RUN_ID}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-percore.XXXXXX")"
chmod 0777 "$WORK"

cleanup() {
  docker rm -f "$SERVER" "$K6_NAME" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  rm -rf "$WORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# k6 duration string -> integer seconds.
to_secs() {
  awk -v s="$1" 'BEGIN{ t=0; n="";
    for(i=1;i<=length(s);i++){c=substr(s,i,1);
      if(c ~ /[0-9]/){n=n c}
      else{v=n+0; n="";
        if(c=="s")t+=v; else if(c=="m")t+=v*60; else if(c=="h")t+=v*3600}}
    printf "%d", t}'
}

STEP_S="$(to_secs "$SWEEP_STEP")"
GAP_S="$(to_secs "$SWEEP_GAP")"

echo "--- item 18 serving per-core: host_cores=$HOST_CORES ladder=$CORE_LADDER k6=[${K6_MIN_CORES}..${K6_MAX_CORES}] image=$MOCKSERVER_IMAGE" >&2

# --- compile the availableProcessors probe ONCE (JDK image; the SUT image is a
# JRE and cannot run the single-file source launcher, so we ship a .class and run
# it with `--entrypoint java -cp` in the SUT image). ---------------------------
cat > "$WORK/AvailableProcessors.java" <<'EOF'
public class AvailableProcessors {
  public static void main(String[] a) {
    System.out.println("availableProcessors=" + Runtime.getRuntime().availableProcessors());
  }
}
EOF
if ! docker run --rm -v "$WORK:/w" -w /w "$PROBE_JDK_IMAGE" javac AvailableProcessors.java >/dev/null 2>&1; then
  echo "ERROR: could not compile the availableProcessors probe with $PROBE_JDK_IMAGE" >&2
  echo '{"attempted":true,"error":"probe_compile_failed","points":[],"skipped":[]}' > "$OUT_FILE"
  exit 0
fi

docker network create "$NETWORK" >/dev/null

# Report a JVM's availableProcessors for a cpuset, using the SUT image's own JVM.
probe_processors() { # cpuset
  docker run --rm --cpuset-cpus="$1" --entrypoint java \
    -v "$WORK:/probe:ro" "$MOCKSERVER_IMAGE" -cp /probe AvailableProcessors 2>/dev/null \
    | sed -n 's/^availableProcessors=//p' | head -1
}

# Build the "0-(C-1)" server cpuset and the disjoint k6 cpuset for a core count.
server_cpuset() { local c="$1"; if [ "$c" -eq 1 ]; then echo "0"; else echo "0-$((c-1))"; fi; }
k6_cpuset() {     local c="$1" w="$2"; if [ "$w" -eq 1 ]; then echo "$c"; else echo "$c-$((c+w-1))"; fi; }

POINTS=()     # per-C aggregate JSON objects
SKIPPED=()    # {cores, reason} for every requested-but-infeasible C
MAX_MEASURED=0

IFS=',' read -ra CORES_ARR <<< "$CORE_LADDER"
for C in "${CORES_ARR[@]}"; do
  # Feasibility: the SUT needs C cores AND k6 needs >= K6_MIN_CORES on DISJOINT
  # cores (with K6_RESERVE held back for the box), so C + K6_MIN_CORES + K6_RESERVE
  # must fit on the host. The whole point of item 18's prerequisite: at C=16 on a
  # <18-core box this is false and the rung is skipped with a reason rather than
  # measured wrong (shared cores) or silently omitted. The client gets ALL remaining
  # cores (minus the reserve) so it is not itself the bottleneck.
  k6w=$(( HOST_CORES - C - K6_RESERVE ))
  [ "$k6w" -gt "$K6_MAX_CORES" ] && k6w="$K6_MAX_CORES"
  if [ "$C" -gt "$HOST_CORES" ] || [ "$k6w" -lt "$K6_MIN_CORES" ]; then
    reason="needs ${C} SUT cores + >=${K6_MIN_CORES} disjoint client cores + ${K6_RESERVE} reserved; host has ${HOST_CORES}"
    echo "--- C=$C SKIPPED: $reason" >&2
    SKIPPED+=("$(jq -nc --argjson c "$C" --arg r "$reason" '{cores:$c, reason:$r, type:"infeasible"}')")
    continue
  fi

  SCPU="$(server_cpuset "$C")"
  KCPU="$(k6_cpuset "$C" "$k6w")"
  echo "+++ C=$C  server_cpus=$SCPU  k6_cpus=$KCPU (${k6w} client cores)" >&2

  # --- pinning proof: the SUT image's JVM, on the server cpuset -----------------
  AVAIL="$(probe_processors "$SCPU")"
  echo "    availableProcessors (SUT image JVM @ cpuset $SCPU) = ${AVAIL:-unknown}" >&2
  if [ "${AVAIL:-0}" != "$C" ]; then
    echo "ERROR: pin proof FAILED at C=$C — JVM reported '${AVAIL:-unknown}' processors, expected $C. --cpuset-cpus did not take; refusing to record a mislabelled point." >&2
    SKIPPED+=("$(jq -nc --argjson c "$C" --arg r "pin proof failed: JVM saw ${AVAIL:-unknown} processors, expected ${C}" '{cores:$c, reason:$r, type:"failure"}')")
    continue
  fi

  # --- start the SUT pinned to C cores -----------------------------------------
  docker rm -f "$SERVER" >/dev/null 2>&1 || true
  docker run -d --rm --name "$SERVER" --network "$NETWORK" --network-alias mockserver \
    --cpuset-cpus="$SCPU" --memory="$SERVER_MEMORY" -p 127.0.0.1::1080 \
    -e MOCKSERVER_LOG_LEVEL=ERROR -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
    -e MOCKSERVER_METRICS_ENABLED=true \
    "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null

  HOSTPORT="$(docker port "$SERVER" 1080/tcp 2>/dev/null | head -1)"
  HOSTPORT="${HOSTPORT:-127.0.0.1:1080}"

  # --- readiness: a listening port is NOT readiness. MockServer accepts then
  # RESETS during init, so poll PUT /mockserver/status (unauthenticated, present
  # in all versions) until it answers 200. ------------------------------------
  ready=false
  for _ in $(seq 1 60); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -X PUT "http://${HOSTPORT}/mockserver/status" 2>/dev/null || echo 000)"
    if [ "$code" = "200" ]; then ready=true; break; fi
    if ! docker ps --format '{{.Names}}' | grep -q "^${SERVER}$"; then
      echo "ERROR: SUT container exited during startup at C=$C" >&2; docker logs "$SERVER" 2>&1 | tail -20 >&2 || true; break
    fi
    sleep 2
  done
  if [ "$ready" != true ]; then
    echo "ERROR: SUT not ready at C=$C — skipping this rung" >&2
    SKIPPED+=("$(jq -nc --argjson c "$C" --arg r "SUT did not become ready (PUT /mockserver/status != 200)" '{cores:$c, reason:$r, type:"failure"}')")
    docker rm -f "$SERVER" >/dev/null 2>&1 || true
    continue
  fi

  # Measure the served body size once (for the retention arithmetic) — seed /simple
  # and GET it. sweep.js re-seeds/reset in setup()/teardown(), so this is only for
  # the byte count; it does not perturb the measured window.
  curl -s --max-time 5 -X PUT "http://${HOSTPORT}/mockserver/expectation" \
    -H 'Content-Type: application/json' \
    -d '[{"httpRequest":{"path":"/simple"},"httpResponse":{"statusCode":200,"body":"simple"},"times":{"unlimited":true}}]' \
    -o /dev/null 2>/dev/null || true
  BODY_BYTES="$(curl -s --max-time 5 "http://${HOSTPORT}/simple" 2>/dev/null | wc -c | tr -d ' ')"
  BODY_BYTES="${BODY_BYTES:-6}"

  # --- warm-up drive (NEVER measured): remove the JIT/first-touch transient so
  # the sweep's first rung is not systematically slow. ------------------------
  WARMUP_JSON="$WORK/warmup-C${C}.json"
  docker run --rm --network "$NETWORK" --cpuset-cpus="$KCPU" \
    -v "$K6_DIR:/k6:ro" -v "$WORK:/out" \
    -e "BASE_URL=http://mockserver:1080" -e "PROTO=http" \
    -e "K6_SWEEP_RATES=$WARMUP_RATE" -e "K6_SWEEP_STEP=$WARMUP_DURATION" -e "K6_SWEEP_GAP=1s" \
    -e "K6_SWEEP_RESULT_PATH=/out/warmup-C${C}.json" \
    -e "K6_SWEEP_PRE_VUS=$SWEEP_PRE_VUS" -e "K6_SWEEP_MAX_VUS=$SWEEP_MAX_VUS" \
    "$K6_IMAGE" run /k6/sweep.js >/dev/null 2>&1 || true
  WARMUP_P50="$(jq -r '(.points[0].p50_ms) // null' "$WARMUP_JSON" 2>/dev/null || echo null)"
  WARMUP_ACH="$(jq -r '(.points[0].achieved_rps) // null' "$WARMUP_JSON" 2>/dev/null || echo null)"

  # --- sample BOTH the k6 CLIENT and the SUT CPU during the measured sweep -----
  # The SUT CPU is the decisive datum for attributing a falling peak: if the SUT
  # sat WELL BELOW its C-core pin (C*100%) while achieved throughput fell, the
  # server had spare CPU and the limit is the load path / virtualization, NOT the
  # server; if it sat AT ~C*100% the server itself was the ceiling. Both are read
  # from ONE `docker stats --no-stream` call (two container names) so the sampler
  # adds one probe per interval, not two, and the k6 and SUT samples share a
  # timestamp. A name not yet running just yields no line (handled by the awk).
  CPU_LOG="$WORK/cpu-C${C}.csv"
  echo "ts,k6_cpu_pct,sut_cpu_pct" > "$CPU_LOG"
  ( while true; do
      ts="$(date -u +%s)"
      stats="$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' "$K6_NAME" "$SERVER" 2>/dev/null || echo '')"
      k6c="$(printf '%s\n' "$stats" | awk -v n="$K6_NAME" '$1==n{gsub(/%/,"",$2); print $2}')"
      sutc="$(printf '%s\n' "$stats" | awk -v n="$SERVER" '$1==n{gsub(/%/,"",$2); print $2}')"
      printf '%s,%s,%s\n' "$ts" "${k6c:-}" "${sutc:-}" >> "$CPU_LOG"
      sleep "$SWEEP_SAMPLE_INTERVAL"
    done ) & SAMPLER_PID=$!

  SWEEP_JSON="$WORK/sweep-C${C}.json"
  T0="$(date -u +%s)"
  docker run --rm --name "$K6_NAME" --network "$NETWORK" --cpuset-cpus="$KCPU" \
    -v "$K6_DIR:/k6:ro" -v "$WORK:/out" \
    -e "BASE_URL=http://mockserver:1080" -e "PROTO=http" \
    -e "K6_SWEEP_RATES=$SWEEP_RATES" -e "K6_SWEEP_STEP=$SWEEP_STEP" -e "K6_SWEEP_GAP=$SWEEP_GAP" \
    -e "K6_SWEEP_RESULT_PATH=/out/sweep-C${C}.json" \
    -e "K6_SWEEP_PRE_VUS=$SWEEP_PRE_VUS" -e "K6_SWEEP_MAX_VUS=$SWEEP_MAX_VUS" \
    "$K6_IMAGE" run --quiet /k6/sweep.js >&2 || true
  kill "$SAMPLER_PID" >/dev/null 2>&1 || true

  docker rm -f "$SERVER" >/dev/null 2>&1 || true

  if ! jq -e '.points | length > 0' "$SWEEP_JSON" >/dev/null 2>&1; then
    echo "ERROR: sweep produced no points at C=$C" >&2
    SKIPPED+=("$(jq -nc --argjson c "$C" --arg r "sweep produced no points" '{cores:$c, reason:$r, type:"failure"}')")
    continue
  fi

  # --- per-rung max k6 AND SUT CPU% from the sampler + the known ladder schedule
  # NOTE (scheduling caveat, review observation): each rung's window is derived
  # from T0 + i*(step+gap), which ASSUMES the k6 scenarios start the instant the
  # container launches. Container + k6 init latency shifts the real schedule by a
  # few seconds, so the FIRST rung's CPU attribution (and thus its rig_valid) can
  # be slightly misaligned; SWEEP_SETTLE_S trims the leading edge but does not
  # eliminate it. A k6-emitted scenario-start timestamp would fix it exactly; the
  # SUT-CPU series added here is the cross-check in the meantime (a rung mislabelled
  # by a schedule shift still shows the SUT's true CPU for that window).
  K6_PIN_PCT=$(( k6w * 100 ))
  SUT_PIN_PCT=$(( C * 100 ))
  CPU_MAP="{}"; SUT_CPU_MAP="{}"
  IFS=',' read -ra RATE_ARR <<< "$SWEEP_RATES"
  for i in "${!RATE_ARR[@]}"; do
    r="${RATE_ARR[$i]}"
    ws=$(( T0 + i * (STEP_S + GAP_S) + SWEEP_SETTLE_S ))
    we=$(( T0 + i * (STEP_S + GAP_S) + STEP_S ))
    # Emit the max in the window, or EMPTY when the window caught NO sample (so a
    # sparse/last-rung window becomes null, not a false 0% — a 0 would misread as
    # "idle" and mis-attribute the limit). `n` counts matched rows.
    maxcpu="$(awk -F',' -v a="$ws" -v b="$we" 'NR>1 && $1>=a && $1<=b && $2!="" { n++; if($2+0>m) m=$2+0 } END{ if(n>0) printf "%.1f", m; }' "$CPU_LOG" 2>/dev/null || echo '')"
    maxsut="$(awk -F',' -v a="$ws" -v b="$we" 'NR>1 && $1>=a && $1<=b && $3!="" { n++; if($3+0>m) m=$3+0 } END{ if(n>0) printf "%.1f", m; }' "$CPU_LOG" 2>/dev/null || echo '')"
    CPU_MAP="$(jq -c --arg k "$r" --argjson v "${maxcpu:-null}" '. + {($k): $v}' <<<"$CPU_MAP")"
    SUT_CPU_MAP="$(jq -c --arg k "$r" --argjson v "${maxsut:-null}" '. + {($k): $v}' <<<"$SUT_CPU_MAP")"
  done

  # --- healthy_ceiling via the ONE authoritative implementation ----------------
  # Feed this C's sweep as a synthetic run to lib/perf-website-figures.jq and read
  # its headline back. No re-implementation of the ceiling rule here.
  NOW_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  SYN_RUN="$(jq -nc --slurpfile s "$SWEEP_JSON" --arg cpus "$SCPU" --arg ts "$NOW_ISO" \
    '{schema_version:2, timestamp_utc:$ts, config:{}, agent:{server_cpus:$cpus}, sweep:$s[0]}')"
  HEADLINE="$(jq --arg now "$NOW_ISO" --argjson lat_mult 3 --argjson keep 0.95 --arg fix_date "2026-09-16" \
    -f "$FIGURES_JQ" <<<"$SYN_RUN" 2>/dev/null | jq -c '.headline // {}')"
  HC_RPS="$(jq -r '.healthy_ceiling_rps // null' <<<"$HEADLINE")"
  HC_P50="$(jq -r '.healthy_ceiling_p50_ms // null' <<<"$HEADLINE")"
  HC_P95="$(jq -r '.healthy_ceiling_p95_ms // null' <<<"$HEADLINE")"

  # --- rig-valid peak + per-rung ladder (spread, tail suppression, residence) ---
  # rig_valid is perf-test-run.sh's saturation block MINUS the no-drops term: k6 CPU
  # headroom (< 85% of its pin) and error_rate <= eps, but NOT "no dropped iterations".
  # That difference is deliberate and is explained at the $rig_valid line below -- with
  # client CPU headroom, drops mean the SERVER could not keep up, which is the signal
  # this ladder is here to capture rather than a reason to discard the rung. (This
  # comment previously claimed the two blocks matched EXACTLY, which was false and led a
  # reader to treat a valid ceiling reading as a false green because it carried drops.)
  # perf-test-run.sh:1185 does include $no_drops; do not "restore" it here. peak_achieved_rps
  # = max achieved over rig-valid rungs. p95/p99 are SUPPRESSED to null on any rung
  # whose sample_count < MIN_TAIL_SAMPLES (the repo rule) so a low-C, low-rate rung
  # never reports a tail that is really just its max.
  AGG="$(jq -nc \
    --slurpfile sweep "$SWEEP_JSON" \
    --argjson cpu "$CPU_MAP" \
    --argjson sutcpu "$SUT_CPU_MAP" \
    --argjson sutpin "$SUT_PIN_PCT" \
    --argjson pin "$K6_PIN_PCT" \
    --argjson cores "$C" \
    --argjson k6cores "$k6w" \
    --argjson err_eps "$SWEEP_ERR_EPS" \
    --argjson min_tail "$MIN_TAIL_SAMPLES" \
    --argjson maxlog "$ASSUMED_MAX_LOG_ENTRIES" \
    --argjson body "$BODY_BYTES" \
    --argjson hc_rps "${HC_RPS:-null}" \
    --argjson hc_p50 "${HC_P50:-null}" \
    --argjson hc_p95 "${HC_P95:-null}" '
    ($pin * 0.85) as $cpu_ceiling
    | (($sweep[0].points) // []) as $points
    | [ $points[]
        | ($cpu[(.offered_rps|tostring)]) as $c
        | ($sutcpu[(.offered_rps|tostring)]) as $sc
        | (.dropped_iterations // 0) as $drops
        | (.error_rate // 0) as $err
        | (.offered_rps) as $off | (.achieved_rps // 0) as $ach
        | (.sample_count // 0) as $n
        | (($k6cores <= 0) or ($c == null) or ($c <= $cpu_ceiling)) as $headroom
        | ($err <= $err_eps) as $low_err
        # A rung is CLIENT-SOUND (trustworthy as a SERVER figure) iff the k6 client
        # had CPU headroom and was not erroring. Dropped iterations do NOT by
        # themselves invalidate it: with client CPU headroom, drops mean the SERVER
        # could not keep up (VUs blocked on slow responses) — that IS the saturation
        # signal we are looking for, not a client limit. (Proven: at C=1 the 1-core
        # SUT dropped iterations from ~1000 rps up while k6 sat at ~250% of its
        # 1200% pin — the earlier no-drops rule mislabelled that as client-starved
        # and collapsed the peak to the last hiccup-free rung.) Only when the client
        # is AT its CPU pin do drops indicate client VU-starvation.
        | ($headroom and $low_err) as $rig_valid
        # server_saturated: dropped iterations WITH client headroom — the server,
        # not the client, was the limit at this rung.
        | ($drops > 0 and $headroom) as $server_saturated
        | ($n >= $min_tail) as $enough_tail
        | {
            offered_rps:$off, achieved_rps:$ach, sample_count:$n,
            p50_ms:.p50_ms,
            # tail suppression: null below MIN_TAIL_SAMPLES (see comment above).
            p95_ms:(if $enough_tail then .p95_ms else null end),
            p99_ms:(if $enough_tail then .p99_ms else null end),
            error_rate:$err, dropped_iterations:$drops, k6_cpu_pct:$c,
            # SUT CPU% for this rung + how close it ran to its C-core pin. This is
            # the datum that attributes a falling peak: SUT near its pin => server
            # was the ceiling; SUT well below it while achieved fell => the load
            # path / virtualization was the limit, not the server.
            sut_cpu_pct:$sc,
            sut_cpu_frac_of_pin:(if ($sc == null or $sutpin <= 0) then null else (($sc / $sutpin) * 1000 | round) / 1000 end),
            rig_valid:$rig_valid, server_saturated:$server_saturated,
            exclude_reason:(
              if $rig_valid then null
              elif ($headroom|not) then "k6 client CPU \($c)% >= 85% of \($pin)% pin (client bottleneck)"
              else "server error_rate \($err) > \($err_eps)" end),
            # Event-log RESIDENCE for this rung: how long a body lingers in the
            # count-bounded ring before eviction = maxLogEntries / achieved_rps.
            # LENGTHENS as rps falls (long at low C). retained bytes ~ constant.
            retention_residence_s:(if $ach > 0 then (($maxlog / $ach) * 1000 | round) / 1000 else null end),
            # --- VU-pool diagnostics passed through from sweep.js (item 18 open
            # question). vus_active_max is the peak CONCURRENT VUs this rung used —
            # if it is ~1-2 while dropped_iterations>0, the 200-VU pool was NOT the
            # constraint (198 VUs idle) and the drops need another explanation; a max
            # ABOVE the preAllocatedVUs pool is the only per-rung proof the pool grew.
            # (Whole-run pool growth is in the top-level .vus_diagnostics block, not
            # here — k6 pre-inits the pool of every staggered scenario so growth
            # cannot be attributed to one rung.) stall_time_buckets shows WHEN in the rung
            # deep-tail requests fell — clustered => transient stall (the standing
            # hypothesis), uniform => steady limit — a proxy for drop timing (a
            # dropped iteration never runs code, so drops cannot be timestamped).
            vus_active_max:.vus_active_max, vus_active_p95:.vus_active_p95, vus_active_avg:.vus_active_avg,
            stalls:.stalls, stall_ms_threshold:.stall_ms_threshold,
            stall_concurrency_max:.stall_concurrency_max, stall_concurrency_avg:.stall_concurrency_avg,
            stall_time_buckets:.stall_time_buckets
          } ] as $rungs
    | ([ $rungs[] | select(.rig_valid) | .achieved_rps ] | max // 0) as $peak
    | ($rungs | map(select(.rig_valid)) | sort_by(.achieved_rps) | last) as $peakrung
    # The rung the healthy_ceiling landed on (offered == hc_rps). Its OWN client
    # soundness decides whether HC can be trusted as a SERVER figure: the Finding-1
    # definition (reused verbatim) does NOT look at dropped iterations or client CPU,
    # so on a box where the load generator is co-resident the ceiling rung can be
    # client-contended. When that rung dropped iterations or ran the client near its
    # CPU pin, HC reflects the CLIENT limit as much as the server one — surfaced as
    # client_limited_at_ceiling, never silently trusted.
    | ($rungs | map(select(.offered_rps == $hc_rps)) | first) as $hcrung
    | {
        cores:$cores, server_cpus:null, k6_cpus:null, k6_cores:$k6cores,
        available_processors:null,
        # ceiling_rps mirrors the injector per-core shape (chart_inject_percore):
        # the HEALTHY ceiling is the headline serving figure.
        ceiling_rps:$hc_rps,
        healthy_ceiling_rps:$hc_rps,
        healthy_ceiling_p50_ms:$hc_p50,
        healthy_ceiling_p95_ms:$hc_p95,
        # client-soundness of the ceiling rung (see comment above).
        healthy_ceiling_rig_valid:($hcrung.rig_valid // null),
        healthy_ceiling_dropped_iterations:($hcrung.dropped_iterations // null),
        healthy_ceiling_client_cpu_pct:($hcrung.k6_cpu_pct // null),
        client_limited_at_ceiling:(if $hcrung == null then null else ($hcrung.rig_valid | not) end),
        peak_achieved_rps:$peak,
        peak_offered_rps:($peakrung.offered_rps // null),
        rps_per_core:(if $hc_rps == null then null else (($hc_rps / $cores) * 100 | round) / 100 end),
        peak_per_core:(($peak / $cores) * 100 | round) / 100,
        # --- SUT-side CPU: the evidence that attributes where the peak was bound ---
        sut_pin_pct:$sutpin,
        sut_cpu_peak_pct:([ $rungs[] | .sut_cpu_pct | select(. != null) ] | max // null),
        # SUT CPU at the peak-achieved rung, and its fraction of the C-core pin
        # (null when that rung window caught no sample — a sparse/last-rung window).
        sut_cpu_at_peak_pct:($peakrung.sut_cpu_pct // null),
        sut_cpu_frac_of_pin_at_peak:($peakrung.sut_cpu_frac_of_pin // null),
        # Attribution of the ceiling, from the MAX SUT CPU observed across ALL rungs
        # (well-sampled — robust to a single sparse window, unlike the peak rung
        # lone sample). Threshold 0.85 of the C-core pin mirrors the client-headroom
        # test: "server" = the SUT reached ~its pin on at least one rung (it CAN be
        # the ceiling, a real per-core figure); "load_path_or_virtualization" = the
        # SUT NEVER approached its pin on ANY rung while throughput plateaued/fell, so
        # the server had spare CPU throughout and the limit is the load path (k6 + the
        # Docker VM network), NOT MockServer; null when SUT CPU was not sampled at all.
        peak_limited_by:(
          ([ $rungs[] | .sut_cpu_frac_of_pin | select(. != null) ] | max) as $fmax
          | if $fmax == null then null
            elif $fmax >= 0.85 then "server"
            else "load_path_or_virtualization" end),
        retention:{
          assumed_max_log_entries:$maxlog,
          body_bytes:$body,
          retained_bytes_estimate:($maxlog * $body),
          note:"count-bounded ring: retained bytes ~ maxLogEntries*body (≈constant vs rate); residence = maxLogEntries/achieved_rps lengthens as rps falls (see .ladder[].retention_residence_s)"
        },
        # VU-pool diagnostics for this C, straight from sweep.js (item 18). Records
        # the pool CONFIG the sweep ran with (preallocated_vus/max_vus) plus the k6
        # whole-run VU gauges (vus_concurrent_overall_max, vus_initialized_global_max,
        # vus_initialized_baseline and the vus_pool_grew bottom line)
        # so actual pool growth for this C is legible without drilling into the
        # per-rung ladder. Null on an old sweep artifact that predates the fields.
        vus_diagnostics:($sweep[0].vus_diagnostics // null),
        client_pin_pct:$pin,
        ladder:$rungs,
        excluded:[ $rungs[] | select(.rig_valid|not) | {offered_rps, achieved_rps, k6_cpu_pct, dropped_iterations, error_rate, reason:.exclude_reason} ]
      }')"

  # stitch in the shell-known cpusets, the proven processor count, and warm-up.
  AGG="$(jq -c \
    --arg scpu "$SCPU" --arg kcpu "$KCPU" --argjson avail "$AVAIL" \
    --argjson wup_p50 "${WARMUP_P50:-null}" --argjson wup_ach "${WARMUP_ACH:-null}" '
    .server_cpus=$scpu | .k6_cpus=$kcpu | .available_processors=$avail
    | (.ladder[0].p50_ms) as $r1
    | (.ladder[1].p50_ms // null) as $r2
    | .warmup={
        drive_rate:'"$WARMUP_RATE"', drive_p50_ms:$wup_p50, drive_achieved_rps:$wup_ach,
        # first vs second measured rung p50: if the FIRST rung is systematically
        # slower than the second AFTER the warm-up, the transient was not fully
        # removed — surfaced, not averaged away (item 18 warm-up-is-bias rule).
        first_rung_p50_ms:$r1, second_rung_p50_ms:$r2,
        first_rung_slower_than_second:(($r1 != null) and ($r2 != null) and ($r1 > $r2))
      }' <<<"$AGG")"

  echo "    C=$C  healthy_ceiling=${HC_RPS} rps_per_core=$(jq -r '.rps_per_core' <<<"$AGG") peak=$(jq -r '.peak_achieved_rps' <<<"$AGG") sut_cpu@peak=$(jq -r '.sut_cpu_at_peak_pct' <<<"$AGG")%/$(jq -r '.sut_pin_pct' <<<"$AGG")% peak_limited_by=$(jq -r '.peak_limited_by' <<<"$AGG")" >&2
  POINTS+=("$AGG")
  MAX_MEASURED="$C"
done

# --- assemble the serving_percore block ---------------------------------------
POINTS_JSON="$(printf '%s\n' "${POINTS[@]:-}" | jq -sc 'map(select(. != null and . != ""))')"
SKIPPED_JSON="$(printf '%s\n' "${SKIPPED[@]:-}" | jq -sc 'map(select(. != null and . != ""))')"

jq -nc \
  --argjson points "$POINTS_JSON" \
  --argjson skipped "$SKIPPED_JSON" \
  --argjson host_cores "$HOST_CORES" \
  --argjson max_measured "$MAX_MEASURED" \
  --arg ladder "$CORE_LADDER" \
  --arg rates "$SWEEP_RATES" \
  --arg step "$SWEEP_STEP" --arg gap "$SWEEP_GAP" '
  {
    attempted:true,
    proto:"http",
    host_cores:$host_cores,
    cores_requested:($ladder | split(",") | map(tonumber)),
    max_cores_measured:$max_measured,
    # explicit, un-skimmable statement of where the curve ends and why (item 18).
    curve_complete_to_16:(($points | map(.cores) | max // 0) >= 16),
    sweep:{rates:$rates, step:$step, gap:$gap},
    healthy_ceiling_definition:"lib/perf-website-figures.jq headline (Finding 1: highest rung achieved>=0.95*offered, zero errors, p50<=3x flat-region p50) — reused, not re-implemented",
    points:($points | sort_by(.cores)),
    skipped:$skipped
  }' > "$OUT_FILE"

echo "--- serving_percore points=$(jq -r '.points | length' "$OUT_FILE") skipped=$(jq -r '.skipped | length' "$OUT_FILE") max_cores_measured=$(jq -r '.max_cores_measured' "$OUT_FILE")" >&2
