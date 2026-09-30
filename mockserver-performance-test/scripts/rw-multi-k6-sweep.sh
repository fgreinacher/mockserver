#!/usr/bin/env bash
set -euo pipefail
# Top-level only (no errtrace): names the failing step instead of exiting silently.
trap 'echo ":x: rw-multi-k6: failed (exit $?) at line $LINENO: $BASH_COMMAND" >&2' ERR

# rw-multi-k6-sweep.sh [out.json] — the sweep.js ladder from N independent k6 processes
# started at one instant, merged in Prometheus (native histograms). Opt-in, never published;
# see docs/code/performance-measurement.md (item 31) for design, env knobs and degrade tests.
# Invariants: every gate is fail-closed (a failed check -> "valid": false, exit 2), and the
# healthy ceiling and rig validity come from the shared libs, never a copy of their rules.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${PERF_RW_REPO_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
LIB_DIR="$REPO_ROOT/.buildkite/scripts/steps/lib"
FIGURES_JQ="$LIB_DIR/perf-website-figures.jq"
for lib in perf-cpu-topology.sh perf-sweep-window.sh perf-derive-saturation.sh; do
  if [ ! -r "$LIB_DIR/$lib" ]; then
    echo ":x: $LIB_DIR/$lib not found — refusing to run without the shared guard" >&2
    exit 1
  fi
  # shellcheck source=/dev/null
  . "$LIB_DIR/$lib"
done
[ -r "$FIGURES_JQ" ] || { echo ":x: $FIGURES_JQ not found" >&2; exit 1; }

OUT_FILE="${1:-/dev/stdout}"

# --- inputs ------------------------------------------------------------------
MOCKSERVER_IMAGE="${PERF_RW_IMAGE:-${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot-graaljs}}"
K6_IMAGE="${PERF_RW_K6_IMAGE:-grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f}"
PROM_IMAGE="${PERF_RW_PROM_IMAGE:-prom/prometheus:v3.1.0@sha256:6559acbd5d770b15bb3c954629ce190ac3cbbdb2b7f1c30f0385c4e05104e218}"
K6_DIR="$REPO_ROOT/mockserver-performance-test/k6"
HOST_CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"

RATES="${PERF_RW_RATES:-500,1000,2000,4000,8000,16000,24000,32000,36000,40000,44000,48000,64000}"
SWEEP_STEP="${PERF_RW_STEP:-15s}"
SWEEP_GAP="${PERF_RW_GAP:-5s}"
SETTLE_S="${PERF_RW_SETTLE_S:-3}"
PUSH_S="${PERF_RW_PUSH_INTERVAL_S:-1}"
QUIET_S="${PERF_RW_QUIET_S:-$(( PUSH_S * 2 > 5 ? PUSH_S * 2 : 5 ))}"
START_LEAD_S="${PERF_RW_START_LEAD_S:-20}"
MAX_SKEW_MS="${PERF_RW_MAX_SKEW_MS:-100}"
# Slack on the cut-excess bound (requests between a settle boundary and its cut must not
# exceed the offered rate over 2 push intervals).
WINDOW_TOL="${PERF_RW_WINDOW_TOL:-0.05}"
WINDOW_MODE="${PERF_RW_WINDOW_MODE:-wallclock}"
VU_DIAGNOSTICS="${PERF_RW_VU_DIAGNOSTICS:-true}"
ACCOUNT_TOL="${PERF_RW_ACCOUNT_TOL:-0}"
WARMUP_RATE="${PERF_RW_WARMUP_RATE:-2000}"
WARMUP_DURATION="${PERF_RW_WARMUP_DURATION:-10s}"
SERVER_MEMORY="${PERF_RW_MEMORY:-1g}"
DEGRADE="${PERF_RW_DEGRADE:-}"
# Test hook for the fail-closed cut gates: "<proc>:query" corrupts that process's cut
# query; "<proc>:empty" moves its boundary past every sample (a null cut, no failure).
CUT_FAULT="${PERF_RW_TEST_CUT_FAULT:-}"

# Rig-validity tunables — the same names and defaults perf-test-run.sh passes to
# derive_saturation, so both methods judge a rung by one rule.
# shellcheck disable=SC2034  # SWEEP_* are read by lib/perf-derive-saturation.sh
SWEEP_ERR_EPS="${PERF_SWEEP_ERROR_EPS:-0.01}"
# shellcheck disable=SC2034
SWEEP_DROP_TOL="${PERF_SWEEP_DROP_TOL:-0.01}"
# shellcheck disable=SC2034
SWEEP_OCC_KNEE="${PERF_SWEEP_OCC_KNEE:-0.80}"
KEEP="${PERF_RW_KEEP:-0.95}"

# Cross-check: one k6 in the published summary mode (vu_tag window, full summary)
# that ALSO remote-writes, on rungs where a single process is not saturated.
XCHECK="${PERF_RW_XCHECK:-true}"
XCHECK_MAX_RPS="${PERF_RW_XCHECK_MAX_RPS:-24000}"
# Same-request tolerances: native histograms (bucket factor 1.1) resolve a quantile to ~5%,
# and the time cut may move up to 2 push intervals of requests between windows. The
# absolute floor only matters below ~0.05 ms.
XTOL_P50="${PERF_RW_XCHECK_TOL_P50:-0.10}"
XTOL_P95="${PERF_RW_XCHECK_TOL_P95:-0.10}"
XTOL_P99="${PERF_RW_XCHECK_TOL_P99:-0.15}"
XTOL_ABS_MS="${PERF_RW_XCHECK_TOL_ABS_MS:-0.005}"
# Cross-run tolerances (two separate runs of the same rung, so run-to-run noise).
RTOL_ACHIEVED="${PERF_RW_CROSSRUN_TOL_ACHIEVED:-0.02}"
RTOL_P50="${PERF_RW_CROSSRUN_TOL_P50:-0.20}"
RTOL_P95="${PERF_RW_CROSSRUN_TOL_P95:-0.35}"
RTOL_P99="${PERF_RW_CROSSRUN_TOL_P99:-0.60}"

# Target: launch a pinned SUT (default) or drive an existing one on an existing
# docker network (perf-test-run.sh passes its main SUT, so both methods measure it).
NETWORK_IN="${PERF_RW_NETWORK:-}"
TARGET_URL="${PERF_RW_TARGET_URL:-}"
SUT_CONTAINER="${PERF_RW_SUT_CONTAINER:-}"

# Placement. The c5.12xlarge rig has 48 logical cpus, siblings at N and N+24: SUT on
# physical cores 0-5 (siblings idle), Prometheus on 23, four k6 processes on four
# physical cores each (both hyperthreads). Smaller hosts get a proportional layout.
if [ "$HOST_CORES" -ge 48 ]; then
  DEF_SERVER="0-5"; DEF_PROM="23,47"
  DEF_K6="7-10,31-34;11-14,35-38;15-18,39-42;19-22,43-46"
else
  DEF_SERVER="0-3"; DEF_PROM="4"
  _n="${PERF_RW_PROCS:-3}"; _avail=$(( HOST_CORES - 6 )); _w=$(( _avail / _n )); [ "$_w" -lt 1 ] && _w=1
  DEF_K6=""
  for ((i=0;i<_n;i++)); do
    _s=$(( 5 + i * _w )); _e=$(( _s + _w - 1 ))
    DEF_K6="${DEF_K6:+$DEF_K6;}$([ "$_w" -eq 1 ] && echo "$_s" || echo "$_s-$_e")"
  done
fi
SERVER_CPUS="${PERF_RW_SERVER_CPUS:-$DEF_SERVER}"
PROM_CPUS="${PERF_RW_PROM_CPUS:-$DEF_PROM}"
IFS=';' read -ra K6_SETS <<< "${PERF_RW_K6_CPUSETS:-$DEF_K6}"
N="${PERF_RW_PROCS:-${#K6_SETS[@]}}"
if [ "$N" -lt 1 ] || [ "$N" -gt "${#K6_SETS[@]}" ]; then
  echo ":x: PERF_RW_PROCS=$N but only ${#K6_SETS[@]} k6 cpuset(s) given (PERF_RW_K6_CPUSETS, ';'-separated)" >&2
  exit 1
fi
K6_SETS=("${K6_SETS[@]:0:$N}")
XCHECK_CPUS="${PERF_RW_XCHECK_CPUS:-$(IFS=','; echo "${K6_SETS[*]}")}"

# --- validation --------------------------------------------------------------
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
case "$WINDOW_MODE" in wallclock|vu_tag) ;; *) echo ":x: PERF_RW_WINDOW_MODE must be wallclock or vu_tag" >&2; exit 1 ;; esac
[[ "$PUSH_S" =~ ^[1-9][0-9]*$ ]] || { echo ":x: PERF_RW_PUSH_INTERVAL_S must be a whole number of seconds >= 1" >&2; exit 1; }
if [ "$QUIET_S" -lt $(( PUSH_S * 2 )) ]; then
  echo ":x: PERF_RW_QUIET_S=$QUIET_S is below 2x the push interval ($PUSH_S s): the final rung's last pushes could be lost at shutdown" >&2
  exit 1
fi
[ "$SETTLE_S" -lt "$STEP_S" ] || { echo ":x: settle ($SETTLE_S s) must be shorter than the step ($STEP_S s)" >&2; exit 1; }
case "$DEGRADE" in ""|pause_prometheus|stall_prometheus|kill:[0-9]*) ;; *) echo ":x: PERF_RW_DEGRADE must be empty, pause_prometheus, stall_prometheus or kill:<index>" >&2; exit 1 ;; esac

cpu_count() { local c; c="$(expand_cpuset "$1" | wc -w | tr -d ' ')"; echo "${c:-0}"; }

RUN_ID="${BUILDKITE_BUILD_ID:-local}-$$-rw"
PROM_NAME="mockserver-rw-prom-${RUN_ID}"
SUT_NAME="mockserver-rw-sut-${RUN_ID}"
K6_PREFIX="mockserver-rw-k6-${RUN_ID}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-rw.XXXXXX")"
chmod 0777 "$WORK"
ALL_NAMES=""
OWN_NETWORK=""
cleanup() {
  # shellcheck disable=SC2086
  [ -n "$ALL_NAMES" ] && docker rm -f $ALL_NAMES >/dev/null 2>&1 || true
  [ -n "$OWN_NETWORK" ] && docker network rm "$OWN_NETWORK" >/dev/null 2>&1 || true
  if [ -n "${PERF_RW_KEEP_WORK:-}" ]; then echo "--- work dir kept: $WORK" >&2; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

echo "--- item 31 remote-write multi-k6 sweep: N=$N rates=$RATES step=$SWEEP_STEP gap=$SWEEP_GAP settle=${SETTLE_S}s push=${PUSH_S}s quiet=${QUIET_S}s window=$WINDOW_MODE server=$SERVER_CPUS prometheus=$PROM_CPUS k6=${K6_SETS[*]} target=${TARGET_URL:-<launch SUT>} degrade=${DEGRADE:-none}" >&2

# --- placement proof: SUT, Prometheus and every k6 on disjoint physical cores ---
PAIRS=(server "$SERVER_CPUS" prometheus "$PROM_CPUS")
for ((i=0;i<N;i++)); do PAIRS+=("k6_$i" "${K6_SETS[$i]}"); done
if ! cpusets_physically_disjoint "${PAIRS[@]}" >&2; then
  echo ":x: SUT / Prometheus / k6 cpusets are not physically disjoint — refusing to measure contention" >&2
  exit 1
fi

# --- network, Prometheus, SUT --------------------------------------------------
if [ -n "$NETWORK_IN" ]; then
  NETWORK="$NETWORK_IN"
else
  NETWORK="mockserver-rw-${RUN_ID}"
  docker network create "$NETWORK" >/dev/null
  OWN_NETWORK="$NETWORK"
fi

cat > "$WORK/prometheus.yml" <<'EOF'
global:
  scrape_interval: 1h
scrape_configs: []
EOF
chmod 0644 "$WORK/prometheus.yml"
ALL_NAMES="$ALL_NAMES $PROM_NAME"
# Native histograms on; the lookback is long so each rung's final cumulative value is
# readable at any instant after the run without a per-rung query time.
docker run -d --name "$PROM_NAME" --network "$NETWORK" --network-alias "$PROM_NAME" \
  --cpuset-cpus="$PROM_CPUS" -p 127.0.0.1::9090 \
  -v "$WORK/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  "$PROM_IMAGE" --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.path=/prometheus \
  --web.enable-remote-write-receiver --enable-feature=native-histograms \
  --query.lookback-delta=2h >/dev/null
# Resolved per call: a Prometheus restart moves the published host port.
prom_url() { echo "http://$(docker port "$PROM_NAME" 9090/tcp 2>/dev/null | head -1)"; }
PROM_URL="$(prom_url)"
RW_URL="http://${PROM_NAME}:9090/api/v1/write"
for _ in $(seq 1 60); do
  curl -sf "$PROM_URL/-/ready" >/dev/null 2>&1 && break
  sleep 1
done
curl -sf "$PROM_URL/-/ready" >/dev/null || { echo ":x: Prometheus did not become ready" >&2; exit 1; }

wait_ready() {
  local url="$1" code
  for _ in $(seq 1 60); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -X PUT "${url}/mockserver/status" 2>/dev/null || echo 000)"
    [ "$code" = "200" ] && return 0
    sleep 2
  done
  return 1
}

if [ -z "$TARGET_URL" ]; then
  ALL_NAMES="$ALL_NAMES $SUT_NAME"
  docker run -d --name "$SUT_NAME" --network "$NETWORK" --network-alias "$SUT_NAME" \
    --cpuset-cpus="$SERVER_CPUS" --memory="$SERVER_MEMORY" -p 127.0.0.1::1080 \
    -e MOCKSERVER_LOG_LEVEL=ERROR -e MOCKSERVER_DISABLE_SYSTEM_OUT=true -e MOCKSERVER_METRICS_ENABLED=true \
    "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null
  SUT_CONTAINER="$SUT_NAME"
  TARGET_URL="http://${SUT_NAME}:1080"
  CURL_URL="http://$(docker port "$SUT_NAME" 1080/tcp | head -1)"
else
  CURL_URL="${PERF_RW_TARGET_CURL_URL:-$TARGET_URL}"
fi
wait_ready "$CURL_URL" || { echo ":x: SUT not ready at $CURL_URL" >&2; exit 1; }

# --- Prometheus query helpers ------------------------------------------------
# Fail closed without aborting: a failed query is recorded in $PROM_FAILURES (which fails
# the rw_prometheus_queries_ok gate) and reads as an empty result ("[]" / "null"), never "".
PROM_FAILURES="$WORK/prom-query-failures.txt"
: > "$PROM_FAILURES"
promq() { # expr -> JSON result array ("[]" on failure)
  local body out
  if body="$(curl -sf --max-time 30 --data-urlencode "query=$1" "$(prom_url)/api/v1/query")" \
     && out="$(jq -ce '.data.result | arrays' <<<"$body")"; then
    echo "$out"
  else
    echo ":x: Prometheus query failed (container $PROM_NAME down or restarted?): $1" >&2
    echo "$1" >> "$PROM_FAILURES"
    echo "[]"
  fi
}
promv() { # expr -> first scalar value, or "null"
  local out
  out="$(promq "$1" | jq -r 'if length == 0 then "null" else (.[0].value[1] | if . == "NaN" or . == "+Inf" or . == "-Inf" then "null" else . end) end' 2>/dev/null)" || out=""
  nn "$out"
}
nn() { [ -n "$1" ] && echo "$1" || echo null; } # "" -> null for bash-side consumers

usage_usec() { docker exec "$1" cat /sys/fs/cgroup/cpu.stat 2>/dev/null | awk '$1=="usage_usec"{print $2}'; }
sleep_until() { while [ "$(date +%s)" -lt "$1" ]; do sleep 0.2; done; }

# --- one synchronised ladder ---------------------------------------------------
# run_phase <phase> <n> <agg_rates_csv> <window_mode> <lean> <cpusets;...>
# Leaves $WORK/<phase>-p<i>.json (per-process summaries), $WORK/<phase>-meta.json
# and $WORK/<phase>-cpu.csv (`ts name cpu`).
run_phase() {
  local phase="$1" n="$2" agg="$3" wmode="$4" lean="$5" sets="$6"
  local -a set_arr agg_arr
  IFS=';' read -ra set_arr <<< "$sets"
  IFS=',' read -ra agg_arr <<< "$agg"
  local pp="" a
  for a in "${agg_arr[@]}"; do pp="${pp:+$pp,}$(( a / n > 0 ? a / n : 1 ))"; done
  local rungs="${#agg_arr[@]}"
  local start_s=$(( $(date +%s) + START_LEAD_S ))
  local ladder_end_s=$(( start_s + (rungs - 1) * (STEP_S + GAP_S) + STEP_S ))
  local names="" i
  echo "+++ phase=$phase N=$n per_process_rates=$pp window=$wmode lean=$lean start_at=$start_s ladder_end=$ladder_end_s" >&2
  for ((i=0;i<n;i++)); do
    local kname="${K6_PREFIX}-${phase}-p${i}"
    names="${names:+$names }$kname"
    ALL_NAMES="$ALL_NAMES $kname"
    docker run -d --name "$kname" --network "$NETWORK" --cpuset-cpus="${set_arr[$i]}" \
      -v "$K6_DIR:/k6:ro" -v "$WORK:/out" \
      -e "BASE_URL=$TARGET_URL" -e "PROTO=http" \
      -e "K6_SWEEP_RATES=$pp" -e "K6_SWEEP_STEP=${STEP_S}s" -e "K6_SWEEP_GAP=${GAP_S}s" \
      -e "K6_SWEEP_SETTLE=${SETTLE_S}s" -e "K6_SWEEP_RESULT_PATH=/out/${phase}-p${i}.json" \
      -e "K6_SWEEP_WINDOW_MODE=$wmode" -e "K6_SWEEP_LEAN_SUMMARY=$lean" \
      -e "K6_SWEEP_VU_DIAGNOSTICS=$VU_DIAGNOSTICS" \
      -e "K6_SWEEP_START_AT_MS=$(( start_s * 1000 ))" -e "K6_SWEEP_QUIET=${QUIET_S}s" \
      -e "K6_SWEEP_MANAGE_SUT=$([ "$i" -eq 0 ] && echo true || echo false)" \
      -e "K6_PROMETHEUS_RW_SERVER_URL=$RW_URL" \
      -e "K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true" \
      -e "K6_PROMETHEUS_RW_PUSH_INTERVAL=${PUSH_S}s" \
      -e "K6_PROMETHEUS_RW_STALE_MARKERS=false" \
      "$K6_IMAGE" run --quiet --tag "proc=${phase}-p${i}" -o experimental-prometheus-rw /k6/sweep.js >/dev/null
  done

  local cpu_log="$WORK/${phase}-cpu.csv" want="$names $PROM_NAME ${SUT_CONTAINER:-}"
  : > "$cpu_log"
  ( while true; do
      ts="$(date -u +%s)"
      docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' 2>/dev/null \
        | awk -v t="$ts" -v want="$want" '
            BEGIN{ split(want, w, " "); for(k in w) keep[w[k]]=1 }
            ($1 in keep){ gsub(/%/,"",$2); printf "%s %s %s\n", t, $1, $2 }' >> "$cpu_log"
      sleep "${PERF_RW_SAMPLE_INTERVAL:-1}"
    done ) & local sampler=$!

  # cgroup CPU at the ladder's start and end: k6 CPU per request over the load
  # window only (VU initialisation and the summary are excluded).
  ( sleep_until "$start_s"
    for k in $names; do echo "$k start $(usage_usec "$k")"; done
    sleep_until "$ladder_end_s"
    for k in $names; do echo "$k end $(usage_usec "$k")"; done ) > "$WORK/${phase}-cgroup.txt" 2>/dev/null & local cgroup_pid=$!

  local degrade_pid=""
  if [ "$phase" = main ] && [ -n "$DEGRADE" ]; then
    case "$DEGRADE" in
      kill:*)
        local victim="${K6_PREFIX}-${phase}-p${DEGRADE#kill:}"
        ( sleep_until $(( ladder_end_s - 1 )); echo "--- DEGRADE: SIGKILL $victim before its last flush" >&2; docker kill "$victim" >/dev/null 2>&1 || true ) & degrade_pid=$!
        ;;
      pause_prometheus)
        ( sleep_until $(( ladder_end_s - 1 )); echo "--- DEGRADE: pausing Prometheus so the final flushes fail" >&2; docker pause "$PROM_NAME" >/dev/null 2>&1 || true ) & degrade_pid=$!
        ;;
      stall_prometheus)
        # Mid-ladder: stall pushes across a rung's settle boundary, then resume.
        local sr=$(( rungs > 1 ? 1 : 0 )) stall_s=$(( PUSH_S * 4 ))
        local stall_at=$(( start_s + sr * (STEP_S + GAP_S) + SETTLE_S - 1 ))
        ( sleep_until "$stall_at"; echo "--- DEGRADE: stalling Prometheus ${stall_s}s across rung $sr's settle boundary" >&2
          docker pause "$PROM_NAME" >/dev/null 2>&1 || true; sleep "$stall_s"; docker unpause "$PROM_NAME" >/dev/null 2>&1 || true ) & degrade_pid=$!
        ;;
    esac
  fi

  local exits=""
  for k in $names; do exits="$exits $(docker wait "$k" 2>/dev/null || echo 255)"; done
  [ -n "$degrade_pid" ] && wait "$degrade_pid" 2>/dev/null || true
  if [ "$DEGRADE" = pause_prometheus ] && [ "$phase" = main ]; then docker unpause "$PROM_NAME" >/dev/null 2>&1 || true; fi
  wait "$cgroup_pid" 2>/dev/null || true
  kill "$sampler" >/dev/null 2>&1 || true; wait "$sampler" 2>/dev/null || true
  i=0
  for k in $names; do docker logs "$k" > "$WORK/${phase}-p${i}.log" 2>&1 || true; i=$((i+1)); done

  jq -nc --arg phase "$phase" --argjson n "$n" --arg agg "$agg" --arg pp "$pp" --arg sets "$sets" \
    --arg wmode "$wmode" --arg lean "$lean" --argjson start_s "$start_s" --argjson end_s "$ladder_end_s" \
    --arg names "$names" --arg exits "$exits" '
    {phase:$phase, n:$n, window_mode:$wmode, lean_summary:($lean=="true"),
     agg_rates:($agg|split(",")|map(tonumber)), per_process_rates:($pp|split(",")|map(tonumber)),
     cpusets:($sets|split(";")), start_at_s:$start_s, ladder_end_s:$end_s,
     containers:($names|split(" ")), exit_codes:($exits|split(" ")|map(select(.!=""))|map(tonumber))}' > "$WORK/${phase}-meta.json"
}

# --- merge one phase from Prometheus -------------------------------------------
# merge_phase <phase> <window_mode> <out_name>: writes $WORK/<out_name>.json. The
# window mode is a parameter so the cross-check can cut the same requests both ways.
merge_phase() {
  local phase="$1" wmode="$2" out_name="$3" meta="$WORK/$1-meta.json"
  local n; n="$(jq -r '.n' "$meta")"
  local -a agg_arr pp_arr
  IFS=',' read -ra agg_arr <<< "$(jq -r '.agg_rates|join(",")' "$meta")"
  IFS=',' read -ra pp_arr <<< "$(jq -r '.per_process_rates|join(",")' "$meta")"
  local rungs_json="[]" k i
  local procre="${phase}-p[0-9]+"
  for k in "${!agg_arr[@]}"; do
    local r="${pp_arr[$k]}" sel
    sel="proc=~\"${procre}\",rate=\"${r}\""
    # Per-process: Prometheus count vs the process's own count, rung start, settle cut.
    local procs_json="[]"
    local bexpr=""
    for ((i=0;i<n;i++)); do
      local proc="${phase}-p${i}" f="$WORK/${phase}-p${i}.json"
      local summ_count="null" start_ms="null" failed="null" dropped="null" pool="null" setup_late="null"
      if jq -e ".points[$k]" "$f" >/dev/null 2>&1; then
        summ_count="$(jq -r ".points[$k].sample_count // \"null\"" "$f")"
        start_ms="$(jq -r ".points[$k].start_epoch_ms // \"null\"" "$f")"
        failed="$(jq -r ".points[$k].failed_count // \"null\"" "$f")"
        dropped="$(jq -r ".points[$k].dropped_iterations // \"null\"" "$f")"
        pool="$(jq -r ".vus_diagnostics.pool_per_rung[\"$r\"] // \"null\"" "$f")"
        setup_late="$(jq -r 'if .wallclock.start_at_ms and .wallclock.setup_end_ms then (.wallclock.setup_end_ms - .wallclock.start_at_ms) else "null" end' "$f")"
      fi
      local prom_count prom_hist_count
      prom_count="$(promv "sum(k6_http_reqs_total{proc=\"$proc\",rate=\"$r\"})")"
      prom_hist_count="$(promv "sum(histogram_count(k6_http_req_duration_seconds{proc=\"$proc\",rate=\"$r\"}))")"
      # Settle cut: the dominant series' first pushed sample at or after (rung start +
      # settle), so a sparse error series cannot set it. It never reaches back into the
      # settle window; cut_excess counts the requests it drops from the steady window.
      local cut_s="null" boundary_s="null" cut_excess="null"
      if [ "$wmode" = wallclock ] && [ "$start_ms" != null ]; then
        boundary_s="$(awk -v s="$start_ms" -v st="$SETTLE_S" 'BEGIN{printf "%.3f", s/1000 + st}')"
        local cut_query_suffix=""
        [ "$CUT_FAULT" = "$proc:query" ] && cut_query_suffix=")"
        [ "$CUT_FAULT" = "$proc:empty" ] && boundary_s="$(awk -v b="$boundary_s" 'BEGIN{printf "%.3f", b + 100000}')"
        local span=$(( STEP_S + GAP_S + QUIET_S + 60 ))
        local end_eval
        end_eval="$(awk -v s="$start_ms" -v sp="$span" 'BEGIN{printf "%.3f", s/1000 + sp}')"
        cut_s="$(promq "k6_http_reqs_total{proc=\"$proc\",rate=\"$r\"}[${span}s] @ ${end_eval}${cut_query_suffix}" \
          | jq -r --argjson b "$boundary_s" 'if length == 0 then "null" else
              (max_by(.values[-1][1] | tonumber) | [ .values[] | .[0] | select(. >= $b) ] | min // "null") end')" || cut_s=""
        cut_s="$(nn "$cut_s")"
        if [ "$cut_s" != null ]; then
          bexpr="${bexpr:+$bexpr or }(k6_http_req_duration_seconds{proc=\"$proc\",rate=\"$r\"} @ ${cut_s})"
          local at_cut at_boundary
          at_cut="$(promv "sum(k6_http_reqs_total{proc=\"$proc\",rate=\"$r\"} @ ${cut_s})")"
          at_boundary="$(promv "sum(k6_http_reqs_total{proc=\"$proc\",rate=\"$r\"} @ ${boundary_s})")"
          # No sample at or before the boundary is a counter still at 0; a FAILED query is
          # caught by rw_prometheus_queries_ok. A missing value at the cut leaves cut_excess null.
          [ "$at_boundary" = null ] && at_boundary=0
          if [ "$at_cut" != null ]; then
            cut_excess="$(nn "$(awk -v a="$at_cut" -v b="$at_boundary" 'BEGIN{printf "%d", a - b}')")"
          fi
        fi
      fi
      procs_json="$(jq -c --arg proc "$proc" --argjson sc "$summ_count" --argjson pc "$prom_count" \
        --argjson hc "$prom_hist_count" --argjson st "$start_ms" --argjson fl "$failed" --argjson dr "$dropped" \
        --argjson pool "$pool" --argjson b "$boundary_s" --argjson cut "$cut_s" --argjson tol "$ACCOUNT_TOL" \
        --argjson sl "$setup_late" --argjson cx "$cut_excess" --argjson rate "$r" \
        --argjson push "$PUSH_S" --argjson wtol "$WINDOW_TOL" --arg wmode "$wmode" '
        . + [{proc:$proc, summary_count:$sc, prometheus_count:$pc, prometheus_histogram_count:$hc,
              rung_start_epoch_ms:$st, failed_count:$fl, dropped_iterations:$dr, pool:$pool,
              drop_fraction:(if $dr == null or $sc == null or ($dr + $sc) == 0 then null else (($dr / ($dr + $sc)) * 100000 | round) / 100000 end),
              setup_end_minus_start_at_ms:$sl,
              settle_boundary_s:$b, settle_cut_s:$cut,
              settle_cut_late_ms:(if $cut==null or $b==null then null else ((($cut-$b)*1000)|round) end),
              # Requests completed between the boundary and the cut, i.e. dropped from the
              # steady window; bounded by the offered rate over 2 push intervals.
              cut_excess_requests:$cx,
              cut_excess_max:(($rate * 2 * $push * (1 + $wtol)) | floor),
              # Wall-clock mode needs a measured cut and excess; a missing one fails, never passes.
              cut_measured:($wmode != "wallclock" or ($cut != null and $cx != null)),
              cut_excess_ok:($wmode != "wallclock" or ($cx != null and $cx <= ($rate * 2 * $push * (1 + $wtol)))),
              accounted:($sc != null and $pc != null and $hc != null
                         and (($pc - $sc)|fabs) <= $tol and (($hc - $sc)|fabs) <= $tol)}]' <<<"$procs_json")"
    done

    # Merged histograms: A = whole rung (final cumulative), B = up to each process's cut.
    local a_expr="sum(k6_http_req_duration_seconds{${sel}})" s_expr b_count
    if [ "$wmode" = wallclock ]; then
      if [ -n "$bexpr" ]; then
        s_expr="(${a_expr} - sum(${bexpr}))"
        b_count="$(promv "histogram_count(sum(${bexpr}))")"
      else
        s_expr="$a_expr"; b_count=0
      fi
    else
      s_expr="sum(k6_http_req_duration_seconds{${sel},win=\"${r}_steady\"})"
      b_count="$(promv "histogram_count(sum(k6_http_req_duration_seconds{${sel},win=\"${r}_settle\"}))")"
    fi
    local q vals="{}"
    for q in 0.5 0.9 0.95 0.99 0.999; do
      vals="$(jq -c --arg q "$q" --argjson v "$(promv "histogram_quantile($q, ${s_expr}) * 1000")" '. + {($q):$v}' <<<"$vals")"
      vals="$(jq -c --arg q "full_$q" --argjson v "$(promv "histogram_quantile($q, ${a_expr}) * 1000")" '. + {($q):$v}' <<<"$vals")"
    done
    local a_count s_count vus_p95="null" vus_max="null"
    a_count="$(promv "histogram_count(${a_expr})")"
    s_count="$(promv "histogram_count(${s_expr})")"
    if [ "$VU_DIAGNOSTICS" = true ]; then
      # Occupancy is per process (each has its own pool), so take the busiest process.
      vus_p95="$(nn "$(promq "histogram_quantile(0.95, sum by (proc) (k6_sweep_vus_active{${sel}}))" | jq -r '[.[].value[1]|tonumber] | max // "null"' || true)")"
      vus_max="$(nn "$(promq "histogram_quantile(1, sum by (proc) (k6_sweep_vus_active{${sel}}))" | jq -r '[.[].value[1]|tonumber] | max // "null"' || true)")"
    fi
    rungs_json="$(jq -c --argjson agg "${agg_arr[$k]}" --argjson r "$r" --argjson n "$n" --argjson procs "$procs_json" \
      --argjson v "$vals" --argjson ac "$a_count" --argjson sc "$s_count" --argjson bc "${b_count:-null}" \
      --argjson step "$STEP_S" --argjson vp95 "$vus_p95" --argjson vmax "$vus_max" --argjson maxskew "$MAX_SKEW_MS" \
      --argjson push "$PUSH_S" '
      def r3: if . == null then null else (. * 1000 | round) / 1000 end;
      ([ $procs[] | .rung_start_epoch_ms | select(. != null) ]) as $starts
      | ([ $procs[] | .summary_count // 0 ] | add) as $summ_total
      | ([ $procs[] | .failed_count // 0 ] | add) as $failed
      | ([ $procs[] | .dropped_iterations // 0 ] | add) as $dropped
      | (if ($starts|length) == $n then (($starts|max) - ($starts|min)) else null end) as $skew
      | . + [{
          offered_rps: ($r * $n),
          nominal_agg_offered_rps: $agg,
          achieved_rps: (if $ac == null then null else (($ac / $step) * 10 | round) / 10 end),
          p50_ms: ($v["0.5"]|r3), p90_ms: ($v["0.9"]|r3), p95_ms: ($v["0.95"]|r3),
          p99_ms: ($v["0.99"]|r3), p999_ms: ($v["0.999"]|r3),
          phase_ms: null,
          sample_count: $ac,
          measured_sample_count: $sc,
          settle_excluded: $bc,
          full_rung_ms: {p50_ms: ($v["full_0.5"]|r3), p95_ms: ($v["full_0.95"]|r3),
                         p99_ms: ($v["full_0.99"]|r3), p999_ms: ($v["full_0.999"]|r3)},
          error_rate: (if $summ_total > 0 then (($failed / $summ_total) * 100000 | round) / 100000 else 0 end),
          dropped_iterations: $dropped,
          vus_active_p95: $vp95, vus_active_max: $vmax,
          per_process_pool: ([ $procs[] | .pool ] | max),
          start_skew_ms: $skew,
          skew_ok: ($skew != null and $skew <= $maxskew),
          accounting_ok: (($procs | length) == $n and all($procs[]; .accounted)),
          # The cut may land at most 2 push intervals after its boundary (none in vu_tag mode).
          settle_cut_ok: all($procs[]; .settle_cut_s == null
                                 or (.settle_cut_late_ms >= 0 and .settle_cut_late_ms <= 2 * $push * 1000)),
          cut_measured: all($procs[]; .cut_measured),
          cut_excess_ok: all($procs[]; .cut_excess_ok),
          per_process: $procs
        }]' <<<"$rungs_json")"
  done
  jq -nc --argjson pts "$rungs_json" --slurpfile meta "$meta" --arg w "$wmode" \
    '{meta:($meta[0] + {merged_window_mode:$w}), points:$pts}' > "$WORK/${out_name}.json"
}

# Cgroup CPU us/request per process over the ladder window.
cpu_cost_json() { # phase
  local phase="$1" meta="$WORK/$1-meta.json" out="[]" i=0 k
  for k in $(jq -r '.containers[]' "$meta"); do
    local u0 u1 reqs
    u0="$(awk -v k="$k" '$1==k && $2=="start"{print $3}' "$WORK/${phase}-cgroup.txt")"
    u1="$(awk -v k="$k" '$1==k && $2=="end"{print $3}' "$WORK/${phase}-cgroup.txt")"
    reqs="$(jq -r '[.points[].sample_count // 0] | add // 0' "$WORK/${phase}-p${i}.json" 2>/dev/null || echo 0)"
    out="$(jq -c --arg c "$k" --argjson u0 "${u0:-null}" --argjson u1 "${u1:-null}" --argjson n "${reqs:-0}" '
      . + [{container:$c, cpu_usec:(if $u0==null or $u1==null then null else ($u1-$u0) end), requests:$n,
            cpu_us_per_request:(if $u0==null or $u1==null or $n<=0 then null else ((($u1-$u0)/$n)*10|round)/10 end)}]' <<<"$out")"
    i=$((i+1))
  done
  echo "$out"
}

# --- warm-up (never measured) --------------------------------------------------
if [ "$(to_secs "$WARMUP_DURATION")" -gt 0 ]; then
  echo "--- warm-up: ${WARMUP_RATE} rps for $WARMUP_DURATION (not measured)" >&2
  docker run --rm --network "$NETWORK" --cpuset-cpus="$XCHECK_CPUS" -v "$K6_DIR:/k6:ro" -v "$WORK:/out" \
    -e "BASE_URL=$TARGET_URL" -e "PROTO=http" -e "K6_SWEEP_RATES=$WARMUP_RATE" -e "K6_SWEEP_STEP=$WARMUP_DURATION" \
    -e "K6_SWEEP_GAP=1s" -e "K6_SWEEP_SETTLE=0s" -e "K6_SWEEP_RESULT_PATH=/out/warmup.json" \
    "$K6_IMAGE" run --quiet /k6/sweep.js >/dev/null 2>&1 || true
fi

# --- cross-check phase: one k6, published summary mode, also remote-writing ------
XRATES=""
if [ "$XCHECK" = true ]; then
  IFS=',' read -ra _all <<< "$RATES"
  for a in "${_all[@]}"; do [ "$a" -le "$XCHECK_MAX_RPS" ] && XRATES="${XRATES:+$XRATES,}$a"; done
  XRATES="${PERF_RW_XCHECK_RATES:-$XRATES}"
fi
if [ -n "$XRATES" ]; then
  run_phase xcheck 1 "$XRATES" vu_tag false "$XCHECK_CPUS"
  merge_phase xcheck vu_tag xcheck-by-tag
  merge_phase xcheck wallclock xcheck-by-time
fi

run_phase main "$N" "$RATES" "$WINDOW_MODE" true "$(IFS=';'; echo "${K6_SETS[*]}")"
merge_phase main "$WINDOW_MODE" main-merged

# --- assemble the sweep.json-shaped result --------------------------------------
MAIN_META="$WORK/main-meta.json"
jq --argjson settle "$SETTLE_S" --argjson step "$STEP_S" --arg w "$WINDOW_MODE" '
  {proto:"http",
   latency_window:{settle_s:$settle, measured_s:($step-$settle), mode:$w, cut:"prometheus"},
   points:[ .points[] | del(.per_process, .nominal_agg_offered_rps, .per_process_pool, .start_skew_ms, .skew_ok, .accounting_ok, .settle_cut_ok, .cut_excess_ok, .cut_measured) ],
   vus_diagnostics:{pool_per_rung:(reduce .points[] as $p ({}; . + {($p.offered_rps|tostring): $p.per_process_pool})),
                    note:"pool_per_rung is PER PROCESS, keyed by the aggregate offered rate; vus_active_p95 is the busiest process"}}
' "$WORK/main-merged.json" > "$WORK/sweep.json"

WINDOW_MISMATCH=""
WINDOW_MISMATCH="$(sweep_window_mismatches "$WORK/sweep.json")" || true

# Rig validity through the published rule: the worst k6 process's CPU, scaled to a
# common pin, stands in for the single client (every process must have headroom).
K6_CORES="$(cpu_count "${K6_SETS[0]}")"
K6_PIN_PCT=$(( K6_CORES * 100 ))
# Physical cores of that pin, so derive_saturation lowers its CPU ceiling for hyperthread pairs.
# shellcheck disable=SC2034  # read by derive_saturation
K6_PHYS_CORES="$(phys_core_count "${K6_SETS[0]}")"; K6_PHYS_CORES="${K6_PHYS_CORES:-null}"
PINS=""
for ((i=0;i<N;i++)); do PINS="${PINS:+$PINS }${K6_PREFIX}-main-p${i}=$(( $(cpu_count "${K6_SETS[$i]}") * 100 ))"; done
{ echo "ts,cpu_pct"
  awk -v pins="$PINS" -v ref="$K6_PIN_PCT" '
    BEGIN{ n=split(pins, a, " "); for(i=1;i<=n;i++){ split(a[i], kv, "="); pin[kv[1]]=kv[2] } }
    ($2 in pin) && $3 != "" { v=$3*ref/pin[$2]; if(!($1 in m) || v>m[$1]) m[$1]=v }
    END{ for(t in m) printf "%s,%.2f\n", t, m[t] }' "$WORK/main-cpu.csv" | sort -n
} > "$WORK/main-k6-cpu.csv"
# shellcheck disable=SC2034  # read by derive_saturation
SWEEP_RATES="$(jq -r '[.points[].offered_rps] | join(",")' "$WORK/sweep.json")"
# CPU windows start from the earliest MEASURED rung-0 start, not the requested instant.
T0_S="$(jq -r '[.points[0].per_process[].rung_start_epoch_ms | select(. != null)]
  | if length == 0 then "null" else (min / 1000 | floor) end' "$WORK/main-merged.json")"
[ "$T0_S" = null ] && T0_S="$(jq -r '.start_at_s' "$MAIN_META")"
SATURATION_JSON="$(derive_saturation "$WORK/sweep.json" "$WORK/main-k6-cpu.csv" "$T0_S")"
# derive_saturation reads a rung with no CPU sample as 0% (headroom), so count them here.
RUNG_COUNT="$(jq '.points | length' "$WORK/sweep.json")"
CPU_SAMPLES="[]"
for ((k=0;k<RUNG_COUNT;k++)); do
  ws=$(( T0_S + k * (STEP_S + GAP_S) + SETTLE_S )); we=$(( T0_S + k * (STEP_S + GAP_S) + STEP_S ))
  cnt="$(awk -F',' -v a="$ws" -v b="$we" 'NR>1 && $1>=a && $1<=b {n++} END{print n+0}' "$WORK/main-k6-cpu.csv")"
  CPU_SAMPLES="$(jq -c --argjson c "$cnt" '. + [$c]' <<<"$CPU_SAMPLES")"
done

NOW_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SYNTH="$(jq -nc --slurpfile s "$WORK/sweep.json" --argjson sat "$SATURATION_JSON" --arg ts "$NOW_ISO" \
  --argjson scpus "$(cpu_count "$SERVER_CPUS")" '
  {schema_version:2, timestamp_utc:$ts, config:{}, agent:{server_cpus:$scpus}, sweep:$s[0], saturation:$sat}')"
HEADLINE="$(jq -c --arg now "$NOW_ISO" --argjson lat_mult 3 --argjson keep "$KEEP" --arg fix_date "2026-09-16" \
  -f "$FIGURES_JQ" <<<"$SYNTH" | jq -c '.headline')"

# --- per-process, CPU and Prometheus cost ---------------------------------------
cpu_stats() { # csv name from_s to_s -> {mean,max,samples}
  awk -v n="$2" -v a="$3" -v b="$4" '$2==n && $1>=a && $1<=b && $3!="" {s+=$3; c++; if($3+0>m) m=$3+0}
    END{ if(c) printf "{\"mean\":%.1f,\"max\":%.1f,\"samples\":%d}", s/c, m, c; else printf "{\"mean\":null,\"max\":null,\"samples\":0}" }' "$1"
}
L0="$(jq -r '.start_at_s' "$MAIN_META")"; L1="$(jq -r '.ladder_end_s' "$MAIN_META")"
MAIN_COST="$(cpu_cost_json main)"
PER_PROCESS="[]"
for ((i=0;i<N;i++)); do
  c="${K6_PREFIX}-main-p${i}"
  pin=$(( $(cpu_count "${K6_SETS[$i]}") * 100 ))
  per_rung="[]"
  for ((k=0;k<RUNG_COUNT;k++)); do
    ws=$(( T0_S + k * (STEP_S + GAP_S) + SETTLE_S )); we=$(( T0_S + k * (STEP_S + GAP_S) + STEP_S ))
    per_rung="$(jq -c --argjson st "$(cpu_stats "$WORK/main-cpu.csv" "$c" "$ws" "$we")" --argjson pin "$pin" \
      --argjson k "$k" --argjson i "$i" --slurpfile m "$WORK/main-merged.json" '
      . + [{offered_rps:$m[0].points[$k].offered_rps, cpu_pct_mean:$st.mean, cpu_pct_max:$st.max, cpu_samples:$st.samples,
            cpu_frac_of_pin:(if $st.mean == null then null else (($st.mean / $pin) * 1000 | round) / 1000 end),
            drop_fraction:$m[0].points[$k].per_process[$i].drop_fraction}]' <<<"$per_rung")"
  done
  PER_PROCESS="$(jq -c --argjson i "$i" --arg set "${K6_SETS[$i]}" --argjson pin "$pin" --argjson per_rung "$per_rung" \
    --argjson cpu "$(cpu_stats "$WORK/main-cpu.csv" "$c" "$L0" "$L1")" --argjson cost "$MAIN_COST" \
    --argjson exit "$(jq ".exit_codes[$i] // null" "$MAIN_META")" --slurpfile s <(cat "$WORK/main-p${i}.json" 2>/dev/null || echo 'null') '
    . + [{index:$i, cpuset:$set, pin_pct:$pin, exit_code:$exit,
          summary_present:($s[0] != null),
          cpu_pct_over_ladder:$cpu, cpu_us_per_request:$cost[$i].cpu_us_per_request, requests:$cost[$i].requests,
          setup_end_minus_start_at_ms:(if $s[0].wallclock.start_at_ms and $s[0].wallclock.setup_end_ms
                                       then ($s[0].wallclock.setup_end_ms - $s[0].wallclock.start_at_ms) else null end),
          per_rung:$per_rung}]' <<<"$PER_PROCESS")"
done
SUT_CPU="$( [ -n "$SUT_CONTAINER" ] && cpu_stats "$WORK/main-cpu.csv" "$SUT_CONTAINER" "$L0" "$L1" || echo '{"mean":null,"max":null}')"
PROM_CPU="$(cpu_stats "$WORK/main-cpu.csv" "$PROM_NAME" "$L0" "$L1")"
# Send failures and slow flushes both invalidate: k6 warns a slow flush may drop samples.
RW_FAILURES="$( { grep -hiE 'failed to send|level=error.*remote write' "$WORK"/*-p*.log 2>/dev/null || true; } | wc -l | tr -d ' ')"
SLOW_FLUSHES="$( { grep -hoE 'took [0-9.]+(ms|s) while flush period' "$WORK"/*-p*.log 2>/dev/null || true; } \
  | awk '{d=$2; v=d+0; if (d ~ /ms$/) v=v/1000; n++; if (v>m) m=v} END{printf "{\"count\":%d,\"max_took_s\":%s}", n, (n ? sprintf("%.3f", m) : "null")}')"

# --- cross-check ------------------------------------------------------------------
CROSS='{"attempted":false}'
if [ -n "$XRATES" ]; then
  CROSS="$(jq -nc --slurpfile pub "$WORK/xcheck-p0.json" --slurpfile bytime "$WORK/xcheck-by-time.json" \
    --slurpfile bytag "$WORK/xcheck-by-tag.json" --slurpfile main "$WORK/main-merged.json" \
    --argjson xcost "$(cpu_cost_json xcheck)" \
    --argjson t50 "$XTOL_P50" --argjson t95 "$XTOL_P95" --argjson t99 "$XTOL_P99" --argjson abs "$XTOL_ABS_MS" \
    --argjson ra "$RTOL_ACHIEVED" --argjson r50 "$RTOL_P50" --argjson r95 "$RTOL_P95" --argjson r99 "$RTOL_P99" '
    def within($a; $b; $tol): ($a != null and $b != null) and ((($a - $b)|fabs) <= ([($tol * ([$a, $b]|max)), $abs]|max));
    def cmp($x; $y; $t5; $t9; $t99): {
        p50:{published:$x.p50_ms, remote_write:$y.p50_ms, ok:within($x.p50_ms; $y.p50_ms; $t5)},
        p95:{published:$x.p95_ms, remote_write:$y.p95_ms, ok:within($x.p95_ms; $y.p95_ms; $t9)},
        p99:{published:$x.p99_ms, remote_write:$y.p99_ms, ok:within($x.p99_ms; $y.p99_ms; $t99)}};
    ($pub[0].points) as $P
    | [ range(0; $P|length) as $k
        | ($P[$k]) as $x | ($bytime[0].points[$k]) as $t | ($bytag[0].points[$k]) as $g
        | {offered_rps:$x.offered_rps,
           counts:{published:$x.sample_count, prometheus:$t.sample_count, ok:($x.sample_count == $t.sample_count)},
           measured_counts:{published:$x.measured_sample_count, by_tag:$g.measured_sample_count, by_time:$t.measured_sample_count},
           by_time:cmp($x; $t; $t50; $t95; $t99),
           by_tag:cmp($x; $g; $t50; $t95; $t99)}
        | . + {ok:(.counts.ok and .by_time.p50.ok and .by_time.p95.ok and .by_time.p99.ok)} ] as $same
    | [ $main[0].points[] as $m
        | ($P[] | select(.offered_rps == $m.nominal_agg_offered_rps)) as $x
        | {offered_rps:$x.offered_rps, main_offered_rps:$m.offered_rps,
           achieved_ratio:{single:(($x.achieved_rps/$x.offered_rps)*1000|round/1000), multi:(($m.achieved_rps/$m.offered_rps)*1000|round/1000),
                           ok:((($x.achieved_rps/$x.offered_rps) - ($m.achieved_rps/$m.offered_rps))|fabs <= $ra)},
           latency:cmp($x; $m; $r50; $r95; $r99)}
        | . + {ok:(.achieved_ratio.ok and .latency.p50.ok and .latency.p95.ok and .latency.p99.ok)} ] as $run
    | {attempted:true,
       note:"same_requests: one k6 in the published summary mode ALSO remote-writing; its summary percentiles vs the Prometheus merge cut by time (the method under test) and by VU tag (histogram resolution only). cross_run: the N-process rungs vs that single-process run at the same aggregate offered rate (separate runs, so run-to-run noise).",
       tolerances:{same_requests:{p50:$t50, p95:$t95, p99:$t99, abs_ms:$abs},
                   cross_run:{achieved_ratio:$ra, p50:$r50, p95:$r95, p99:$r99, abs_ms:$abs}},
       same_requests:{rungs:$same, equivalent:(($same|length) > 0 and all($same[]; .ok)),
                      accounting_ok:all($bytime[0].points[]; .accounting_ok)},
       cross_run:{rungs:$run, agrees:(($run|length) > 0 and all($run[]; .ok))},
       single_process_cpu_us_per_request:$xcost[0].cpu_us_per_request}
    | . + {equivalent:(.same_requests.equivalent and .same_requests.accounting_ok and .cross_run.agrees)}')"
fi

# --- validity (fail closed) ---------------------------------------------------------
VALIDITY="$(jq -nc --slurpfile m "$WORK/main-merged.json" --argjson pp "$PER_PROCESS" --arg wm "$WINDOW_MISMATCH" \
  --argjson cross "$CROSS" --argjson rwfail "${RW_FAILURES:-0}" --argjson cpusamples "$CPU_SAMPLES" \
  --argjson slow "$SLOW_FLUSHES" \
  --argjson promfail "$(wc -l < "$PROM_FAILURES" | tr -d ' ')" --arg promfirst "$(head -1 "$PROM_FAILURES")" \
  --argjson push "$PUSH_S" '
  def check($name; $ok; $detail): {name:$name, ok:$ok, detail:(if $ok then "ok" else $detail end)};
  ($m[0].points) as $P
  | [ check("rw_accounting"; all($P[]; .accounting_ok);
        "Prometheus request counts differ from the processes own counts (a lost remote-write flush or a missing process summary) at " + ([ $P[] | select(.accounting_ok|not) | "\(.offered_rps) [" + ([ .per_process[] | select(.accounted|not) | "\(.proc) summary=\(.summary_count) prometheus=\(.prometheus_count) hist=\(.prometheus_histogram_count)" ] | join(", ")) + "]" ] | join("; "))),
      check("rw_processes_exited_cleanly"; all($pp[]; .summary_present and .exit_code == 0);
        ([ $pp[] | select((.summary_present and .exit_code == 0)|not) | "p\(.index) exit=\(.exit_code) summary=\(.summary_present)" ] | join(", "))),
      check("rw_start_skew_within_bound"; all($P[]; .skew_ok);
        "over bound (or unknown: a process reported no rung start) at " + ([ $P[] | select(.skew_ok|not) | "\(.offered_rps): \(.start_skew_ms // "unknown") ms" ] | join(", "))),
      check("rw_settle_cut_within_bound"; all($P[]; .settle_cut_ok);
        "a settle cut landed before its boundary or more than \(2 * $push) s after it at " + ([ $P[] | select(.settle_cut_ok|not) | "\(.offered_rps): late_ms=\([ .per_process[] | .settle_cut_late_ms ])" ] | join(", "))),
      check("rw_settle_cut_measured"; all($P[]; .cut_measured);
        ([ $P[] | . as $p | .per_process[] | select(.cut_measured|not)
           | "cut excess could not be measured for proc \(.proc) rung \($p.offered_rps) (cut=\(.settle_cut_s), excess=\(.cut_excess_requests))" ] | join("; "))),
      check("rw_settle_cut_excess_bounded"; all($P[]; .cut_excess_ok);
        "the settle cut excess is missing or exceeds 2 push intervals at the offered rate at " + ([ $P[] | select(.cut_excess_ok|not) | "\(.offered_rps): " + ([ .per_process[] | select(.cut_excess_ok|not) | "\(.proc) excess=\(.cut_excess_requests) > \(.cut_excess_max)" ] | join(", ")) ] | join("; "))),
      check("rw_prometheus_queries_ok"; $promfail == 0;
        "\($promfail) Prometheus quer(y/ies) failed, so merged figures are missing; first: \($promfirst)"),
      check("rw_cpu_sampled_every_rung"; all($cpusamples[]; . > 0);
        "no k6 CPU sample in the steady window of rung index(es) " + ([ range(0; $cpusamples|length) | select($cpusamples[.] == 0) | tostring ] | join(", "))),
      check("rw_window_accounts_every_request"; $wm == ""; $wm),
      check("rw_cross_check_same_requests"; (($cross.attempted | not) or ($cross.same_requests.equivalent and $cross.same_requests.accounting_ok));
        "the same requests measured by the published summary and by the Prometheus merge disagree beyond tolerance (see cross_check.same_requests)"),
      check("rw_no_failed_pushes"; $rwfail == 0; "\($rwfail) k6 log line(s) report a remote-write send failure"),
      check("rw_no_slow_flushes"; $slow.count == 0;
        "\($slow.count) remote-write flush(es) took longer than the \($push) s push interval (max \($slow.max_took_s) s); k6 warns samples may be dropped")
    ] as $checks
  | {valid:all($checks[]; .ok), checks:$checks,
     reasons:[ $checks[] | select(.ok|not) | "\(.name): \(.detail)" ]}')"

# --- output -------------------------------------------------------------------------
jq -n --argjson synth "$SYNTH" --argjson headline "$HEADLINE" --slurpfile m "$WORK/main-merged.json" \
  --argjson validity "$VALIDITY" --argjson cross "$CROSS" --argjson pp "$PER_PROCESS" \
  --argjson sutcpu "$SUT_CPU" --argjson promcpu "$PROM_CPU" --argjson n "$N" --argjson push "$PUSH_S" \
  --argjson quiet "$QUIET_S" --argjson lead "$START_LEAD_S" --argjson maxskew "$MAX_SKEW_MS" \
  --arg wmode "$WINDOW_MODE" --arg vudiag "$VU_DIAGNOSTICS" --arg degrade "$DEGRADE" \
  --arg scpus "$SERVER_CPUS" --arg pcpus "$PROM_CPUS" --arg k6img "$K6_IMAGE" --arg promimg "$PROM_IMAGE" \
  --arg msimg "$MOCKSERVER_IMAGE" --argjson host_cores "$HOST_CORES" --argjson acct_tol "$ACCOUNT_TOL" \
  --argjson cpusamples "$CPU_SAMPLES" --argjson t0 "$T0_S" \
  --argjson slow "$SLOW_FLUSHES" --argjson rwfail "${RW_FAILURES:-0}" '
  ($m[0].points) as $P
  | $synth + {
      rig_valid_peak_achieved_rps: $synth.saturation.rig_valid_peak_achieved_rps,
      saturation_rps: $synth.saturation.saturation_rps,
      # An invalid run carries no headline; the computed one stays for diagnosis.
      headline: (if $validity.valid then $headline else null end),
      headline_if_valid: $headline,
      valid: $validity.valid,
      invalid_reasons: $validity.reasons,
      validity: {valid: $validity.valid, checks: $validity.checks},
      method: {
        method: "remote_write_multi_k6", procs: $n, push_interval_s: $push, quiet_s: $quiet,
        window_mode: $wmode, lean_summary: true, vu_diagnostics: ($vudiag == "true"),
        start_lead_s: $lead, max_start_skew_ms_bound: $maxskew,
        observed_max_start_skew_ms: ([ $P[] | .start_skew_ms | select(. != null) ] | max),
        max_setup_end_minus_start_at_ms: ([ $pp[] | .setup_end_minus_start_at_ms | select(. != null) ] | max),
        native_histograms: true, histogram_bucket_factor: 1.1, accounting_tolerance: $acct_tol,
        server_cpus: $scpus, prometheus_cpus: $pcpus, host_cores: $host_cores,
        k6_image: $k6img, prometheus_image: $promimg, mockserver_image: $msimg,
        cpu_window_t0_s: $t0,
        degrade: (if $degrade == "" then null else $degrade end)
      },
      accounting: [ $P[] | {offered_rps, ok:.accounting_ok, per_process:[ .per_process[] | {proc, summary_count, prometheus_count, prometheus_histogram_count, accounted} ]} ],
      windows: [ $P[] | {offered_rps, start_skew_ms, skew_ok, settle_cut_ok,
                         settle_cut_late_ms:[ .per_process[] | .settle_cut_late_ms ],
                         cut_excess_requests:[ .per_process[] | .cut_excess_requests ], cut_excess_ok,
                         measured_sample_count, settle_excluded} ]
               | [ range(0; length) as $k | .[$k] + {k6_cpu_samples: $cpusamples[$k]} ],
      per_process: $pp,
      remote_write: {send_failures: $rwfail, slow_flushes: $slow},
      cpu: {sut_pct_over_ladder:$sutcpu, prometheus_pct_over_ladder:$promcpu,
            k6_cpu_us_per_request_mean: ([ $pp[] | .cpu_us_per_request | select(. != null) ] | if length == 0 then null else (add / length * 10 | round) / 10 end)},
      cross_check: $cross
    }' > "$WORK/result.json"
cp "$WORK/result.json" "$OUT_FILE"

echo "--- rw-multi-k6: valid=$(jq -r '.valid' "$WORK/result.json") healthy_ceiling=$(jq -r '.headline_if_valid.healthy_ceiling_rps // "null"' "$WORK/result.json") rig_valid_peak=$(jq -r '.rig_valid_peak_achieved_rps' "$WORK/result.json") skew_max_ms=$(jq -r '.method.observed_max_start_skew_ms' "$WORK/result.json") k6_us_per_req=$(jq -r '.cpu.k6_cpu_us_per_request_mean' "$WORK/result.json") cross_check_equivalent=$(jq -r '.cross_check.equivalent | if . == null then "n/a" else tostring end' "$WORK/result.json")" >&2
if [ "$(jq -r '.valid' "$WORK/result.json")" != true ]; then
  echo ":x: remote-write multi-k6 run INVALID:" >&2
  jq -r '.invalid_reasons[] | "    - " + .' "$WORK/result.json" >&2
  exit 2
fi
