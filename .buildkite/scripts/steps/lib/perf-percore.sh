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
#   * rig_valid_peak_achieved_rps — max achieved over RIG-VALID rungs (client had CPU
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
#
# HARDWARE MATRIX MODE (item 27): PERF_PERCORE_MODE=hw_matrix runs the same procedure
# over PERF_HW_MATRIX ("cores:memory[:control]") and emits `serving_hw_matrix`. Each point
# also sets a container memory limit (no swap, no -Xmx, as in a user's container) and
# records resolved heap/log bounds, peak memory, OOM state and any lower-bound reasons.
# ---------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${PERF_PERCORE_REPO_ROOT:-$(cd "$SCRIPT_DIR/../../../.." && pwd)}"
FIGURES_JQ="$SCRIPT_DIR/perf-website-figures.jq"
if [ -r "$SCRIPT_DIR/perf-sweep-window.sh" ]; then
  # shellcheck source=SCRIPTDIR/perf-sweep-window.sh
  . "$SCRIPT_DIR/perf-sweep-window.sh"
else
  echo ":x: sweep latency-window check lib not found at $SCRIPT_DIR/perf-sweep-window.sh" >&2
  exit 1
fi
# shellcheck source=perf-cpu-topology.sh
. "$SCRIPT_DIR/perf-cpu-topology.sh"

OUT_FILE="${1:-/dev/stdout}"

MODE="${PERF_PERCORE_MODE:-percore}"
case "$MODE" in
  percore|hw_matrix) ;;
  *) echo "ERROR: PERF_PERCORE_MODE='$MODE' (expected percore or hw_matrix)" >&2; exit 2 ;;
esac

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
# a non-monotonic curve). So the client gets ALL spare cores by default: one thread on
# every physical core the SUT does not use, minus a small reserve for the
# kernel/docker/sampler (see select_cpusets). K6_MIN_CORES is the floor below which a rung
# is skipped (the client would be too weak to be trusted); K6_MAX_CORES defaults to
# the host count (no artificial cap).
K6_MIN_CORES="${PERF_PERCORE_K6_MIN_CORES:-2}"
K6_MAX_CORES="${PERF_PERCORE_K6_MAX_CORES:-$HOST_CORES}"
# Cores held back from BOTH server and client for the kernel, dockerd and the CPU
# sampler, so neither the SUT nor k6 contends with the box's own overhead.
K6_RESERVE="${PERF_PERCORE_RESERVE_CORES:-1}"

# Sweep ladder + timing per C. Kept overridable.
#
# The default is DELIBERATELY NOT a doubling ladder. healthy_ceiling_rps is defined as the highest
# RUNG whose achieved rate held at or above keep x offered (see the derivation below), so the
# ceiling can only ever take a rung's value and its resolution IS the rung spacing. On the old
# doubling ladder (250,500,1000,2000,4000,8000,16000,32000) that meant a factor-of-two uncertainty:
# a server whose true ceiling is 7,900 rps reported 4,000.
#
# That is not hypothetical. Build #358 reported 4000 / 8000 / 4000 / 4000 across C=1/2/4/8 and the
# non-monotonicity read like a server curve; it was quantisation noise. Build #364 re-ran with the
# ladder below and measured 6000 flat at every core count - and 6000 IS NOT A RUNG of the doubling
# ladder, so the old default would have reported 4000 again: a 50% understatement of the same
# server. The refinement paid for itself on its first run.
#
# Cost is one rung x (step + gap) each, so the sweep grows from ~8.5 to ~15 minutes across the four
# feasible core counts - cheap for the first curve this item can actually interpret. The extra
# resolution is concentrated in the 2k-8k region where the ceilings actually cluster.
SWEEP_RATES="${PERF_PERCORE_SWEEP_RATES:-500,1000,1500,2000,3000,4000,5000,6000,7000,8000,10000,12000,16000,32000}"
# The matrix ladder spaces rungs 6-17% apart from 8,000 req/s up and 12-100% below
# that, so a ceiling is only resolved to one rung (the memory verdict allows for it).
# Each point offers only rates up to MAX_RPS_PER_CORE x cores (0 = no cap); a ceiling
# on a point's top rung is flagged ladder_top_reached (a lower bound).
if [ "$MODE" = hw_matrix ]; then
  SWEEP_RATES="${PERF_HW_MATRIX_SWEEP_RATES:-1000,2000,3000,4000,5000,6000,7000,8000,9000,10000,11000,12000,14000,16000,18000,20000,22000,24000,26000,28000,32000,36000,40000,44000,48000,52000,56000,60000,64000,68000,72000}"
fi
HW_MAX_RPS_PER_CORE="${PERF_HW_MATRIX_MAX_RPS_PER_CORE:-20000}"
HW_MATRIX_SPEC="${PERF_HW_MATRIX:-1:512m,2:1g,2:2g:control,4:2g,6:2g,8:2g}"
HW_WARMUP_RPS_PER_CORE="${PERF_HW_MATRIX_WARMUP_RPS_PER_CORE:-1000}"
SWEEP_STEP="${PERF_PERCORE_SWEEP_STEP:-12s}"
SWEEP_GAP="${PERF_PERCORE_SWEEP_GAP:-4s}"
# Empty by default: sweep.js then sizes a fixed pool per rung. If set, both must be
# set and equal (sweep.js refuses a pre/max VU ramp — the Finding-3 invariant).
SWEEP_PRE_VUS="${PERF_PERCORE_SWEEP_PRE_VUS:-}"
SWEEP_MAX_VUS="${PERF_PERCORE_SWEEP_MAX_VUS:-}"
WARMUP_RATE="${PERF_PERCORE_WARMUP_RATE:-500}"
WARMUP_DURATION="${PERF_PERCORE_WARMUP_DURATION:-8s}"

SERVER_MEMORY="${PERF_PERCORE_MEMORY:-1g}"
# Event-log retention parameters for the per-rung residence arithmetic. The default mirrors
# the product's min(heapKB/8, 250000) on the image's MaxRAMPercentage heap of SERVER_MEMORY,
# read from the image's ENTRYPOINT; 45 (the GraalJS image this rig runs) if it cannot be read.
percore_image_heap_pct() {
  local pct
  pct="$(docker image inspect -f '{{json .Config.Entrypoint}}' "$MOCKSERVER_IMAGE" 2>/dev/null \
    | grep -oE 'MaxRAMPercentage=[0-9]+' | head -1 | cut -d= -f2 || true)"
  echo "${pct:-45}"
}
percore_default_max_log_entries() {
  local mem="$1" bytes
  case "$mem" in
    *[gG]) bytes=$(( ${mem%[gG]} * 1024 * 1024 * 1024 )) ;;
    *[mM]) bytes=$(( ${mem%[mM]} * 1024 * 1024 )) ;;
    *) echo 250000; return ;;
  esac
  local entries=$(( (bytes * $(percore_image_heap_pct) / 100 / 1024 - 20480) / 8 ))
  [ "$entries" -gt 250000 ] && entries=250000
  [ "$entries" -lt 1000 ] && entries=1000
  echo "$entries"
}
# "512m" / "2g" -> bytes; empty on anything else (validated by the matrix parser).
mem_to_bytes() {
  case "$1" in
    *[gG]) echo $(( ${1%[gG]} * 1024 * 1024 * 1024 )) ;;
    *[mM]) echo $(( ${1%[mM]} * 1024 * 1024 )) ;;
    *) echo "" ;;
  esac
}

# The point list: parallel arrays of cores / memory limit / control flag. percore
# mode is the core ladder at one memory; hw_matrix mode is PERF_HW_MATRIX. A malformed
# entry fails the step loudly (exit 2) rather than measuring a matrix nobody asked for.
P_CORES=(); P_MEM=(); P_CONTROL=()
if [ "$MODE" = hw_matrix ]; then
  IFS=',' read -ra _hw_entries <<< "$HW_MATRIX_SPEC"
  for _e in "${_hw_entries[@]}"; do
    IFS=':' read -r _c _m _f _extra <<< "$_e"
    if ! [[ "$_c" =~ ^[1-9][0-9]*$ ]] || ! [[ "$_m" =~ ^[1-9][0-9]*[mMgG]$ ]] \
       || { [ -n "${_f:-}" ] && [ "$_f" != control ]; } || [ -n "${_extra:-}" ]; then
      echo "ERROR: PERF_HW_MATRIX entry '$_e' is not cores:memory[:control] (e.g. 2:1g or 2:2g:control)" >&2
      exit 2
    fi
    _m="$(printf '%s' "$_m" | tr 'MG' 'mg')"
    for _i in "${!P_CORES[@]}"; do
      if [ "${P_CORES[$_i]}" = "$_c" ] && [ "$(mem_to_bytes "${P_MEM[$_i]}")" = "$(mem_to_bytes "$_m")" ]; then
        echo "ERROR: PERF_HW_MATRIX lists ${_c}:${_m} twice — each point needs a distinct cores:memory pair" >&2
        exit 2
      fi
    done
    P_CORES+=("$_c"); P_MEM+=("$_m")
    if [ "${_f:-}" = control ]; then P_CONTROL+=(true); else P_CONTROL+=(false); fi
  done
else
  IFS=',' read -ra _pc_cores <<< "$CORE_LADDER"
  for _c in "${_pc_cores[@]}"; do P_CORES+=("$_c"); P_MEM+=("$SERVER_MEMORY"); P_CONTROL+=(false); done
fi

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

if [ "$MODE" = hw_matrix ]; then
  echo "--- item 27 hardware matrix: host_cores=$HOST_CORES matrix=$HW_MATRIX_SPEC k6=[${K6_MIN_CORES}..${K6_MAX_CORES}] image=$MOCKSERVER_IMAGE" >&2
else
  echo "--- item 18 serving per-core: host_cores=$HOST_CORES ladder=$CORE_LADDER k6=[${K6_MIN_CORES}..${K6_MAX_CORES}] image=$MOCKSERVER_IMAGE" >&2
fi

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

# Where sysfs topology is readable (the Linux perf host), the SUT takes C logical cpus
# on C DISTINCT physical cores and k6 takes one thread on each remaining physical core
# (minus the reserve), so the client never runs on the SUT's hyperthread siblings; on
# the c5.12xlarge (siblings N / N+24) that is SUT 0..C-1 and k6 C..22. Where it is not
# readable (a macOS Docker Desktop run) it falls back to logical ids, labelled unverified.
TOPO_KNOWN=false
HOST_PHYS_CORES=null
if phys_core_key 0 >/dev/null 2>&1; then
  TOPO_KNOWN=true
  HOST_PHYS_CORES="$(phys_core_count "0-$((HOST_CORES-1))")"
fi
# echoes "<server cpuset> <k6 cpuset> <k6 core count>"; k6 cpuset "-" when none fit.
select_cpusets() {
  local c="$1" cpu key s="" k="" skeys="" kkeys="" sn=0 kn=0 klist=() i
  if [ "$TOPO_KNOWN" != true ]; then
    local w=$(( HOST_CORES - c - K6_RESERVE ))
    [ "$w" -gt "$K6_MAX_CORES" ] && w="$K6_MAX_CORES"
    if [ "$c" -eq 1 ]; then s="0"; else s="0-$((c-1))"; fi
    if [ "$w" -lt 1 ]; then echo "$s - $w"; return; fi
    if [ "$w" -eq 1 ]; then k="$c"; else k="$c-$((c+w-1))"; fi
    echo "$s $k $w"; return
  fi
  for ((cpu=0; cpu<HOST_CORES; cpu++)); do
    key="$(phys_core_key "$cpu")" || continue
    if [ "$sn" -lt "$c" ]; then
      grep -qxF "$key" <<<"$skeys" && continue
      skeys="$skeys$key"$'\n'; s="${s:+$s,}$cpu"; sn=$((sn+1))
    else
      grep -qxF "$key" <<<"$skeys" && continue
      grep -qxF "$key" <<<"$kkeys" && continue
      kkeys="$kkeys$key"$'\n'; klist+=("$cpu"); kn=$((kn+1))
    fi
  done
  kn=$(( kn - K6_RESERVE )); [ "$kn" -gt "$K6_MAX_CORES" ] && kn="$K6_MAX_CORES"
  [ "$sn" -lt "$c" ] && kn=0
  for ((i=0; i<kn; i++)); do k="${k:+$k,}${klist[$i]}"; done
  echo "${s:--} ${k:--} $kn"
}

# The SUT container's end state: still running, OOM-killed, exit code, restarts, when it
# stopped (epoch, null while running) and how many OutOfMemoryError lines it logged.
sut_state_json() {
  local st fa fa_epoch oome
  st="$(docker inspect --format '{{.State.Running}};{{.State.OOMKilled}};{{.State.ExitCode}};{{.RestartCount}};{{.State.FinishedAt}}' "$SERVER" 2>/dev/null || echo 'unknown;unknown;;;')"
  fa="$(cut -d';' -f5 <<<"$st")"; fa_epoch=""
  case "$fa" in ""|0001-*) ;; *)
    fa_epoch="$(date -u -d "$fa" +%s 2>/dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%S' "${fa%%.*}" +%s 2>/dev/null || echo '')" ;;
  esac
  oome="$(docker logs "$SERVER" 2>&1 | grep -c 'OutOfMemoryError' || true)"
  jq -nc --arg run "$(cut -d';' -f1 <<<"$st")" --arg oom "$(cut -d';' -f2 <<<"$st")" \
    --arg ec "$(cut -d';' -f3 <<<"$st")" --arg rc "$(cut -d';' -f4 <<<"$st")" \
    --arg fae "$fa_epoch" --arg oome "${oome:-0}" '
    {running:(if $run == "unknown" then null else ($run == "true") end),
     oom_killed:(if $oom == "unknown" then null else ($oom == "true") end),
     exit_code:($ec | tonumber? // null), restart_count:($rc | tonumber? // null),
     finished_at_epoch:($fae | tonumber? // null), java_oom_errors:($oome | tonumber? // 0)}'
}

# Heap ceiling and event-log bounds as the running SUT reports them (null when a gauge
# is absent, e.g. an image that predates it).
resolved_bounds_json() {
  local m
  m="$(curl -s --max-time 5 "$METRICS_URL" 2>/dev/null || true)"
  awk '
    /^jvm_memory_max_bytes\{area="heap"\} /                 { h=$2 }
    /^mock_server_event_log_max_retained_entries /           { e=$2 }
    /^mock_server_event_log_max_retained_bytes /             { b=$2 }
    /^mock_server_event_log_max_in_flight_bytes /            { f=$2 }
    /^mock_server_event_log_ring_capacity /                  { r=$2 }
    function num(v) { return (v == "" || v + 0 <= 0) ? "null" : sprintf("%.0f", v) }
    END { printf "{\"max_heap_bytes\":%s,\"max_log_entries\":%s,\"max_event_log_bytes\":%s,\"max_in_flight_bytes\":%s,\"ring_capacity\":%s,\"source\":\"sut-metrics\"}\n",
            num(h), num(e), num(b), num(f), num(r) }' <<<"$m"
}

POINTS=()     # per-point aggregate JSON objects
SKIPPED=()    # {cores, reason, type, ...} for every requested-but-unmeasured point
MAX_MEASURED=0

for PI in "${!P_CORES[@]}"; do
  C="${P_CORES[$PI]}"; MEM="${P_MEM[$PI]}"; IS_CONTROL="${P_CONTROL[$PI]}"
  MEM_BYTES="$(mem_to_bytes "$MEM")"
  if [ "$MODE" = hw_matrix ]; then
    PKEY="${C}c-${MEM}"; LBL="C=$C mem=$MEM"; [ "$IS_CONTROL" = true ] && LBL="$LBL (control)"
  else
    PKEY="${C}c"; LBL="C=$C"
  fi
  POINT_META="$(jq -nc --argjson c "$C" --arg mem "$MEM" --argjson memb "${MEM_BYTES:-null}" \
    --argjson ctl "$IS_CONTROL" --arg key "$PKEY" --arg mode "$MODE" '
    if $mode == "hw_matrix" then {cores:$c, key:$key, memory_limit:$mem, memory_limit_bytes:$memb, control:$ctl}
    else {cores:$c, memory_limit:$mem, memory_limit_bytes:$memb} end')"
  ASSUMED_MAX_LOG_ENTRIES="${PERF_PERCORE_MAX_LOG_ENTRIES:-$(percore_default_max_log_entries "$MEM")}"

  # Only offer rates up to MAX_RPS_PER_CORE x C in hw_matrix mode (see SWEEP_RATES).
  POINT_RATES="$SWEEP_RATES"
  if [ "$MODE" = hw_matrix ] && [ "$HW_MAX_RPS_PER_CORE" -gt 0 ]; then
    POINT_RATES="$(tr ',' '\n' <<<"$SWEEP_RATES" | awk -v cap="$(( HW_MAX_RPS_PER_CORE * C ))" '$1+0 <= cap' | paste -sd, -)"
    [ -n "$POINT_RATES" ] || POINT_RATES="$SWEEP_RATES"
  fi
  POINT_WARMUP_RATE="$WARMUP_RATE"
  if [ "$MODE" = hw_matrix ] && [ -z "${PERF_PERCORE_WARMUP_RATE:-}" ]; then
    POINT_WARMUP_RATE=$(( HW_WARMUP_RPS_PER_CORE * C ))
  fi

  # Feasibility: the SUT needs C cores AND k6 needs >= K6_MIN_CORES on DISJOINT
  # cores (with K6_RESERVE held back for the box). The whole point of item 18's
  # prerequisite: at C=16 on a <18-core box this is false and the point is skipped
  # with a reason rather than measured wrong (shared cores) or silently omitted.
  read -r SCPU KCPU k6w <<<"$(select_cpusets "$C")"
  if [ "$C" -gt "$HOST_CORES" ] || [ "$SCPU" = "-" ] || [ "$KCPU" = "-" ] || [ "$k6w" -lt "$K6_MIN_CORES" ]; then
    reason="needs ${C} SUT cores + >=${K6_MIN_CORES} disjoint client cores + ${K6_RESERVE} reserved; host has ${HOST_CORES} logical / ${HOST_PHYS_CORES} physical"
    echo "--- $LBL SKIPPED: $reason" >&2
    SKIPPED+=("$(jq -c --arg r "$reason" '. + {reason:$r, type:"infeasible"}' <<<"$POINT_META")")
    continue
  fi
  if ! cpusets_physically_disjoint server "$SCPU" k6 "$KCPU" >&2; then
    SKIPPED+=("$(jq -c '. + {reason:"server and k6 cpusets share a physical core", type:"failure"}' <<<"$POINT_META")")
    continue
  fi
  SPHYS="$(phys_core_count "$SCPU")"

  echo "+++ $LBL  server_cpus=$SCPU  k6_cpus=$KCPU (${k6w} client cores)  rates=$POINT_RATES" >&2

  # --- pinning proof: the SUT image's JVM, on the server cpuset -----------------
  AVAIL="$(probe_processors "$SCPU" || true)"
  echo "    availableProcessors (SUT image JVM @ cpuset $SCPU) = ${AVAIL:-unknown}" >&2
  if [ "${AVAIL:-0}" != "$C" ]; then
    echo "ERROR: pin proof FAILED at $LBL — JVM reported '${AVAIL:-unknown}' processors, expected $C. --cpuset-cpus did not take; refusing to record a mislabelled point." >&2
    SKIPPED+=("$(jq -c --arg r "pin proof failed: JVM saw ${AVAIL:-unknown} processors, expected ${C}" '. + {reason:$r, type:"failure"}' <<<"$POINT_META")")
    continue
  fi

  # --- start the SUT pinned to C cores, memory-limited ---------------------------
  # No --rm: an OOM-killed or crashed SUT must stay inspectable so the point records
  # it rather than vanishing. --memory-swap equal to --memory means no swap, so the
  # limit is the whole budget (as on a Kubernetes pod); no -Xmx, so the image's
  # MaxRAMPercentage sizes the heap from the limit exactly as a user's container.
  docker rm -f "$SERVER" >/dev/null 2>&1 || true
  docker run -d --name "$SERVER" --network "$NETWORK" --network-alias mockserver \
    --cpuset-cpus="$SCPU" --memory="$MEM" --memory-swap="$MEM" -p 127.0.0.1::1080 \
    -e MOCKSERVER_LOG_LEVEL=ERROR -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
    -e MOCKSERVER_METRICS_ENABLED=true \
    "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null

  HOSTPORT="$(docker port "$SERVER" 1080/tcp 2>/dev/null | head -1)"
  HOSTPORT="${HOSTPORT:-127.0.0.1:1080}"
  METRICS_URL="http://${HOSTPORT}/mockserver/metrics"

  # --- readiness: a listening port is NOT readiness. MockServer accepts then
  # RESETS during init, so poll PUT /mockserver/status (unauthenticated, present
  # in all versions) until it answers 200. ------------------------------------
  ready=false
  for _ in $(seq 1 60); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -X PUT "http://${HOSTPORT}/mockserver/status" 2>/dev/null || echo 000)"
    if [ "$code" = "200" ]; then ready=true; break; fi
    if ! grep -q "^${SERVER}$" <<<"$(docker ps --format '{{.Names}}' || true)"; then
      echo "ERROR: SUT container exited during startup at $LBL" >&2; docker logs "$SERVER" 2>&1 | tail -20 >&2 || true; break
    fi
    sleep 2
  done
  if [ "$ready" != true ]; then
    STATE_JSON="$(sut_state_json)"
    echo "ERROR: SUT not ready at $LBL — skipping this point (state: $STATE_JSON)" >&2
    SKIPPED+=("$(jq -c --argjson st "$STATE_JSON" '. + {reason:(if $st.oom_killed == true then "SUT was OOM-killed during startup" else "SUT did not become ready (PUT /mockserver/status != 200)" end), type:"failure", oom_killed:$st.oom_killed, sut_state:$st}' <<<"$POINT_META")")
    docker rm -f "$SERVER" >/dev/null 2>&1 || true
    continue
  fi

  # --- resolved heap and event-log bounds, from the SUT's own gauges -------------
  # These are what the JVM and MockServer actually applied under this memory limit,
  # not a formula: the heap ceiling the JVM reports and the event log's count/byte bounds.
  RESOLVED_JSON="$(resolved_bounds_json)"
  echo "    resolved: $RESOLVED_JSON" >&2

  # Measure the served body size once (for the retention arithmetic) — seed /simple
  # and GET it. sweep.js re-seeds/reset in setup()/teardown(), so this is only for
  # the byte count; it does not perturb the measured window.
  curl -s --max-time 5 -X PUT "http://${HOSTPORT}/mockserver/expectation" \
    -H 'Content-Type: application/json' \
    -d '[{"httpRequest":{"path":"/simple"},"httpResponse":{"statusCode":200,"body":"simple"},"times":{"unlimited":true}}]' \
    -o /dev/null 2>/dev/null || true
  # `|| true` throughout: a SUT that dies here (e.g. OOM-killed right after start) must
  # be recorded as a point, not abort the whole matrix under set -e / pipefail.
  BODY_BYTES="$(curl -s --max-time 5 "http://${HOSTPORT}/simple" 2>/dev/null | wc -c | tr -d ' ' || true)"
  BODY_BYTES="${BODY_BYTES:-6}"

  # --- warm-up drive (NEVER measured): remove the JIT/first-touch transient so
  # the sweep's first rung is not systematically slow. ------------------------
  WARMUP_JSON="$WORK/warmup-${PKEY}.json"
  docker run --rm --network "$NETWORK" --cpuset-cpus="$KCPU" \
    -v "$K6_DIR:/k6:ro" -v "$WORK:/out" \
    -e "BASE_URL=http://mockserver:1080" -e "PROTO=http" \
    -e "K6_SWEEP_RATES=$POINT_WARMUP_RATE" -e "K6_SWEEP_STEP=$WARMUP_DURATION" -e "K6_SWEEP_GAP=1s" \
    -e "K6_SWEEP_SETTLE=0s" -e "K6_SWEEP_RESULT_PATH=/out/warmup-${PKEY}.json" \
    ${SWEEP_PRE_VUS:+-e "K6_SWEEP_PRE_VUS=$SWEEP_PRE_VUS"} ${SWEEP_MAX_VUS:+-e "K6_SWEEP_MAX_VUS=$SWEEP_MAX_VUS"} \
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
  # The SUT's memory use and retained log entries ride the same interval, so a point
  # records how close it ran to its memory limit and whether the log filled.
  CPU_LOG="$WORK/cpu-${PKEY}.csv"
  echo "ts,k6_cpu_pct,sut_cpu_pct,sut_mem_bytes,retained_entries,retained_bytes" > "$CPU_LOG"
  ( while true; do
      ts="$(date -u +%s)"
      stats="$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' "$K6_NAME" "$SERVER" 2>/dev/null || echo '')"
      k6c="$(printf '%s\n' "$stats" | awk -v n="$K6_NAME" '$1==n{gsub(/%/,"",$2); print $2}')"
      sutc="$(printf '%s\n' "$stats" | awk -v n="$SERVER" '$1==n{gsub(/%/,"",$2); print $2}')"
      sutm="$(printf '%s\n' "$stats" | awk -v n="$SERVER" '
        function tob(s,  n, u) { n = s; sub(/[A-Za-z]+$/, "", n); u = s; sub(/^[0-9.]+/, "", u)
          if (u == "KiB" || u == "kB" || u == "KB") return n * 1024
          if (u == "MiB" || u == "MB") return n * 1048576
          if (u == "GiB" || u == "GB") return n * 1073741824
          return n }
        $1==n { printf "%.0f", tob($3) }')"
      retl="$(curl -s --max-time 1 "$METRICS_URL" 2>/dev/null | awk '
        /^mock_server_event_log_retained_entries / { e = sprintf("%.0f", $2) }
        /^mock_server_event_log_retained_bytes /   { b = sprintf("%.0f", $2) }
        END { printf "%s,%s", e, b }' || true)"
      printf '%s,%s,%s,%s,%s\n' "$ts" "${k6c:-}" "${sutc:-}" "${sutm:-}" "${retl:-,}" >> "$CPU_LOG"
      sleep "$SWEEP_SAMPLE_INTERVAL"
    done ) & SAMPLER_PID=$!

  SWEEP_JSON="$WORK/sweep-${PKEY}.json"
  T0="$(date -u +%s)"
  docker run --rm --name "$K6_NAME" --network "$NETWORK" --cpuset-cpus="$KCPU" \
    -v "$K6_DIR:/k6:ro" -v "$WORK:/out" \
    -e "BASE_URL=http://mockserver:1080" -e "PROTO=http" \
    -e "K6_SWEEP_RATES=$POINT_RATES" -e "K6_SWEEP_STEP=$SWEEP_STEP" -e "K6_SWEEP_GAP=$SWEEP_GAP" \
    -e "K6_SWEEP_SETTLE=${SWEEP_SETTLE_S}s" -e "K6_SWEEP_RESULT_PATH=/out/sweep-${PKEY}.json" \
    ${SWEEP_PRE_VUS:+-e "K6_SWEEP_PRE_VUS=$SWEEP_PRE_VUS"} ${SWEEP_MAX_VUS:+-e "K6_SWEEP_MAX_VUS=$SWEEP_MAX_VUS"} \
    "$K6_IMAGE" run --quiet /k6/sweep.js >&2 || true
  kill "$SAMPLER_PID" >/dev/null 2>&1 || true
  wait "$SAMPLER_PID" 2>/dev/null || true

  # --- did the SUT survive? (OOM kill / crash / JVM OutOfMemoryError) ----------
  STATE_JSON="$(sut_state_json)"
  docker rm -f "$SERVER" >/dev/null 2>&1 || true
  if [ "$(jq -r '.running' <<<"$STATE_JSON")" != true ] || [ "$(jq -r '.java_oom_errors' <<<"$STATE_JSON")" != 0 ]; then
    echo "WARNING: SUT did not survive the sweep cleanly at $LBL: $STATE_JSON" >&2
  fi

  if ! jq -e '.points | length > 0' "$SWEEP_JSON" >/dev/null 2>&1; then
    echo "ERROR: sweep produced no points at $LBL" >&2
    SKIPPED+=("$(jq -c --argjson st "$STATE_JSON" '. + {reason:(if $st.oom_killed == true then "SUT was OOM-killed and the sweep produced no points" else "sweep produced no points" end), type:"failure", oom_killed:$st.oom_killed, sut_state:$st}' <<<"$POINT_META")")
    continue
  fi
  if ! WINDOW_MISMATCH="$(sweep_window_mismatches "$SWEEP_JSON")"; then
    echo "ERROR: sweep latency windows inconsistent at $LBL — $WINDOW_MISMATCH" >&2
    SKIPPED+=("$(jq -c --arg r "latency windows inconsistent: $WINDOW_MISMATCH" '. + {reason:$r, type:"failure"}' <<<"$POINT_META")")
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
  IFS=',' read -ra RATE_ARR <<< "$POINT_RATES"
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
  MEM_PEAK="$(awk -F',' 'NR>1 && $4!="" { n++; if($4+0>m) m=$4+0 } END{ if(n>0) printf "%.0f", m; }' "$CPU_LOG" 2>/dev/null || echo '')"
  RETAINED_PEAK="$(awk -F',' 'NR>1 && $5!="" { n++; if($5+0>m) m=$5+0 } END{ if(n>0) printf "%.0f", m; }' "$CPU_LOG" 2>/dev/null || echo '')"
  RETAINED_BYTES_PEAK="$(awk -F',' 'NR>1 && $6!="" { n++; if($6+0>m) m=$6+0 } END{ if(n>0) printf "%.0f", m; }' "$CPU_LOG" 2>/dev/null || echo '')"
  # The rung the SUT stopped in, from its FinishedAt against the ladder schedule.
  DIED_AT_RPS=null; DIED_BEFORE_SWEEP=false
  _fae="$(jq -r '.finished_at_epoch // empty' <<<"$STATE_JSON")"
  if [ -n "$_fae" ] && [ "$(jq -r '.running' <<<"$STATE_JSON")" != true ]; then
    if [ "$_fae" -lt "$T0" ]; then
      DIED_BEFORE_SWEEP=true
    else
      _idx=$(( (_fae - T0) / (STEP_S + GAP_S) ))
      [ "$_idx" -ge "${#RATE_ARR[@]}" ] && _idx=$(( ${#RATE_ARR[@]} - 1 ))
      DIED_AT_RPS="${RATE_ARR[$_idx]}"
    fi
  fi

  # --- healthy_ceiling via the ONE authoritative implementation ----------------
  # Feed this C's sweep as a synthetic run to lib/perf-website-figures.jq and read
  # its headline back. No re-implementation of the ceiling rule here.
  NOW_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  SYN_RUN="$(jq -nc --slurpfile s "$SWEEP_JSON" --arg cpus "$SCPU" --arg ts "$NOW_ISO" \
    '{schema_version:2, timestamp_utc:$ts, config:{}, agent:{server_cpus:$cpus}, sweep:$s[0]}')"
  HEADLINE="$(jq --arg now "$NOW_ISO" --argjson lat_mult 3 --argjson keep 0.95 --arg fix_date "2026-09-16" \
    -f "$FIGURES_JQ" <<<"$SYN_RUN" 2>/dev/null | jq -c '.headline // {}' || echo '{}')"
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
  # perf-test-run.sh:1185 does include $no_drops; do not "restore" it here. rig_valid_peak_achieved_rps
  # = max achieved over rig-valid rungs. p95/p99 are SUPPRESSED to null on any rung
  # whose post-settle sample count < MIN_TAIL_SAMPLES (the repo rule) so a low-C, low-rate rung
  # never reports a tail that is really just its max.
  MAXLOG_USED="$(jq -r '.max_log_entries // empty' <<<"$RESOLVED_JSON")"
  MAXLOG_USED="${MAXLOG_USED:-$ASSUMED_MAX_LOG_ENTRIES}"
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
    --argjson maxlog "$MAXLOG_USED" \
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
        | (.measured_sample_count // $n) as $n_tail
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
        | ($n_tail >= $min_tail) as $enough_tail
        | {
            offered_rps:$off, achieved_rps:$ach, sample_count:$n,
            measured_sample_count:.measured_sample_count, settle_excluded:.settle_excluded,
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
            # here — k6 shares initialised VUs across rungs whose reservations overlap,
            # so growth cannot be attributed to one rung.) stall_time_buckets shows WHEN in the rung
            # deep-tail requests fell — clustered => transient stall (the standing
            # hypothesis), uniform => steady limit — a proxy for drop timing (a
            # dropped iteration never runs code, so drops cannot be timestamped).
            vus_active_max:.vus_active_max, vus_active_p95:.vus_active_p95, vus_active_avg:.vus_active_avg,
            stalls:.stalls, stalls_post_settle:.stalls_post_settle, stall_ms_threshold:.stall_ms_threshold,
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
        rig_valid_peak_achieved_rps:$peak,
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
        drive_rate:'"$POINT_WARMUP_RATE"', drive_p50_ms:$wup_p50, drive_achieved_rps:$wup_ach,
        # first vs second measured rung p50: if the FIRST rung is systematically
        # slower than the second AFTER the warm-up, the transient was not fully
        # removed — surfaced, not averaged away (item 18 warm-up-is-bias rule).
        first_rung_p50_ms:$r1, second_rung_p50_ms:$r2,
        first_rung_slower_than_second:(($r1 != null) and ($r2 != null) and ($r1 > $r2))
      }' <<<"$AGG")"

  # Memory, survival and lower-bound labelling. A ceiling is a LOWER BOUND when its rung
  # was client-limited, the SUT never neared its CPU pin, it is the top rung offered, or
  # the CPU samples needed to rule the first two out are missing (cpu_unverified).
  AGG="$(jq -c --argjson meta "$POINT_META" --argjson resolved "$RESOLVED_JSON" --argjson state "$STATE_JSON" \
    --argjson mempeak "${MEM_PEAK:-null}" --argjson retpeak "${RETAINED_PEAK:-null}" --argjson died_at "$DIED_AT_RPS" \
    --argjson retbpeak "${RETAINED_BYTES_PEAK:-null}" \
    --argjson sphys "${SPHYS:-null}" --argjson assumed "$ASSUMED_MAX_LOG_ENTRIES" --argjson used "$MAXLOG_USED" \
    --arg rates "$POINT_RATES" --argjson topo "$TOPO_KNOWN" --argjson died_early "$DIED_BEFORE_SWEEP" '
    def frac($a; $b): if $a == null or $b == null or $b <= 0 then null else (($a / $b) * 1000 | round) / 1000 end;
    . + $meta
    | ([.ladder[].offered_rps] | max) as $top
    | .healthy_ceiling_rps as $hc
    | ($state.running == false) as $died
    | (($state.java_oom_errors // 0) > 0) as $jvm_oom
    | .server_physical_cores = $sphys
    | .cpus_physically_verified = $topo
    | .sweep_rates = $rates
    | .resolved = $resolved
    | .heap_frac_of_memory_limit = frac($resolved.max_heap_bytes; $meta.memory_limit_bytes)
    | .container_memory_peak_bytes = $mempeak
    | .container_memory_peak_frac_of_limit = frac($mempeak; $meta.memory_limit_bytes)
    | .event_log_retained_entries_peak = $retpeak
    | .event_log_retained_bytes_peak = $retbpeak
    | frac($retpeak; $resolved.max_log_entries) as $cu
    | frac($retbpeak; $resolved.max_event_log_bytes) as $bu
    | .event_log_count_utilisation = $cu
    | .event_log_bytes_utilisation = $bu
    # Either bound evicts, so the log is full when EITHER is reached.
    | .event_log_filled = (if $cu == null and $bu == null then null
                           else ((($cu // 0) >= 0.95) or (($bu // 0) >= 0.95)) end)
    | .event_log_binding_bound = (if $cu == null and $bu == null then null
                                  elif ($bu // 0) >= ($cu // 0) then "bytes" else "entries" end)
    | .event_log_mean_entry_bytes = (if ($retpeak // 0) > 0 and $retbpeak != null
                                     then ($retbpeak / $retpeak | round) else null end)
    | .retention.assumed_max_log_entries = $assumed
    | .retention.max_log_entries_used = $used
    | .retention.max_log_entries_source = (if $resolved.max_log_entries != null then "resolved" else "assumed" end)
    | .retention.retained_bytes_estimate = ($used * .retention.body_bytes)
    | .sut_state = $state
    | .oom_killed = ($state.oom_killed == true)
    | .java_out_of_memory = $jvm_oom
    | .sut_survived = (($died or $jvm_oom) | not)
    | .died_at_offered_rps = (if $died then $died_at else null end)
    | .died_before_sweep = ($died and $died_early)
    | .client_headroom_frac_at_ceiling = (if .healthy_ceiling_client_cpu_pct == null then null
                                          else frac(.client_pin_pct - .healthy_ceiling_client_cpu_pct; .client_pin_pct) end)
    # A SUT that did not survive has no ceiling to bound; its status says what happened.
    | .lower_bound_reasons = if ($died or $jvm_oom) then [] else [
        (if .client_limited_at_ceiling == true then "client_cpu_limited" else empty end),
        (if $hc != null and .peak_limited_by == "load_path_or_virtualization" then "server_cpu_not_saturated" else empty end),
        (if $hc != null and $hc == $top then "ladder_top_reached" else empty end),
        (if $hc != null and (.peak_limited_by == null or .healthy_ceiling_client_cpu_pct == null)
         then "cpu_unverified" else empty end) ] end
    | .lower_bound = ((.lower_bound_reasons | length) > 0)
    | .status = (if $state.oom_killed == true then "oom_killed" elif $died then "sut_died"
                 elif $jvm_oom then "java_out_of_memory" elif $hc == null then "no_healthy_ceiling"
                 else "measured" end)' <<<"$AGG")"

  echo "    $LBL  status=$(jq -r '.status' <<<"$AGG") heap=$(jq -r '.resolved.max_heap_bytes' <<<"$AGG") mem_peak=$(jq -r '.container_memory_peak_frac_of_limit' <<<"$AGG") lower_bound=$(jq -r '.lower_bound_reasons | join("+")' <<<"$AGG")" >&2
  echo "    C=$C  healthy_ceiling=${HC_RPS} rps_per_core=$(jq -r '.rps_per_core' <<<"$AGG") peak=$(jq -r '.rig_valid_peak_achieved_rps' <<<"$AGG") sut_cpu@peak=$(jq -r '.sut_cpu_at_peak_pct' <<<"$AGG")%/$(jq -r '.sut_pin_pct' <<<"$AGG")% peak_limited_by=$(jq -r '.peak_limited_by' <<<"$AGG")" >&2
  POINTS+=("$AGG")
  [ "$C" -gt "$MAX_MEASURED" ] && MAX_MEASURED="$C"
done

# --- assemble the serving_percore / serving_hw_matrix block --------------------
POINTS_JSON="$(printf '%s\n' "${POINTS[@]:-}" | jq -sc 'map(select(. != null and . != ""))')"
SKIPPED_JSON="$(printf '%s\n' "${SKIPPED[@]:-}" | jq -sc 'map(select(. != null and . != ""))')"
REQUESTED_JSON="$(for i in "${!P_CORES[@]}"; do
  jq -nc --argjson c "${P_CORES[$i]}" --arg m "${P_MEM[$i]}" --argjson ctl "${P_CONTROL[$i]}" \
    '{cores:$c, memory_limit:$m, control:$ctl}'; done | jq -sc '.')"

jq -nc \
  --argjson points "$POINTS_JSON" \
  --argjson skipped "$SKIPPED_JSON" \
  --argjson requested "$REQUESTED_JSON" \
  --argjson host_cores "$HOST_CORES" \
  --argjson host_phys "$HOST_PHYS_CORES" \
  --argjson max_measured "$MAX_MEASURED" \
  --arg mode "$MODE" \
  --arg matrix "$HW_MATRIX_SPEC" \
  --argjson per_core_cap "$HW_MAX_RPS_PER_CORE" \
  --argjson k6_reserve "$K6_RESERVE" \
  --argjson rig_paused "$([ "${PERF_HW_MATRIX_RIG_PAUSED:-false}" = true ] && echo true || echo false)" \
  --arg ladder "$CORE_LADDER" \
  --arg rates "$SWEEP_RATES" \
  --arg step "$SWEEP_STEP" --arg gap "$SWEEP_GAP" --argjson settle "$SWEEP_SETTLE_S" '
  {
    attempted:true,
    mode:$mode,
    proto:"http",
    host_cores:$host_cores,
    host_physical_cores:$host_phys,
    cores_requested:($requested | map(.cores)),
    max_cores_measured:$max_measured,
    # latency_settle_s: the methodology fingerprint perf-test-compare.sh keys the
    # p50-derived healthy-ceiling metrics on.
    sweep:{rates:$rates, step:$step, gap:$gap, latency_settle_s:$settle},
    healthy_ceiling_definition:"lib/perf-website-figures.jq headline (Finding 1: highest rung achieved>=0.95*offered, zero errors, p50<=3x flat-region p50) — reused, not re-implemented",
    skipped:$skipped
  }
  + if $mode == "hw_matrix" then {
      matrix:$matrix,
      matrix_requested:$requested,
      # each point offers only the rates up to this many rps per core (0 = the full ladder).
      sweep_max_rps_per_core:$per_core_cap,
      k6_reserve_cores:$k6_reserve,
      # true only when the caller (perf-test-run.sh) paused every other container on the
      # rig, so nothing else could run on a point cores during its sweep.
      other_containers_paused:$rig_paused,
      server_config:"shipped image defaults (no -Xmx; heap from MaxRAMPercentage of the memory limit) with MOCKSERVER_LOG_LEVEL=ERROR and MOCKSERVER_DISABLE_SYSTEM_OUT=true",
      log_level:"ERROR",
      points:($points | sort_by(.cores, .memory_limit_bytes))
    } else {
      # explicit, un-skimmable statement of where the curve ends and why (item 18).
      curve_complete_to_16:(($points | map(.cores) | max // 0) >= 16),
      points:($points | sort_by(.cores))
    } end' > "$OUT_FILE"

echo "--- serving_${MODE} points=$(jq -r '.points | length' "$OUT_FILE") skipped=$(jq -r '.skipped | length' "$OUT_FILE") max_cores_measured=$(jq -r '.max_cores_measured' "$OUT_FILE")" >&2
