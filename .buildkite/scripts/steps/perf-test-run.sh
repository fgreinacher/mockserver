#!/usr/bin/env bash
set -euo pipefail

# Periodic performance-regression RUN step (perf queue). Produces ONE result JSON
# (uploaded as a Buildkite artifact) that perf-test-compare.sh baseline-checks.
#
# Phases:
#   1. start a DEDICATED upstream MockServer + the MockServer under test
#      (metrics enabled, DEFAULT maxLogEntries — never shrink it, see growth)
#   2. regression.js over HTTP, then over HTTPS+H2  -> per-behaviour latency
#   3. growth.js (sustained load) with a background CPU/heap sampler
#      -> resource-growth slope ratios (issue #2329 class)
#   4. assemble result.json {metadata, behaviours, growth, resources}
#
# Co-located load-gen + server: on a >=16 vCPU box the server, upstream and k6
# are core-pinned to disjoint cpusets so they don't steal cycles (the single
# biggest factor in number quality). On a smaller box pinning is skipped with a
# warning — numbers are then noisier but the run still works for local checks.
#
# Durations pass through to k6 via K6_* env (defaults in k6/lib/config.js). The
# MockServer image is MOCKSERVER_IMAGE (default snapshot).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

K6_IMAGE="grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f"
# The SUT runs the -graaljs snapshot variant so the JavaScript response-template
# arm (regression.js item 15a) has the GraalVM JS engine available; the engine is
# an OPTIONAL dependency absent from the plain image, and a JS template without it
# fails loud (500). The extra jars are inert for every non-JS arm (loaded lazily
# only when a JS template renders), so the match/forward/velocity/mustache/large
# numbers are unaffected. Overridable; if you point this at a NON-graaljs image,
# also set PERF_JS_TEMPLATE=false or regression.js aborts the run loudly.
MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot-graaljs}"
# item 15a — enable the JavaScript template arm (default on; the SUT image above
# carries GraalJS). item 15d — server-side path of the file-backed response body.
PERF_JS_TEMPLATE="${PERF_JS_TEMPLATE:-true}"
FILE_BODY_CONTAINER_PATH="/perf-files/large-file-body.json"
RUN_ID="${BUILDKITE_BUILD_ID:-local}-$$"
NETWORK="mockserver-perf-${RUN_ID}"
SERVER="mockserver-perf-${RUN_ID}"
UPSTREAM="mockserver-upstream-${RUN_ID}"
# item 12 — the background k6 that drives the streaming concurrency load, and a
# DEDICATED, deliberately CONSTRAINED SUT it drives (low CPU + a small
# action-handler pool) so the scheduler saturates at a modest, DETERMINISTIC
# concurrency regardless of the agent's core count — otherwise on the pinned
# 6-core CI SUT the match A/B never leaves ~1.0 and the tripwire is placed where
# nothing happens (see the streaming phase's header for the full rationale).
STREAM_K6="mockserver-perf-k6-stream-${RUN_ID}"
STREAM_SUT="mockserver-perf-stream-sut-${RUN_ID}"
# item 13 — clustered state under load (within-run A/B). A single in-memory-backend
# control SUT and a two-node Infinispan/JGroups cluster, all on the SAME clustered
# image so the ONLY variable is the state backend; regression.js (unchanged) runs
# against each and the metric is the per-arm ratio (clustered / control), the
# CandidateIndexBenchmark within-run A/B that cancels host/JVM/image noise.
CLU_CTRL="mockserver-perf-clu-ctrl-${RUN_ID}"
CLU_A="mockserver-perf-clu-a-${RUN_ID}"
CLU_B="mockserver-perf-clu-b-${RUN_ID}"
SAMPLE_INTERVAL="${PERF_SAMPLE_INTERVAL:-5}"
# Hard memory bound for the SUT (item: measure growth against a realistic heap).
# Unbounded on a 32 GB box, MaxRAMPercentage=75 yields a ~24 GB heap that barely
# GCs, so a slow leak is invisible and the "live set" is unobservable. A bounded
# heap that actually cycles is what the documented central-deployment guidance
# runs, and is what makes the saw-tooth floor (see the live-set ratio below) mean
# something. Applied to the SUT only, never the upstream. Overridable for a re-run.
SERVER_MEMORY="${PERF_SERVER_MEMORY:-2g}"

# --- event-log body-byte budget: the OOM guard that keeps the run alive ---------
# WHY THIS EXISTS (build #249 died here). MockServer records every request AND its
# response in a COUNT-bounded event-log ring of maxLogEntries entries, holding the
# FULL body of each. On the 2 GB SUT the heap is MaxRAMPercentage=75% ~= 1.5 GB, so
# maxLogEntries = min(heapKB/8, 100000) = 100000. An entry lives for the ring's
# residence = maxLogEntries / total_ACHIEVED_insertion_rps, and — this is the trap —
# residence LENGTHENS without bound as achieved throughput FALLS. The MB-scale
# regression arms (large_1mb/large_10mb/large_file, k6 item 15d) then retain, per arm,
# roughly rate x residence x body:
#     arm          rate     body     @ residence 111 s (total ~901 rps, healthy)
#     large_10mb   0.1/s    10 MB    -> 0.1 x 111 x 10 MB  ~= 111 MB
#     large_1mb    0.5/s     1 MB    -> 0.5 x 111 x  1 MB  ~=  55 MB
#     large_file   0.5/s    ~1 MB    -> 0.5 x 111 x  1 MB  ~=  55 MB   (its ~1 MB RESPONSE)
#     large(4KB)   200/s     4 KB    -> 200 x 111 x  4 KB  ~=  89 MB
# ~310 MB of large bodies looks safe under 1.5 GB — but ONLY at residence 111 s. When
# the SUT contends (the pre-fix JavaScript contagion, or the EXTRA body throughput the
# 2026-09-17 dispatch-pool fixes eec183f7e/7fbae1350 now ADMIT), total achieved rps
# collapses, residence 2x/4x/10x, and every figure above scales with it — there is no
# upper bound as achieved rps -> 0. Two back-to-back protocol passes (http then
# https_h2) compound it. That is what pushed retention past the heap in build #249 and
# killed the container mid-run, so the second (https_h2) pass could not even seed
# ("no such host").
# THE FIX: bound RETENTION, not rate. maxEventLogSizeInBytes is MockServer's own OOM
# guard (MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES) — when > 0 the ring ALSO enforces a
# body-byte budget, evicting oldest-first until total logged body bytes fit, in
# addition to the count bound. Retention is then capped at the budget REGARDLESS of
# residence, so no rate x residence product can run away however far throughput falls.
# 256 MiB is the documented starting point for a 2 GB heap; actual live heap is a small
# multiple (headers/metadata) ~= 0.5-1 GB, comfortably under 1.5 GB.
# WHY NOT SHRINK maxLogEntries INSTEAD: growth.js runs on THIS SAME SUT and must fill
# the DEFAULT 100k ring to reproduce the issue #2329 O(n)-eviction slope; a smaller
# ring would never fill and would hide the bug. The byte budget does NOT corrupt growth
# because growth loads only the tiny /simple body (~hundreds of bytes) — its total
# retained bytes across 100k entries stay in the tens of MB, far below 256 MiB, so the
# byte budget never fires for growth and its count-bounded fill is untouched. Applied to
# EVERY SUT that runs regression.js with the MB arms: the main SUT here and the clustered
# A/B nodes (start_clu) — the clustered image runs the same large_1mb/large_10mb arms on
# a 1.5 GB heap and was at the identical risk.
PERF_MAX_EVENT_LOG_BYTES="${PERF_MAX_EVENT_LOG_BYTES:-268435456}" # 256 MiB

OUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-result.XXXXXX")"
# The k6 image runs as a NON-root user (uid 12345); mktemp -d creates the dir
# 0700 owned by the agent user, so k6's handleSummary() can't write its result
# JSONs into the `/out` bind mount ("permission denied"). World-write the shared
# output dir so the unprivileged container user can write its artifacts. Only the
# k6 result files land here (no secrets), and the dir is per-run + cleaned up.
chmod 0777 "$OUT_DIR"
RESULT_JSON="$OUT_DIR/result.json"
SAMPLE_LOG="$OUT_DIR/samples.csv"

# item 15d — file-backed response body arm. Generate a ~1 MB JSON file on the host
# and mount it read-only into the SUT so a FILE-body expectation can serve it (the
# FileBodyMaterialiser path). World-readable so the container's non-root user can
# read it. Only the SUT gets the mount; the upstream does not need it.
FILE_BODY_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-filebody.XXXXXX")"
chmod 0755 "$FILE_BODY_DIR"
FILE_BODY_HOST_PATH="$FILE_BODY_DIR/large-file-body.json"
awk 'BEGIN{
  printf "{\"marker\":\"large-file\",\"filler\":[";
  n=62000; # ~1 MB of fixed-width quoted tokens (17 bytes each)
  for(i=0;i<n;i++){ if(i)printf ","; printf "\"item-%09d\"", i }
  printf "]}"
}' > "$FILE_BODY_HOST_PATH"
chmod 0644 "$FILE_BODY_HOST_PATH"
FILE_BODY_MOUNT="$FILE_BODY_DIR:/perf-files:ro"
echo "--- file-backed body: $(wc -c < "$FILE_BODY_HOST_PATH") bytes -> ${FILE_BODY_CONTAINER_PATH} (SUT mount)"

SAMPLER_PID=""
SWEEP_SAMPLER_PID=""
SWEEP_K6="k6-sweep-${RUN_ID}"
# Plan open question 5 — the INFO-log-level PUBLICATION arm. The tracked, gated
# baseline is measured at MOCKSERVER_LOG_LEVEL=ERROR (start_mockserver's default)
# and MUST NOT move — every historical S3 run is ERROR, so switching it would break
# comparability. This arm ADDS a second SUT at the SHIPPED-DEFAULT log level (INFO)
# and re-measures ONLY the two PUBLISHED figure families against it — the knee curve
# (sweep.js) and the per-behaviour percentiles (regression.js) — so the site can show
# an honest "out of the box" number ALONGSIDE the (legitimate, but labelled) ERROR
# one. Its output lands under DISTINCT top-level keys (info_log_level_arm.*), never
# under .behaviours / .sweep / peak_achieved_rps, so an INFO number can never be
# confused with, or diffed against, the ERROR baseline series. Set PERF_INFO_ARM=false
# to skip it if the (serialised) perf box is time-pressed — it is a pure add-on and
# nothing else in the run depends on it.
INFO_SERVER="mockserver-perf-info-${RUN_ID}"
INFO_SERVER_ALIAS="mockserver-info"
INFO_SWEEP_K6="k6-info-sweep-${RUN_ID}"
PERF_INFO_ARM="${PERF_INFO_ARM:-true}"
cleanup() {
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" >/dev/null 2>&1 || true
  [ -n "$SWEEP_SAMPLER_PID" ] && kill "$SWEEP_SAMPLER_PID" >/dev/null 2>&1 || true
  [ -n "${HS_CPU_PID:-}" ] && kill "$HS_CPU_PID" >/dev/null 2>&1 || true
  # The item 14 handshake SUTs (deterministic names from RUN_ID) — removed here too
  # so an early exit before the proxy block's own cleanup never leaks them.
  docker rm -f "$SERVER" "$UPSTREAM" "$SWEEP_K6" "$STREAM_K6" "$STREAM_SUT" \
    "$INFO_SERVER" "$INFO_SWEEP_K6" \
    "$CLU_CTRL" "$CLU_A" "$CLU_B" \
    "mockserver-mtls-${RUN_ID}" "mockserver-jdk-${RUN_ID}" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  [ -n "${FILE_BODY_DIR:-}" ] && rm -rf "$FILE_BODY_DIR" >/dev/null 2>&1 || true
  [ -n "${HS_CERT_DIR:-}" ] && rm -rf "$HS_CERT_DIR" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- validity accumulation (item: validity blocks on every result) ------------
# Each measurement phase appends a check {name, ok, detail}; the assembled result
# carries a `validity` object, and perf-test-compare.sh REFUSES to baseline a run
# whose validity is absent or false — rather than silently comparing a compromised
# measurement (the inject harness's discipline, generalised: exclude a bad point,
# don't report it).
VALIDITY_CHECKS=()
add_check() { # name  ok(true|false)  detail
  VALIDITY_CHECKS+=("$(jq -nc --arg n "$1" --argjson ok "$2" --arg d "$3" '{name:$n, ok:$ok, detail:$d}')")
}

# k6 duration string ("15s","1m30s") -> integer seconds (floor). Handles s/m/h/d;
# the sweep step/gap are seconds, so ms is not expected.
to_secs() {
  awk -v s="$1" 'BEGIN{
    t=0; n="";
    for(i=1;i<=length(s);i++){c=substr(s,i,1);
      if(c ~ /[0-9]/){n=n c}
      else{v=n+0; n="";
        if(c=="s")t+=v; else if(c=="m")t+=v*60; else if(c=="h")t+=v*3600; else if(c=="d")t+=v*86400}}
    printf "%d", t}'
}

# Count cores in a cpuset spec ("8-13" -> 6, "8,9,10" -> 3, "" -> 0). Used to turn
# the k6 container's CPU pin into an absolute percentage ceiling (cores*100%).
k6_core_count() {
  local spec="$1" total=0 part a b
  [ -z "$spec" ] && { echo 0; return; }
  IFS=',' read -ra _parts <<< "$spec"
  for part in "${_parts[@]}"; do
    if [[ "$part" == *-* ]]; then a="${part%%-*}"; b="${part##*-}"; total=$((total + b - a + 1));
    else total=$((total + 1)); fi
  done
  echo "$total"
}

# --- core pinning --------------------------------------------------------------
CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"
SERVER_CPUS=""; UPSTREAM_CPUS=""; K6_CPUS=""
if [ "$CORES" -ge 16 ]; then
  # Defaults: server=0-5 upstream=6 k6=8-13 (7,14,15 left for kernel/docker/sampler).
  # Each cpuset is overridable via PERF_SERVER_CPUS / PERF_UPSTREAM_CPUS / PERF_K6_CPUS
  # so a re-run can, e.g., hand k6 more cores to drive higher arrival rates.
  SERVER_CPUS="${PERF_SERVER_CPUS:-0-5}"; UPSTREAM_CPUS="${PERF_UPSTREAM_CPUS:-6}"; K6_CPUS="${PERF_K6_CPUS:-8-13}"
  echo "--- core-pinning enabled (${CORES} vCPU): server=$SERVER_CPUS upstream=$UPSTREAM_CPUS k6=$K6_CPUS"
else
  echo "--- WARNING: ${CORES} vCPU (<16) — core-pinning skipped; numbers will be noisier"
fi
cpuset_arg() { [ -n "$1" ] && printf -- '--cpuset-cpus=%s' "$1"; }

docker network create "$NETWORK" >/dev/null

start_mockserver() {
  local name="$1" cpus="$2" alias="$3" publish="${4:-}" mem="${5:-}" mount="${6:-}" log_level="${7:-ERROR}"
  # log_level defaults to ERROR — the tracked baseline's level, which every existing
  # caller relies on. The INFO publication arm (plan open question 5) passes INFO
  # explicitly; nothing else does, so the ERROR baseline is unaffected.
  # PERF_SERVER_JAVA_OPTS (when set) is passed through as JAVA_TOOL_OPTIONS so a
  # re-run can opt into a tuned JVM (e.g. low-pause GC + a larger heap) for nicer
  # documentation-site throughput/latency figures. Applied to BOTH the SUT and
  # the upstream so the upstream never becomes the bottleneck under those tuned
  # rates. Request logging is deliberately left ON (we don't disable it here) so
  # the growth phase stays meaningful. Built as an array element so the value
  # survives intact as a SINGLE -e pair even though it contains spaces; unset =>
  # the array is empty and no -e flag is added, identical behaviour.
  local java_opts_arg=()
  [ -n "${PERF_SERVER_JAVA_OPTS:-}" ] && java_opts_arg=(-e "JAVA_TOOL_OPTIONS=$PERF_SERVER_JAVA_OPTS")
  # shellcheck disable=SC2046
  docker run -d --rm --name "$name" --network "$NETWORK" --network-alias "$alias" \
    $(cpuset_arg "$cpus") \
    ${mem:+--memory="$mem"} \
    ${mount:+-v "$mount"} \
    ${publish:+-p 127.0.0.1::1080} \
    ${java_opts_arg[@]+"${java_opts_arg[@]}"} \
    -e MOCKSERVER_LOG_LEVEL="$log_level" \
    -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
    -e MOCKSERVER_METRICS_ENABLED=true \
    -e MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES="$PERF_MAX_EVENT_LOG_BYTES" \
    "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null
}

wait_ready() {
  local name="$1"
  for _ in $(seq 1 60); do
    local status
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' "$name" 2>/dev/null || echo missing)"
    case "$status" in
      healthy) return 0 ;;
      nohealth) sleep 10; return 0 ;;
      missing) echo "ERROR: container $name exited early" >&2; docker logs "$name" 2>&1 | tail -20 >&2 || true; return 1 ;;
    esac
    sleep 2
  done
  echo "ERROR: $name did not become ready" >&2; return 1
}

# The server's network alias is `mockserver`, which k6's config.js treats as a
# local/private target — so the HTTPS pass auto-trusts the self-signed cert with
# no per-VU TLS warning, and no reliance on an explicit insecure flag.
SERVER_ALIAS="mockserver"
echo "--- starting upstream + MockServer ($MOCKSERVER_IMAGE)"
start_mockserver "$UPSTREAM" "$UPSTREAM_CPUS" "mockserver-upstream"
start_mockserver "$SERVER" "$SERVER_CPUS" "$SERVER_ALIAS" "publish" "$SERVER_MEMORY" "$FILE_BODY_MOUNT"
echo "--- SUT started with --memory=$SERVER_MEMORY (bounded heap so GC cycles) + maxEventLogSizeInBytes=$PERF_MAX_EVENT_LOG_BYTES (body-byte OOM guard)"
wait_ready "$UPSTREAM"
wait_ready "$SERVER"

# Host-mapped metrics port so the sampler reads /mockserver/metrics from the host
# (curl on the agent) instead of spawning a container per sample — avoids adding
# CPU noise to the very box being measured. k6 still reaches the server over the
# docker network (container alias), unaffected by this host publish.
SERVER_METRICS="$(docker port "$SERVER" 1080/tcp 2>/dev/null | head -1)"
SERVER_METRICS_URL="http://${SERVER_METRICS:-127.0.0.1:1080}/mockserver/metrics"

# --- self-describing config block (make a result record what it WAS) -----------
# The comparison machinery, budget ratchet, hardware-invalidation rule and the
# website provenance line all assume a stored run records HOW it was configured.
# Resolve that from the RUNNING JVM (its own metrics endpoint) and the ACTUAL
# container (docker inspect) — never by echoing the shell variables that were only
# MEANT to set it. Each field is marked `observed` (read back from the live
# process/container) or `declared` (a shell value we could not verify against the
# process). A MANDATORY value that cannot be recorded FAILS the step HERE, early —
# not silently written as a placeholder. The instance_type:"" bug is the worked
# example: a field that exists, is populated, and is WRONG survives review, which
# is worse than an absent field. Runs before the long measurement phases so an
# unrecordable config wastes seconds, not the whole 45-minute run.
CONFIG_METRICS=""
for _ in $(seq 1 15); do
  CONFIG_METRICS="$(curl -sf --max-time 4 "$SERVER_METRICS_URL" 2>/dev/null || true)"
  if [ -n "$CONFIG_METRICS" ] && printf '%s' "$CONFIG_METRICS" | grep -q '^mock_server_build_info'; then break; fi
  sleep 2
done

# Extract one label's value from a Prometheus info-gauge line (labels are quoted,
# so a comma/space inside a value — e.g. the GC list or the VM name — is safe).
# The label name is anchored to its preceding `{` or `,` delimiter so a query for
# `version` does NOT match inside `major_minor_version` (that substring match would
# silently record the wrong value — precisely the populated-but-wrong trap).
# The trailing `|| true` matters: a no-match here must yield the EMPTY string and
# succeed, so the fail-closed guard below can report *which* field is unrecordable.
# Without it, `set -euo pipefail` would abort the whole step on the failing grep
# (an accidental fail-closed with no diagnostic), turning the guard into dead code.
metric_label() { # metric_name label_name   (reads global CONFIG_METRICS)
  printf '%s' "$CONFIG_METRICS" | grep -oE "^$1\{[^}]*\}" | head -1 \
    | grep -oE "[{,]$2=\"[^\"]*\"" | head -1 | sed -E 's/^[{,][^=]*="//; s/"$//' || true
}
# Resolved max heap in bytes from the RUNNING JVM (reflects the entrypoint's
# -Xmx / MaxRAMPercentage + the container memory limit — the actual heap, not the
# flag that implies it). Normalised to an integer (the client may emit 1.6E9).
metric_heap_max() {
  printf '%s' "$CONFIG_METRICS" \
    | awk -F'} ' '/^jvm_memory_max_bytes\{area="heap"\}/{print $2}' | head -1 \
    | awk '{printf "%d", $1+0}' || true
}
# One env var as the SUT container ACTUALLY received it (empty if unset).
# NOTE this is a WEAKER observation than gc/heap_max_bytes, which are read from the JVM's own
# metrics endpoint. This only proves the container was HANDED the value, not that the JVM parsed
# and applied it - hence the config block labels it container-env rather than observed. If the
# server ever exposes the effective budget as a metric, read it from there instead.
container_env() { # VAR_NAME [container_name=$SERVER]
  # Defaults to the ERROR baseline SUT so every existing caller is unchanged; the
  # INFO publication arm passes its own container name to read that SUT's log level.
  local cname="${2:-$SERVER}"
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$cname" 2>/dev/null \
    | awk -F= -v k="$1" '$1==k{sub("^[^=]*=",""); print; exit}' || true
}

MS_VERSION="$(metric_label mock_server_build_info version)"
MS_GIT_HASH="$(metric_label mock_server_build_info git_hash)"
JDK_BUILD="$(metric_label jvm_runtime_info java_runtime_version)"
JAVA_VENDOR="$(metric_label jvm_runtime_info java_vendor)"
VM_NAME="$(metric_label jvm_runtime_info vm_name)"
GC_IN_USE="$(metric_label jvm_runtime_info gc)"
HEAP_MAX_BYTES="$(metric_heap_max)"
# The immutable content id the SUT image actually resolved to (RepoDigest), or the
# local image id when built without a digest (locally-built image). RepoDigests is
# an IMAGE property and does NOT exist on a container, so we must resolve the
# container's image id first (.Image) and inspect the IMAGE — NOT the container.
# Inspecting the container reads a key that is absent there: older Docker CLIs
# (missingkey=invalid) rendered that as empty and silently fell through to the
# container's .Image (the image id, never the RepoDigest), but Docker 29.x
# (missingkey=error) makes the missing key a hard template error, so the value
# came back empty and tripped the fail-closed guard below for a digest that is in
# fact perfectly resolvable. On the image, RepoDigests is always a present key
# (an empty list for a locally-built image), so the {{if}} degrades cleanly to the
# documented .Id fallback rather than erroring. Only a genuine inability to inspect
# the image at all leaves this empty — which is meant to fail closed.
#
# HISTORY: schema_version 2 runs stored BEFORE this fix carry a bare image-config
# ID here, not a repo digest, labelled sources.image_digest="observed". They are
# wrong rather than absent, so do not read a pre-fix image_digest as provenance.
# The failure direction is safe: the field is display-only in the compare step
# today, and a bare-ID-vs-digest mismatch can only SUPPRESS the planned ratchet
# (which requires an identical config across runs), never falsely tighten a budget.
SERVER_IMAGE_ID="$(docker inspect --format '{{.Image}}' "$SERVER" 2>/dev/null || true)"
IMAGE_DIGEST="$(docker image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{else}}{{.Id}}{{end}}' "${SERVER_IMAGE_ID:-$MOCKSERVER_IMAGE}" 2>/dev/null || true)"
# Log level + system-out suppression are BOTH non-defaults every CI perf run sets,
# and neither was ever recorded. Read them off the container that ran (observed);
# only if absent there fall back to the shell env we exported (declared).
LOG_LEVEL_VAL="$(container_env MOCKSERVER_LOG_LEVEL)"; LOG_LEVEL_SRC="observed"
[ -n "$LOG_LEVEL_VAL" ] || { LOG_LEVEL_VAL="${MOCKSERVER_LOG_LEVEL:-}"; LOG_LEVEL_SRC="declared"; }
DISABLE_SYSOUT_VAL="$(container_env MOCKSERVER_DISABLE_SYSTEM_OUT)"; DISABLE_SYSOUT_SRC="observed"
[ -n "$DISABLE_SYSOUT_VAL" ] || { DISABLE_SYSOUT_VAL="${MOCKSERVER_DISABLE_SYSTEM_OUT:-}"; DISABLE_SYSOUT_SRC="declared"; }
# JAVA_TOOL_OPTIONS is legitimately absent when PERF_SERVER_JAVA_OPTS is unset — an
# empty OBSERVED value here is a true fact (no JVM opts), not a masked placeholder.
JAVA_TOOL_OPTS_VAL="$(container_env JAVA_TOOL_OPTIONS)"
# Body-byte OOM guard the SUT ACTUALLY received (observed from the container env), so
# a reader can see the guard was in force for this run — and so the fail-closed check
# below reddens loudly if a future edit ever drops it (a run without the guard is at
# the exact OOM risk build #249 hit, and must never be silently baselined as a healthy
# one). Read back from the container, not echoed from the shell var, on purpose.
MAX_EVENT_LOG_VAL="$(container_env MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES)"
# k6 image digest is pinned in the K6_IMAGE ref itself (…@sha256:…).
K6_IMAGE_DIGEST="$(printf '%s' "$K6_IMAGE" | sed -nE 's/.*@(sha256:[0-9a-f]+)$/\1/p')"
# k6 container CPU allocation (cores * 100%) from its cpuset pin.
K6_CFG_CORES="$(k6_core_count "${K6_CPUS:-}")"; K6_CFG_PIN_PCT=$((K6_CFG_CORES * 100))

# Fail closed: an unrecordable MANDATORY value must abort the step, not become a
# placeholder that outlives review. GC + JDK come from jvm_runtime_info, which a
# MockServer image built before that metric shipped will not expose — that is a
# genuine "cannot record what this run was" and is meant to fail here.
CONFIG_ERRORS=()
[ -n "$MS_VERSION" ]        || CONFIG_ERRORS+=("mockserver version (mock_server_build_info{version}) not readable from ${SERVER_METRICS_URL}")
[ -n "$IMAGE_DIGEST" ]      || CONFIG_ERRORS+=("image digest not resolvable — 'docker image inspect' of ${SERVER}'s image (${SERVER_IMAGE_ID:-<unresolved>}) yielded no RepoDigest or .Id")
[ -n "$JDK_BUILD" ]        || CONFIG_ERRORS+=("JDK build (jvm_runtime_info{java_runtime_version}) not readable — image predates the jvm_runtime_info metric?")
[ -n "$GC_IN_USE" ]        || CONFIG_ERRORS+=("GC in use (jvm_runtime_info{gc}) not readable — image predates the jvm_runtime_info metric?")
awk -v v="$HEAP_MAX_BYTES" 'BEGIN{exit !(v+0>0)}' || CONFIG_ERRORS+=("resolved heap (jvm_memory_max_bytes{area=\"heap\"}) not a positive value: '${HEAP_MAX_BYTES}'")
[ -n "$LOG_LEVEL_VAL" ]     || CONFIG_ERRORS+=("MOCKSERVER_LOG_LEVEL not recordable (neither container env nor shell)")
[ -n "$DISABLE_SYSOUT_VAL" ] || CONFIG_ERRORS+=("MOCKSERVER_DISABLE_SYSTEM_OUT not recordable (neither container env nor shell)")
awk -v v="$MAX_EVENT_LOG_VAL" 'BEGIN{exit !(v+0>0)}' || CONFIG_ERRORS+=("event-log body-byte OOM guard (MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES) not applied to the SUT, OR the container env could not be read (docker inspect failed) - these are not distinguished here and both fail closed: '${MAX_EVENT_LOG_VAL}' — a run without it is at the build-#249 OOM risk and must not be baselined as healthy")
if [ "${#CONFIG_ERRORS[@]}" -gt 0 ]; then
  echo "ERROR: run configuration is not fully recordable — refusing to emit a result that misrepresents what it measured:" >&2
  printf '  - %s\n' "${CONFIG_ERRORS[@]}" >&2
  echo "(A populated-but-wrong config field survives review; an absent one does not — see the instance_type:\"\" bug.)" >&2
  exit 1
fi

CONFIG_JSON="$(jq -n \
  --arg version "$MS_VERSION" --arg git_hash "$MS_GIT_HASH" \
  --arg image "$MOCKSERVER_IMAGE" --arg image_digest "$IMAGE_DIGEST" \
  --arg jdk "$JDK_BUILD" --arg java_vendor "$JAVA_VENDOR" --arg vm_name "$VM_NAME" \
  --arg gc "$GC_IN_USE" --arg heap_max "$HEAP_MAX_BYTES" \
  --arg log_level "$LOG_LEVEL_VAL" --arg log_level_src "$LOG_LEVEL_SRC" \
  --arg disable_sysout "$DISABLE_SYSOUT_VAL" --arg disable_sysout_src "$DISABLE_SYSOUT_SRC" \
  --arg max_event_log "$MAX_EVENT_LOG_VAL" \
  --arg jto "$JAVA_TOOL_OPTS_VAL" --arg psjo "${PERF_SERVER_JAVA_OPTS:-}" \
  --arg k6_image "$K6_IMAGE" --arg k6_digest "$K6_IMAGE_DIGEST" \
  --arg server_cpus "${SERVER_CPUS:-none}" --arg upstream_cpus "${UPSTREAM_CPUS:-none}" --arg k6_cpus "${K6_CPUS:-none}" \
  --argjson k6_pin_pct "$K6_CFG_PIN_PCT" \
  '{
    mockserver_version: $version,
    mockserver_git_hash: (if $git_hash=="" then null else $git_hash end),
    image: $image,
    image_digest: $image_digest,
    jdk: $jdk, java_vendor: $java_vendor, vm_name: $vm_name,
    gc: $gc,
    heap_max_bytes: ($heap_max|tonumber),
    log_level: $log_level,
    disable_system_out: $disable_sysout,
    max_event_log_size_bytes: ($max_event_log|tonumber),
    java_tool_options: $jto,
    perf_server_java_opts: $psjo,
    k6_image: $k6_image,
    k6_image_digest: (if $k6_digest=="" then null else $k6_digest end),
    cpusets: { server: $server_cpus, upstream: $upstream_cpus, k6: $k6_cpus },
    k6_cpu_pin_pct: $k6_pin_pct,
    sources: {
      mockserver_version:"observed", mockserver_git_hash:"observed",
      image_digest:"observed", jdk:"observed", java_vendor:"observed", vm_name:"observed",
      gc:"observed", heap_max_bytes:"observed",
      log_level:$log_level_src, disable_system_out:$disable_sysout_src,
      max_event_log_size_bytes:"container-env",
      java_tool_options:"observed", perf_server_java_opts:"declared",
      k6_image_digest:"observed", cpusets:"declared", k6_cpu_pin_pct:"declared"
    }
  }')"
echo "--- config resolved: ${MS_VERSION} gc='${GC_IN_USE}' heap_max=${HEAP_MAX_BYTES} jdk='${JDK_BUILD}' log_level=${LOG_LEVEL_VAL} maxEventLogSizeInBytes=${MAX_EVENT_LOG_VAL} (schema_version=2)"

echo "--- seeding upstream /simple (forward target)"
docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 -s -X PUT \
  "http://${UPSTREAM}:1080/mockserver/expectation" -H 'Content-Type: application/json' \
  -d '[{"httpRequest":{"path":"/simple"},"httpResponse":{"statusCode":200,"body":"upstream"},"times":{"unlimited":true}}]' \
  -o /dev/null -w 'upstream seed HTTP %{http_code}\n'

run_regression() {
  local proto="$1" base_url="$2" insecure="$3" out="$4"
  echo "--- regression.js ($proto)"
  # shellcheck disable=SC2046
  docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "BASE_URL=$base_url" \
    -e "PROTO=$proto" \
    -e "INSECURE_SKIP_TLS_VERIFY=$insecure" \
    -e "K6_RESULT_PATH=/out/$out" \
    -e "K6_REG_JS_TEMPLATE=$PERF_JS_TEMPLATE" \
    -e "K6_REG_FILE_BODY_PATH=$FILE_BODY_CONTAINER_PATH" \
    ${K6_REG_WARMUP:+-e K6_REG_WARMUP="$K6_REG_WARMUP"} \
    ${K6_REG_DURATION:+-e K6_REG_DURATION="$K6_REG_DURATION"} \
    ${K6_REG_RATE:+-e K6_REG_RATE="$K6_REG_RATE"} \
    ${K6_REG_LARGE_1MB_RATE:+-e K6_REG_LARGE_1MB_RATE="$K6_REG_LARGE_1MB_RATE"} \
    ${K6_REG_LARGE_10MB_RATE:+-e K6_REG_LARGE_10MB_RATE="$K6_REG_LARGE_10MB_RATE"} \
    ${K6_REG_FILE_BODY_RATE:+-e K6_REG_FILE_BODY_RATE="$K6_REG_FILE_BODY_RATE"} \
    "$K6_IMAGE" run /k6/regression.js
}

run_regression "http" "http://${SERVER_ALIAS}:1080" "false" "regression-http.json"
run_regression "https_h2" "https://${SERVER_ALIAS}:1080" "true" "regression-https.json"

# --- throughput-vs-latency sweep ----------------------------------------------
# Offers an ascending ladder of fixed arrival rates against the SAME core-pinned
# SUT and records the achieved-throughput / latency-percentile knee curve.
# sweep.js seeds + resets MockServer itself in setup()/teardown(). Durations are
# bounded via K6_SWEEP_* env so it adds only ~3-4 min to the run; the CI-default
# ladder + short steps below override sweep.js's longer interactive defaults.
# The CI ladder now climbs PAST the ~32-36k knee (published saturation) to
# 48k/64k so saturation is actually reached rather than stopping where the server
# is still comfortable (the old default topped out at 16k). Adds ~4 min. A rung is
# only a valid server-ceiling candidate if the k6 CLIENT had headroom there, so we
# sample the k6 container's CPU throughout and read k6's own dropped_iterations per
# rung, then derive saturation_rps from the highest CLEANLY-served rung.
SWEEP_RATES="${K6_SWEEP_RATES:-500,1000,2000,4000,8000,16000,32000,48000,64000}"
SWEEP_STEP="${K6_SWEEP_STEP:-15s}"
SWEEP_GAP="${K6_SWEEP_GAP:-5s}"
SWEEP_CPU_LOG="$OUT_DIR/sweep-k6-cpu.csv"

# Ladder schedule + client-pin constants, derived ONCE and reused by every sweep
# (the ERROR baseline below AND the INFO publication arm). They depend only on the
# static ladder/pin config, never on a sweep's output, so hoisting them here keeps
# the two sweeps' saturation derivation identical (a fair ERROR-vs-INFO comparison
# requires the SAME knee methodology). SETTLE_S / err-eps are the same tunables the
# derivation used inline before this was factored out.
STEP_S="$(to_secs "$SWEEP_STEP")"
GAP_S="$(to_secs "$SWEEP_GAP")"
SETTLE_S="${PERF_SWEEP_SETTLE_S:-3}"
K6_CORES="$(k6_core_count "$K6_CPUS")"
K6_PIN_PCT=$((K6_CORES * 100))
SWEEP_ERR_EPS="${PERF_SWEEP_ERROR_EPS:-0.01}"

# Background sampler of the k6 CLIENT container's CPU while a sweep runs — the
# missing "was the client the bottleneck?" evidence. A rung where k6 is near its
# own CPU pin is a CLIENT ceiling, not MockServer's, and is excluded by
# derive_saturation. Parameterised by container name + output CSV so the ERROR and
# INFO sweeps each get their own sampler against their own k6 container.
sweep_cpu_sampler() { # k6_container_name  out_csv
  local cname="$1" out_csv="$2"
  echo "ts,cpu_pct" > "$out_csv"
  while true; do
    local ts cpu
    ts="$(date -u +%s)"
    cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$cname" 2>/dev/null | tr -d '% ' || echo '')"
    [ -n "$cpu" ] && printf '%s,%s\n' "$ts" "$cpu" >> "$out_csv"
    sleep "${PERF_SWEEP_SAMPLE_INTERVAL:-3}"
  done
}

# Run one throughput-vs-latency sweep against $target_alias, writing k6's result to
# $out_json and the client-CPU trace to $cpu_log. Records the sweep's start epoch in
# the global LAST_SWEEP_T0 (NOT echoed — the docker run's own stdout would pollute a
# captured value) so derive_saturation can line each rung up with its hold window.
# Registers the sampler PID in SWEEP_SAMPLER_PID so cleanup() reaps it on an early
# exit, and clears it once reaped.
run_sweep() { # k6_container_name  target_alias  out_json_host_path  cpu_log_host_path
  local k6name="$1" target_alias="$2" out_json="$3" cpu_log="$4"
  LAST_SWEEP_T0="$(date -u +%s)"
  sweep_cpu_sampler "$k6name" "$cpu_log" & SWEEP_SAMPLER_PID=$!
  # shellcheck disable=SC2046
  docker run --rm --name "$k6name" --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "BASE_URL=http://${target_alias}:1080" \
    -e "PROTO=http" \
    -e "K6_SWEEP_RATES=$SWEEP_RATES" \
    -e "K6_SWEEP_STEP=$SWEEP_STEP" \
    -e "K6_SWEEP_GAP=$SWEEP_GAP" \
    -e "K6_SWEEP_RESULT_PATH=/out/$(basename "$out_json")" \
    ${K6_SWEEP_PRE_VUS:+-e K6_SWEEP_PRE_VUS="$K6_SWEEP_PRE_VUS"} \
    ${K6_SWEEP_MAX_VUS:+-e K6_SWEEP_MAX_VUS="$K6_SWEEP_MAX_VUS"} \
    "$K6_IMAGE" run /k6/sweep.js
  kill "$SWEEP_SAMPLER_PID" >/dev/null 2>&1 || true; SWEEP_SAMPLER_PID=""
}

# --- derive saturation_rps from a sweep (item: prove the client had headroom) ---
# Per rung: attribute the max k6-container CPU seen during that rung's hold window
# (from the sampler log + the known ladder schedule anchored at $t0), pair it with
# k6's per-rung dropped_iterations, and mark the rung CLEAN iff achieved >=
# 0.95*offered AND the client had CPU headroom (< 85% of its pin) AND k6 dropped no
# iterations. The highest CLEAN rung's offered rate is saturation_rps. If nothing is
# clean, the client was the bottleneck everywhere (the caller flags the run invalid).
# Echoes the SATURATION_JSON object on stdout.
derive_saturation() { # sweep_json_host_path  cpu_log_host_path  t0_epoch
  local sweep_json="$1" cpu_log="$2" t0="$3"
  local cpu_map="{}" i r ws we maxcpu
  local -a rate_arr
  IFS=',' read -ra rate_arr <<< "$SWEEP_RATES"
  for i in "${!rate_arr[@]}"; do
    r="${rate_arr[$i]}"
    ws=$(( t0 + i * (STEP_S + GAP_S) + SETTLE_S ))
    we=$(( t0 + i * (STEP_S + GAP_S) + STEP_S ))
    maxcpu="$(awk -F',' -v a="$ws" -v b="$we" 'NR>1 && $1>=a && $1<=b { if($2+0>m) m=$2+0 } END{ printf "%.1f", m+0 }' "$cpu_log" 2>/dev/null || echo 0)"
    cpu_map="$(jq -c --arg k "$r" --argjson v "${maxcpu:-0}" '. + {($k): $v}' <<<"$cpu_map")"
  done
  # A rung is RIG-VALID when the measurement itself is trustworthy: the k6 client
  # had CPU headroom, dropped no iterations, and the server was not returning fast
  # errors (a rung "achieves" its offered rate even while erroring, so error_rate is
  # part of validity, not just throughput). The BUDGETED metric is peak_achieved_rps
  # = max achieved over rig-valid rungs — CONTINUOUS (it moves proportionally with
  # the real ceiling, e.g. 36,324 achieved at 48,000 offered), unlike the ladder-
  # QUANTISED saturation_rps (only ever a rung's offered value, 16k/32k/... — its
  # smallest move is a factor of two, so it cannot carry a percentage floor).
  # saturation_rps is kept as a DESCRIPTIVE figure only (the knee), not budgeted.
  jq -n \
    --slurpfile sweep "$sweep_json" \
    --argjson cpu "$cpu_map" \
    --argjson pin "$K6_PIN_PCT" \
    --argjson cores "$K6_CORES" \
    --argjson err_eps "$SWEEP_ERR_EPS" '
    ($pin * 0.85) as $cpu_ceiling
    | (($sweep[0].points) // []) as $points
    | [ $points[]
        | ($cpu[(.offered_rps|tostring)]) as $c
        | (.dropped_iterations // 0) as $drops
        | (.error_rate // 0) as $err
        | (.offered_rps) as $off | (.achieved_rps // 0) as $ach
        | (($cores <= 0) or ($c == null) or ($c <= $cpu_ceiling)) as $headroom
        | ($drops <= 0) as $no_drops
        | ($err <= $err_eps) as $low_err
        # rig_valid: the measurement itself is trustworthy (says nothing about the
        # server verdict). clean: rig_valid AND the server actually kept up (knee).
        | ($headroom and $no_drops and $low_err) as $rig_valid
        | ($rig_valid and ($off > 0) and ($ach >= 0.95 * $off)) as $clean
        | { offered_rps:$off, achieved_rps:$ach, k6_cpu_pct:$c,
            dropped_iterations:$drops, error_rate:$err,
            rig_valid:$rig_valid, clean:$clean,
            exclude_reason:(
              if $rig_valid then null
              elif ($headroom|not) then "k6 client CPU \($c)% >= 85% of \($pin)% pin (client bottleneck)"
              elif ($no_drops|not) then "k6 dropped \($drops) iterations (client VU-starved)"
              else "server error_rate \($err) > \($err_eps) (fast errors inflate achieved)" end) } ]
    | . as $rungs
    | ([ $rungs[] | select(.rig_valid) | .achieved_rps ] | max // 0) as $peak
    | ([ $rungs[] | select(.clean) | .offered_rps ] | max // 0) as $sat
    | { peak_achieved_rps:$peak, saturation_rps:$sat,
        client_pin_pct:$pin, client_cores:$cores,
        ladder:$rungs,
        excluded:[ $rungs[] | select(.rig_valid|not)
                   | {offered_rps, achieved_rps, k6_cpu_pct, dropped_iterations, error_rate, reason:.exclude_reason} ] }'
}

echo "--- sweep.js (throughput-vs-latency knee curve; ladder=$SWEEP_RATES)"
run_sweep "$SWEEP_K6" "$SERVER_ALIAS" "$OUT_DIR/sweep.json" "$SWEEP_CPU_LOG"
SWEEP_T0="$LAST_SWEEP_T0"
SATURATION_JSON="$(derive_saturation "$OUT_DIR/sweep.json" "$SWEEP_CPU_LOG" "$SWEEP_T0")"
PEAK_ACHIEVED_RPS="$(jq -r '.peak_achieved_rps' <<<"$SATURATION_JSON")"
SATURATION_RPS="$(jq -r '.saturation_rps' <<<"$SATURATION_JSON")"
echo "--- peak_achieved_rps=$PEAK_ACHIEVED_RPS saturation_rps=$SATURATION_RPS (client pin=${K6_PIN_PCT}%, cores=$K6_CORES)"

# Validity: at least one rung was measured with the client sound (headroom, no
# drops, low errors). If EVERY rung was excluded the rig was compromised
# throughout and no server figure is trustworthy. PROVOCATION (observed false):
# a synthetic ladder with the k6 CPU above 85% of pin at every rung yields
# peak_achieved_rps=0 and this check false (see the item's can-it-fail evidence).
if awk -v v="$PEAK_ACHIEVED_RPS" 'BEGIN{exit !(v+0>0)}'; then
  add_check "sweep_client_had_headroom" true "peak_achieved_rps=${PEAK_ACHIEVED_RPS} measured with client headroom, no dropped iterations, low errors"
else
  add_check "sweep_client_had_headroom" false "every sweep rung was excluded (client CPU-pinned / VU-starved / erroring) — the k6 client, not MockServer, was the bottleneck; no server throughput figure is trustworthy"
fi

# --- INFO-log-level publication arm (plan open question 5) ---------------------
# Re-measure ONLY the two PUBLISHED figure families — the knee curve (sweep.js) and
# the per-behaviour percentiles (regression.js, http + https_h2) — against a SECOND
# SUT running at the SHIPPED-DEFAULT log level (INFO). The owner's decision: the
# tracked baseline stays ERROR (a performance-sensitive user really does set ERROR,
# so publishing it is legitimate and every historical S3 run is ERROR), but the
# INFO figure is published ALONGSIDE it and LABELLED as INFO so nobody is misled
# about the out-of-the-box number.
#
# NON-GATING BY CONSTRUCTION. This arm never fails the step and never touches the
# ERROR baseline: (1) its output lands under DISTINCT keys (info_log_level_arm.*),
# not .behaviours / .sweep / peak_achieved_rps, so it can never be confused with or
# diffed against the ERROR series; (2) it is EXCLUDED from VALIDITY_CHECKS, so an
# INFO hiccup cannot flip validity.valid and block the ERROR baseline from being
# stored; (3) every fallible command is guarded so a failure degrades to
# measured:false, not an aborted run. Plan open question 9: land notify-only,
# observe ten runs, THEN attach a budget — the info_* budget entries carry no
# `gating` (notify-only) and `provisional: true` until that history exists.
#
# The INFO SUT is pinned to the SAME cpuset as the ERROR SUT (SERVER_CPUS) and runs
# HERE, right after the ERROR sweep, while the ERROR SUT is idle — so the INFO knee
# is measured under the same core budget as the ERROR knee (a fair comparison) with
# negligible contention. It is torn down immediately afterwards to free its heap
# before the growth phase. regression.js / sweep.js each seed AND reset the SUT they
# target, so pointing them at the INFO alias keeps the two SUTs isolated.
INFO_ARM_JSON='{}'
INFO_ARM_ATTEMPTED=false
if [ "$PERF_INFO_ARM" = "true" ]; then
  INFO_ARM_ATTEMPTED=true
  INFO_T_START="$(date -u +%s)"
  echo "--- INFO-log-level arm: starting SUT ($MOCKSERVER_IMAGE, MOCKSERVER_LOG_LEVEL=INFO, pinned to $SERVER_CPUS)"
  INFO_MEASURED=false
  # publish="" (no host port needed — log level is read via docker inspect, not the
  # metrics endpoint); same memory bound + file-body mount + image as the ERROR SUT
  # so the JS-template and file-body arms behave identically. Guarded with `|| true`
  # so a docker-run failure here degrades the (notify-only) INFO arm rather than
  # aborting the ERROR-baseline run under `set -e`; a failed start leaves no
  # container, so wait_ready then reports not-ready and the arm records measured:false.
  start_mockserver "$INFO_SERVER" "$SERVER_CPUS" "$INFO_SERVER_ALIAS" "" "$SERVER_MEMORY" "$FILE_BODY_MOUNT" "INFO" \
    || echo "WARNING: INFO SUT failed to start — INFO arm will record measured:false" >&2
  if wait_ready "$INFO_SERVER"; then
    INFO_MEASURED=true
    # Per-behaviour percentiles at INFO (http + https_h2), same durations/arms as the
    # ERROR regression so the two are comparable. Guarded: a k6 failure degrades this
    # arm to measured:false rather than aborting the (ERROR-baseline) run.
    run_regression "http"     "http://${INFO_SERVER_ALIAS}:1080"  "false" "info-regression-http.json"  || { echo "WARNING: INFO regression http failed — INFO arm degraded" >&2; INFO_MEASURED=false; }
    run_regression "https_h2" "https://${INFO_SERVER_ALIAS}:1080" "true"  "info-regression-https.json" || { echo "WARNING: INFO regression https_h2 failed — INFO arm degraded" >&2; INFO_MEASURED=false; }
    # Knee curve at INFO (same ladder + saturation methodology as the ERROR sweep).
    echo "--- INFO-log-level arm: sweep.js (knee curve at INFO)"
    run_sweep "$INFO_SWEEP_K6" "$INFO_SERVER_ALIAS" "$OUT_DIR/info-sweep.json" "$OUT_DIR/info-sweep-k6-cpu.csv" \
      || { echo "WARNING: INFO sweep failed — INFO knee degraded" >&2; INFO_MEASURED=false; }
  else
    echo "WARNING: INFO SUT did not become ready — INFO arm skipped (notify-only, ERROR baseline unaffected)" >&2
  fi

  # Log level the INFO SUT ACTUALLY received (observed from its container env) — the
  # self-describing tag that lets a reader tell an INFO record from an ERROR one by
  # the DATA, not by which key it landed under (plan open question 5, point 4). Read
  # the same way the ERROR baseline's config.log_level is (container_env), i.e. an
  # extension of the existing config-block mechanism, not a parallel one.
  INFO_LOG_LEVEL_VAL="$(container_env MOCKSERVER_LOG_LEVEL "$INFO_SERVER")"; INFO_LOG_LEVEL_SRC="observed"
  [ -n "$INFO_LOG_LEVEL_VAL" ] || { INFO_LOG_LEVEL_VAL="INFO"; INFO_LOG_LEVEL_SRC="declared"; }

  # Merge the http + https_h2 behaviours (guarded to {} on any missing/unparsable
  # file), derive the INFO saturation with the SHARED derive_saturation (identical
  # knee methodology to the ERROR arm), then assemble the self-describing block.
  INFO_BEHAVIOURS="$(jq -sc '(.[0].behaviours // {}) + (.[1].behaviours // {})' \
    "$OUT_DIR/info-regression-http.json" "$OUT_DIR/info-regression-https.json" 2>/dev/null || echo '{}')"
  jq -e . >/dev/null 2>&1 <<<"$INFO_BEHAVIOURS" || INFO_BEHAVIOURS='{}'
  INFO_SWEEP_JSON="$(cat "$OUT_DIR/info-sweep.json" 2>/dev/null || echo '{}')"
  jq -e . >/dev/null 2>&1 <<<"$INFO_SWEEP_JSON" || INFO_SWEEP_JSON='{}'
  if [ -f "$OUT_DIR/info-sweep.json" ]; then
    INFO_SATURATION_JSON="$(derive_saturation "$OUT_DIR/info-sweep.json" "$OUT_DIR/info-sweep-k6-cpu.csv" "$LAST_SWEEP_T0" 2>/dev/null || echo '{}')"
  else
    INFO_SATURATION_JSON='{}'
  fi
  jq -e . >/dev/null 2>&1 <<<"$INFO_SATURATION_JSON" || INFO_SATURATION_JSON='{}'

  INFO_ARM_JSON="$(jq -n \
    --argjson measured "$INFO_MEASURED" \
    --arg log_level "$INFO_LOG_LEVEL_VAL" --arg log_level_src "$INFO_LOG_LEVEL_SRC" \
    --arg image "$MOCKSERVER_IMAGE" --arg image_digest "$IMAGE_DIGEST" \
    --arg server_cpus "${SERVER_CPUS:-none}" --arg k6_cpus "${K6_CPUS:-none}" \
    --argjson behaviours "$INFO_BEHAVIOURS" \
    --argjson sweep "$INFO_SWEEP_JSON" \
    --argjson saturation "$INFO_SATURATION_JSON" '
    {
      # The log level is carried EXPLICITLY in the record so a reader can tell an
      # INFO number from an ERROR number by the data alone — never by convention or
      # by which key it landed under. The ERROR baseline is self-described the same
      # way at top-level .config.log_level; this is the INFO twin of that tag.
      measured: $measured,
      config: {
        log_level: $log_level,
        log_level_source: $log_level_src,
        image: $image,
        image_digest: $image_digest,
        cpusets: { server: $server_cpus, k6: $k6_cpus }
      },
      # Per-behaviour percentiles (http + https_h2 merged), SAME shape as the ERROR
      # .behaviours but under a distinct key so it is never fingerprint-matched or
      # diffed against the ERROR series.
      behaviours: $behaviours,
      # The knee curve at INFO + its derived saturation (peak_achieved_rps is the
      # continuous ceiling; saturation_rps is the ladder-quantised knee).
      sweep: { proto: ($sweep.proto // "http"), points: ($sweep.points // []) },
      saturation: $saturation,
      peak_achieved_rps: ($saturation.peak_achieved_rps // null),
      saturation_rps: ($saturation.saturation_rps // null)
    }')"
  INFO_PEAK="$(jq -r '.peak_achieved_rps // "n/a"' <<<"$INFO_ARM_JSON")"
  INFO_SAT="$(jq -r '.saturation_rps // "n/a"' <<<"$INFO_ARM_JSON")"
  INFO_T_END="$(date -u +%s)"
  echo "--- INFO-log-level arm done (log_level=${INFO_LOG_LEVEL_VAL} measured=${INFO_MEASURED} peak_achieved_rps=${INFO_PEAK} saturation_rps=${INFO_SAT}); added wall-clock $((INFO_T_END - INFO_T_START))s"
  # Free the INFO SUT's heap before the growth phase (cleanup() also reaps it).
  docker rm -f "$INFO_SERVER" >/dev/null 2>&1 || true
else
  echo "--- INFO-log-level arm skipped (PERF_INFO_ARM=$PERF_INFO_ARM)"
fi

# --- resource sampler (background) --------------------------------------------
# Append timestamped CPU% (docker stats) + heap bytes + gc seconds (metrics) every
# SAMPLE_INTERVAL seconds. Runs only during the growth phase so the trajectory is
# attributable to the sustained fill load.
sampler() {
  echo "ts,cpu_pct,heap_bytes,gc_seconds,threads" > "$SAMPLE_LOG"
  while true; do
    local cpu metrics heap gc threads ts
    ts="$(date -u +%s)"
    cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$SERVER" 2>/dev/null | tr -d '% ' || echo '')"
    metrics="$(curl -s --max-time 4 "$SERVER_METRICS_URL" 2>/dev/null || echo '')"
    heap="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_memory_used_bytes\{area="heap"\}/{print $2}')"
    gc="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_gc_collection_seconds_sum/{s+=$2} END{print s}')"
    threads="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_threads_current/{print $2}')"
    printf '%s,%s,%s,%s,%s\n' "$ts" "${cpu:-}" "${heap:-}" "${gc:-}" "${threads:-}" >> "$SAMPLE_LOG"
    sleep "$SAMPLE_INTERVAL"
  done
}

echo "--- growth.js (sustained load + resource sampling)"
sampler & SAMPLER_PID=$!
# shellcheck disable=SC2046
docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
  -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
  -v "$OUT_DIR:/out" \
  -e "BASE_URL=http://${SERVER_ALIAS}:1080" \
  -e "K6_GROWTH_RESULT_PATH=/out/growth.json" \
  ${K6_GROWTH_DURATION:+-e K6_GROWTH_DURATION="$K6_GROWTH_DURATION"} \
  ${K6_GROWTH_RATE:+-e K6_GROWTH_RATE="$K6_GROWTH_RATE"} \
  ${K6_GROWTH_PROBE:+-e K6_GROWTH_PROBE="$K6_GROWTH_PROBE"} \
  "$K6_IMAGE" run /k6/growth.js
kill "$SAMPLER_PID" >/dev/null 2>&1 || true; SAMPLER_PID=""

# --- forward.js (forward connection-pool regression guard) --------------------
# Runs the previously-dark forward guard against the dedicated upstream already
# started for the run. Its error-rate threshold is the real gate: with pooling
# regressed the SUT opens a fresh upstream socket per request, exhausts ephemeral
# ports (BindException) and the error rate spikes. k6 exits non-zero on a
# threshold breach (aborting threshold), so the exit is captured tolerantly and
# the verdict folded into the result. A breach is a REAL regression (surfaced by
# compare's forward.error_rate row), NOT a rig-invalidity, so it does not touch
# the validity block. Runs last: forward.js resets the SUT in teardown.
echo "--- forward.js (forward connection-pool regression guard)"
FORWARD_EXIT=0
# shellcheck disable=SC2046
docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
  -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
  -v "$OUT_DIR:/out" \
  -e "BASE_URL=http://${SERVER_ALIAS}:1080" \
  -e "FORWARD_UPSTREAM_HOST=mockserver-upstream:1080" \
  -e "K6_FORWARD_RESULT_PATH=/out/forward.json" \
  ${K6_FWD_PEAK_RATE:+-e K6_FWD_PEAK_RATE="$K6_FWD_PEAK_RATE"} \
  ${K6_FWD_HOLD:+-e K6_FWD_HOLD="$K6_FWD_HOLD"} \
  "$K6_IMAGE" run /k6/forward.js || FORWARD_EXIT=$?
if [ "$FORWARD_EXIT" -ne 0 ]; then
  echo "WARNING: forward.js exited $FORWARD_EXIT — forward-pool guard threshold TRIPPED (error rate over limit)" >&2
fi
FORWARD_JSON="$(cat "$OUT_DIR/forward.json" 2>/dev/null || echo '{}')"

# --- proxy.js: item 9a (forward proxy) + item 14 (TLS/mTLS handshake) ----------
# ONE run, TWO phases (the plan: item 14 shares item 9a's containers and run):
#   9a  forward mode — MockServer AS A PROXY. The k6 container gets HTTP_PROXY /
#       HTTPS_PROXY pointed at the SUT, so an http:// upstream target becomes an
#       absolute-URI forward and an https:// target a CONNECT tunnel carrying TLS
#       through the SUT to the upstream already started for the run (MockServer may
#       terminate that tunnel TLS itself with a generated cert). Emits a
#       .behaviours object (forward_absolute_proxy / forward_connect_proxy) that
#       perf-test-compare.sh picks up via its existing behaviours.* budgets with NO
#       jq change — which DOES grow the k6 arm-set fingerprint and so resets the k6
#       baseline ONCE (intended: the arm set changed). The SUT is NOT reset/seeded
#       for this — an absolute-URI/CONNECT request addressed to the UPSTREAM is
#       proxied regardless of the SUT's own expectations.
#   14  handshake mode — MockServer's INBOUND TLS handshake cost, which the
#       https_h2 regression run reuses-away to ~0. k6 hits three DISTINCT SUTs with
#       noConnectionReuse (a fresh TCP+TLS handshake every iteration): TLS 1.3
#       server-only (the main SUT), mTLS-required, and native-provider-absent (the
#       Dockerfile's documented JDK-provider fallback, forced via
#       -Dio.netty.handler.ssl.noOpenSsl=true — nothing else exercises it under
#       load). Emits a .tls_handshake object; this step augments each arm with
#       server CPU + JVM allocation per handshake, sampled per-SUT across the run.
# BEST-EFFORT / NON-FATAL (like the laptop profile): a proxy/handshake failure must
# never cost the k6/growth result its place in the baseline. Both blocks default to
# {} and compare is head-driven, so an absent block simply emits zero metrics.
MTLS="mockserver-mtls-${RUN_ID}"
JDK_SUT="mockserver-jdk-${RUN_ID}"
HS_CERT_DIR=""
cleanup_proxy() {
  docker rm -f "$MTLS" "$JDK_SUT" >/dev/null 2>&1 || true
  [ -n "${HS_CERT_DIR:-}" ] && rm -rf "$HS_CERT_DIR" >/dev/null 2>&1 || true
}
PROXY_FWD_JSON='{}'
HANDSHAKE_JSON='{}'
if [ "${PERF_PROXY_PROFILE:-true}" = "true" ]; then
  # -- 9a: forward-proxy latency arms (behaviours) --
  echo "--- proxy.js (item 9a: forward-proxy — absolute-URI + CONNECT tunnel)"
  # shellcheck disable=SC2046
  docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "K6_PROXY_MODE=forward" \
    -e "HTTP_PROXY=http://${SERVER_ALIAS}:1080" -e "HTTPS_PROXY=http://${SERVER_ALIAS}:1080" \
    -e "http_proxy=http://${SERVER_ALIAS}:1080" -e "https_proxy=http://${SERVER_ALIAS}:1080" \
    -e "NO_PROXY=" -e "no_proxy=" \
    -e "FORWARD_UPSTREAM_HOST=mockserver-upstream:1080" \
    -e "K6_PROXY_RESULT_PATH=/out/proxy-forward.json" \
    ${K6_PROXY_RATE:+-e K6_PROXY_RATE="$K6_PROXY_RATE"} \
    ${K6_PROXY_DURATION:+-e K6_PROXY_DURATION="$K6_PROXY_DURATION"} \
    ${K6_PROXY_WARMUP:+-e K6_PROXY_WARMUP="$K6_PROXY_WARMUP"} \
    "$K6_IMAGE" run /k6/proxy.js || echo "WARNING: proxy.js forward mode failed — no proxy behaviours this run (notify-only)" >&2
  PROXY_FWD_JSON="$(cat "$OUT_DIR/proxy-forward.json" 2>/dev/null || echo '{}')"
  jq -e . >/dev/null 2>&1 <<<"$PROXY_FWD_JSON" || PROXY_FWD_JSON='{}'

  # -- 14: TLS/mTLS/native-absent handshake arms --
  # openssl generates a throwaway CA + client cert (the mTLS arm presents the
  # client cert; the mTLS SUT trusts the CA). No openssl => skip handshake (the
  # forward arms above still stand). Runs on the agent, not in a container.
  if command -v openssl >/dev/null 2>&1; then
    HS_CERT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-hs-certs.XXXXXX")"
    chmod 0755 "$HS_CERT_DIR"
    (
      cd "$HS_CERT_DIR"
      openssl req -x509 -newkey rsa:2048 -keyout ca.key -out ca.pem -days 2 -nodes -subj "/CN=perf-mtls-ca" 2>/dev/null
      openssl req -newkey rsa:2048 -keyout client.key -out client.csr -nodes -subj "/CN=perf-mtls-client" 2>/dev/null
      openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out client.pem -days 2 2>/dev/null
      chmod 0644 ca.pem client.pem client.key
    )
    if [ -s "$HS_CERT_DIR/client.pem" ] && [ -s "$HS_CERT_DIR/ca.pem" ]; then
      echo "--- item 14 handshake SUTs: mTLS (trusts generated CA) + native-absent (noOpenSsl)"
      # mTLS SUT: require + trust our CA. native-absent SUT: force Netty's JDK
      # provider (the documented fallback). Metrics ENABLED on both so the
      # per-handshake CPU/alloc sampling below can read their counters.
      # shellcheck disable=SC2046
      docker run -d --rm --name "$MTLS" --network "$NETWORK" --network-alias mockserver-mtls \
        $(cpuset_arg "$UPSTREAM_CPUS") -v "$HS_CERT_DIR:/hs-certs:ro" \
        -e MOCKSERVER_LOG_LEVEL=ERROR -e MOCKSERVER_DISABLE_SYSTEM_OUT=true -e MOCKSERVER_METRICS_ENABLED=true \
        -e MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_REQUIRED=true \
        -e MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN=/hs-certs/ca.pem \
        "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null 2>&1 || true
      # shellcheck disable=SC2046
      docker run -d --rm --name "$JDK_SUT" --network "$NETWORK" --network-alias mockserver-jdk \
        $(cpuset_arg "$UPSTREAM_CPUS") \
        -e MOCKSERVER_LOG_LEVEL=ERROR -e MOCKSERVER_DISABLE_SYSTEM_OUT=true -e MOCKSERVER_METRICS_ENABLED=true \
        -e JAVA_TOOL_OPTIONS="-Dio.netty.handler.ssl.noOpenSsl=true" \
        "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null 2>&1 || true
      wait_ready "$MTLS" || true
      wait_ready "$JDK_SUT" || true
      # Seed /simple over PLAIN HTTP on each SUT (port unification: mTLS gates only
      # TLS handshakes, so a plain-HTTP control-plane call needs no client cert).
      # tls13 arm hits the main SUT ($SERVER_ALIAS), which forward.js reset — reseed.
      for alias in "$SERVER_ALIAS" mockserver-mtls mockserver-jdk; do
        docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 -s -X PUT \
          "http://${alias}:1080/mockserver/expectation" -H 'Content-Type: application/json' \
          -d '[{"httpRequest":{"path":"/simple"},"httpResponse":{"statusCode":200,"body":"simple"},"times":{"unlimited":true}}]' \
          -o /dev/null -w "seed ${alias} HTTP %{http_code}\n" || true
      done

      # mTLS NEGATIVE control (committed assertion): a TLS request WITHOUT a client
      # cert MUST be rejected. The k6 mtls arm is positive-only (it always presents a
      # cert), so on its own it cannot distinguish "mTLS enforced + cert accepted"
      # from "mTLS not enforced at all" — a positive-only probe is exactly the shape
      # that has bitten this repo before. Here curl with NO cert must fail the
      # handshake (curl exit 35/56, no 2xx). If it returns a 2xx, mTLS is NOT being
      # enforced and the mtls arm's number is not an mTLS measurement — record that on
      # the arm (mtls_enforced:false) and warn loudly, rather than baselining it as if
      # it were. Non-fatal (the whole handshake phase is notify-only best-effort).
      MTLS_ENFORCED=null   # unknown until positively proven either way
      # Fail CLOSED, not open. A no-cert probe that merely fails to get a 2xx proves
      # nothing: a timeout, a curl image that would not start, or any transport fault
      # would otherwise be recorded as "mTLS enforced" - a control confirming something
      # it never tested. So require POSITIVE evidence of a TLS-layer rejection (curl 35
      # SSL connect error / 56 recv failure) before claiming enforcement, and emit null
      # (unknown) for anything else.
      MTLS_NOCERT_CODE="$(docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 \
        -s -k -o /dev/null -w '%{http_code}' --max-time 8 "https://mockserver-mtls:1080/simple" 2>/dev/null)" || MTLS_NOCERT_RC=$?
      MTLS_NOCERT_RC="${MTLS_NOCERT_RC:-0}"
      if printf '%s' "${MTLS_NOCERT_CODE:-000}" | grep -qE '^2..$'; then
        MTLS_ENFORCED=false
        echo "WARNING: mTLS negative control FAILED — https://mockserver-mtls:1080/simple returned ${MTLS_NOCERT_CODE} with NO client cert, so tlsMutualAuthenticationRequired is NOT being enforced; the mtls handshake arm is therefore not a true mTLS measurement this run." >&2
      elif [ "$MTLS_NOCERT_RC" = "35" ] || [ "$MTLS_NOCERT_RC" = "56" ]; then
        MTLS_ENFORCED=true
        echo "--- mTLS negative control OK: no-cert request rejected at the TLS layer (curl exit ${MTLS_NOCERT_RC}), so mutual auth IS enforced"
      else
        MTLS_ENFORCED=null
        echo "WARNING: mTLS negative control INCONCLUSIVE — no-cert probe neither got a 2xx nor failed at the TLS layer (curl exit ${MTLS_NOCERT_RC}, http_code ${MTLS_NOCERT_CODE:-000}); enforcement is UNPROVEN this run, so mtls_enforced is recorded as null rather than assumed true." >&2
      fi

      # Per-SUT resource snapshot: cumulative JVM allocation counter (monotonic —
      # end-start is exact allocation over the window; null on an image predating
      # jvm_memory_allocated_bytes) + requests_received_count (the EXACT handshake
      # denominator, since noConnectionReuse => 1 request per fresh handshake, and a
      # delta matches the same window as the resource delta). Scraped over the
      # network so no host port publishing is needed.
      hs_metric() { # alias  metric_prefix
        docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 -s --max-time 5 \
          "http://$1:1080/mockserver/metrics" 2>/dev/null \
          | awk -v p="$2" '$1==p {print $2; exit}' || true
      }
      declare -A HS_ALIAS=( [tls13]="$SERVER_ALIAS" [mtls]=mockserver-mtls [jdk]=mockserver-jdk )
      declare -A A0 R0
      for arm in tls13 mtls jdk; do
        A0[$arm]="$(hs_metric "${HS_ALIAS[$arm]}" jvm_memory_allocated_bytes)"
        R0[$arm]="$(hs_metric "${HS_ALIAS[$arm]}" requests_received_count)"
      done

      # Background per-SUT CPU sampler (docker stats — host-side, no container spawn)
      # over the handshake window; integrated to CPU-seconds below.
      HS_CPU_LOG="$OUT_DIR/handshake-cpu.csv"; echo "ts,arm,cpu_pct" > "$HS_CPU_LOG"
      HS_CPU_PID=""
      hs_cpu_sampler() {
        while true; do
          local ts; ts="$(date -u +%s)"
          for arm in tls13 mtls jdk; do
            local nm cpu
            case "$arm" in tls13) nm="$SERVER";; mtls) nm="$MTLS";; jdk) nm="$JDK_SUT";; esac
            cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$nm" 2>/dev/null | tr -d '% ' || echo '')"
            [ -n "$cpu" ] && printf '%s,%s,%s\n' "$ts" "$arm" "$cpu" >> "$HS_CPU_LOG"
          done
          sleep "${PERF_HS_SAMPLE_INTERVAL:-2}"
        done
      }
      hs_cpu_sampler & HS_CPU_PID=$!
      HS_T0="$(date -u +%s)"

      echo "--- proxy.js (item 14: TLS 1.3 + mTLS + native-absent handshake cost)"
      # shellcheck disable=SC2046
      docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
        -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
        -v "$OUT_DIR:/out" -v "$HS_CERT_DIR:/hs-certs:ro" \
        -e "K6_PROXY_MODE=handshake" \
        -e "K6_HS_TLS13_URL=https://${SERVER_ALIAS}:1080" \
        -e "K6_HS_MTLS_URL=https://mockserver-mtls:1080" \
        -e "K6_HS_JDK_URL=https://mockserver-jdk:1080" \
        -e "K6_HS_CLIENT_CERT=/hs-certs/client.pem" -e "K6_HS_CLIENT_KEY=/hs-certs/client.key" \
        -e "K6_PROXY_RESULT_PATH=/out/proxy-handshake.json" \
        ${K6_HS_RATE:+-e K6_HS_RATE="$K6_HS_RATE"} \
        ${K6_HS_DURATION:+-e K6_HS_DURATION="$K6_HS_DURATION"} \
        "$K6_IMAGE" run /k6/proxy.js || echo "WARNING: proxy.js handshake mode failed — no tls_handshake block this run (notify-only)" >&2

      kill "$HS_CPU_PID" >/dev/null 2>&1 || true
      HS_T1="$(date -u +%s)"; HS_WINDOW_S=$(( HS_T1 - HS_T0 )); [ "$HS_WINDOW_S" -gt 0 ] || HS_WINDOW_S=1

      HANDSHAKE_JSON="$(cat "$OUT_DIR/proxy-handshake.json" 2>/dev/null || echo '{}')"
      jq -e . >/dev/null 2>&1 <<<"$HANDSHAKE_JSON" || HANDSHAKE_JSON='{}'

      # Augment each arm with cpu_ms_per_handshake + alloc_kb_per_handshake.
      # denominator = requests_received delta on that arm's SUT (exact handshakes in
      # the window). alloc delta / handshakes -> bytes; cpu-seconds = mean(cpu%)/100
      # * window / handshakes. Any missing piece (metric absent, zero handshakes)
      # leaves that field null (compare drops nulls) rather than emitting a bogus 0.
      for arm in tls13 mtls jdk; do
        a1="$(hs_metric "${HS_ALIAS[$arm]}" jvm_memory_allocated_bytes)"
        r1="$(hs_metric "${HS_ALIAS[$arm]}" requests_received_count)"
        hs="$(awk -v a="${R0[$arm]:-}" -v b="${r1:-}" 'BEGIN{ if(a!=""&&b!=""&&(b-a)>0) printf "%d", b-a; else print "" }')"
        alloc_kb="$(awk -v a="${A0[$arm]:-}" -v b="${a1:-}" -v n="$hs" 'BEGIN{ if(a!=""&&b!=""&&n!=""&&n+0>0&&(b-a)>0) printf "%.3f", (b-a)/n/1024; else print "null" }')"
        cpu_ms="$(awk -F',' -v arm="$arm" -v w="$HS_WINDOW_S" -v n="$hs" '
          $2==arm { s+=$3; c++ }
          END{ if(c>0 && n!=""&& n+0>0){ mean=s/c; printf "%.4f", (mean/100.0*w*1000.0)/n } else print "null" }' "$HS_CPU_LOG")"
        HANDSHAKE_JSON="$(jq -c --arg arm "$arm" --argjson akb "${alloc_kb:-null}" --argjson cms "${cpu_ms:-null}" --argjson hs "${hs:-null}" '
          if (.tls_handshake[$arm]) then
            .tls_handshake[$arm].alloc_kb_per_handshake = $akb
            | .tls_handshake[$arm].cpu_ms_per_handshake = $cms
            | .tls_handshake[$arm].resource_handshakes = $hs
          else . end' <<<"$HANDSHAKE_JSON" 2>/dev/null || echo "$HANDSHAKE_JSON")"
      done
      # Record the mTLS-enforcement verdict from the negative control on the mtls arm
      # (a boolean, not a budgeted metric — compare ignores it) so a reader can see
      # whether the mtls figure is a true mTLS measurement or (mtls_enforced:false) a
      # server that did not require the cert. The jdk arm carries no equivalent
      # committed assertion: nothing MockServer exposes at runtime reports which Netty
      # SslProvider is active, and io.netty.handler.ssl.OpenSsl availability is cached
      # at class-init, so an in-JVM assertion would need a forked JVM with the property
      # — not cheap. The flip from OPENSSL to JDK under -Dio.netty.handler.ssl.noOpenSsl
      # is a documented Netty invariant (verified out-of-band against this build's
      # Netty), and the container carries the property (see the JDK_SUT run above); the
      # jdk arm's higher handshake cost vs tls13 is the expected corroborating signal.
      HANDSHAKE_JSON="$(jq -c --argjson enf "$MTLS_ENFORCED" 'if (.tls_handshake.mtls) then .tls_handshake.mtls.mtls_enforced = $enf else . end' <<<"$HANDSHAKE_JSON" 2>/dev/null || echo "$HANDSHAKE_JSON")"
      echo "--- tls_handshake augmented: $(jq -c '.tls_handshake | to_entries | map({(.key): {p50:.value.handshake_p50_ms, hps:.value.handshakes_per_s, cpu_ms:.value.cpu_ms_per_handshake, alloc_kb:.value.alloc_kb_per_handshake}})' <<<"$HANDSHAKE_JSON" 2>/dev/null)"
    else
      echo "WARNING: openssl produced no client cert — skipping handshake arms (forward arms still recorded)" >&2
    fi
  else
    echo "WARNING: openssl unavailable — skipping item 14 handshake arms (forward arms still recorded)" >&2
  fi
  cleanup_proxy
fi

# --- item 8: laptop startup + footprint profile (notify-only) -----------------
# Runs LAST, after every k6 phase, so the box is quiet: the docker sub-items (8a
# ready-median-of-9, idle RSS + threads at --memory 256m/512m/1g, 8d compressed
# image size) launch their own throwaway containers and need only docker + the SUT
# image. The in-JVM sub-items (8b/8c) — the number a MockServerExtension suite
# actually pays per test class, and init-file scaling — need a shaded jar and a
# JDK; the jar is docker-cp'd out of the SUT image (path fixed by the Dockerfile)
# and bench_laptop.py SKIPS 8b/8c loudly if a jar or `java` is unavailable (e.g. a
# JRE-only agent). Emits a `laptop` block merged into result.json; every metric is
# notify-only (laptop.*.<metric> budgets omit `gating`). NON-FATAL by construction:
# the SUT ($SERVER) is still running-but-idle here, so its per-container cgroup RSS
# never pollutes a laptop container's own `docker stats` reading, and a laptop
# measurement failure must never lose the k6/growth result the run exists for.
LAPTOP_JSON='{}'
# Record INTENT separately from success (the 15b two-axis split): `laptop_attempted`
# says the producer tried to measure the profile this run, so compare can tell
# "attempted and failed wholesale" (an empty/incomplete `.laptop` → RED) apart from
# "profile disabled" (never attempted → silent). Without it, `laptop: {}` means both.
LAPTOP_ATTEMPTED=false
if [ "${PERF_LAPTOP_PROFILE:-true}" = "true" ]; then
  LAPTOP_ATTEMPTED=true
  LAPTOP_JAR="${PERF_LAPTOP_JAR:-}"
  if [ -z "$LAPTOP_JAR" ]; then
    _lc="$(docker create "$MOCKSERVER_IMAGE" 2>/dev/null || true)"
    if [ -n "$_lc" ]; then
      docker cp "$_lc:/mockserver-netty-jar-with-dependencies.jar" \
        "$OUT_DIR/mockserver.jar" >/dev/null 2>&1 && LAPTOP_JAR="$OUT_DIR/mockserver.jar"
      docker rm "$_lc" >/dev/null 2>&1 || true
    fi
  fi
  echo "--- item 8 laptop profile (settle ${PERF_LAPTOP_SETTLE:-30}s; jar=${LAPTOP_JAR:-none})"
  # DELIBERATELY NOT an add_check: laptop.* is notify-only, so a laptop-measurement
  # failure must NOT flip .validity.valid false and make compare refuse the whole k6
  # run. On failure the run simply carries no `laptop` block (compare iterates head
  # metrics, so an absent block emits zero laptop metrics — no missing-budget trip).
  if python3 "$REPO_ROOT/scripts/perf/bench_laptop.py" all \
        --jar "$LAPTOP_JAR" --image "$MOCKSERVER_IMAGE" \
        --port "${PERF_LAPTOP_PORT:-23080}" --warmups 1 --runs 9 \
        --settle "${PERF_LAPTOP_SETTLE:-30}" --out "$OUT_DIR/laptop.json"; then
    LAPTOP_JSON="$(cat "$OUT_DIR/laptop.json" 2>/dev/null || echo '{}')"
    # Guard the result-assembly jq: an empty/partial/invalid laptop file must never
    # make `--argjson laptop` fail and lose the whole result. Fall back to {} if the
    # emitted block is not valid JSON.
    jq -e . >/dev/null 2>&1 <<<"$LAPTOP_JSON" || LAPTOP_JSON='{}'
    echo "--- laptop block: $(jq -c '.laptop | keys' <<<"$LAPTOP_JSON" 2>/dev/null)"
  else
    echo "WARNING: laptop profile measurement failed — result carries no laptop block this run (notify-only, does not gate)" >&2
  fi
fi

# --- item 12: LLM/SSE streaming under concurrency -----------------------------
# Drive STREAMING.concurrency concurrent SSE streams (streaming.js, constant-vus)
# while measuring the four item-12 metrics. Two of them k6 CANNOT see (it buffers
# SSE), so they are taken server-side HERE, aligned to k6's phase timeline:
#   - inter-token delay error: a tiny single-threaded reader
#     (tools/sse-fidelity-reader.py) times consecutive `data:` lines. Run IDLE (the
#     match_baseline phase, no streams open — the client-jitter FLOOR / positive
#     control) and again UNDER LOAD (the stream-load phase). The idle floor proves
#     any error growth is the SERVER (scheduler-thread starvation delaying the
#     per-token writeEvent tasks), not the reader — the reader is identical and
#     unloaded in both. Reported as a DISTRIBUTION (p50/p95/p99), never a mean:
#     the failure mode is a fat TAIL while the median stays on time.
#   - heap per open stream: the heap FLOOR (min jvm_memory_used_bytes{area=heap}
#     over a window, the post-GC saw-tooth valley) with the streams open, minus the
#     idle floor, over the concurrency. INCLUDES the streaming log-ring retention
#     (bounded small by config.js STREAMING's arithmetic), so it is an upper bound
#     on per-connection state, which the report states plainly.
# The other two (match-p95 A/B, stream delivery) come from k6's own result. item
# 12's CallerRunsPolicy counter is NOT emitted: MockServer exposes none, and the
# policy does not fire under load (ScheduledThreadPoolExecutor's DelayedWorkQueue
# is unbounded, so CallerRunsPolicy fires only at shutdown — see the report).
# BEST-EFFORT / NON-FATAL like the proxy/laptop profiles: a failure defaults the
# block to {} and never costs the k6/growth result its baseline place.
STREAMING_JSON='{}'
if [ "${PERF_STREAMING:-true}" = "true" ]; then
  if command -v python3 >/dev/null 2>&1; then
    S_WARMUP="${K6_STREAM_WARMUP:-20s}"; S_BASE="${K6_STREAM_MATCH_BASELINE_DURATION:-45s}"
    S_LOAD="${K6_STREAM_LOAD_DURATION:-90s}"; S_SETTLE="${K6_STREAM_SETTLE:-10s}"
    # Default concurrency 300 (not config.js's light-local 100): at delay 20 ms
    # against the dedicated 1-CPU / 2-scheduler-thread / 2-event-loop-thread SUT
    # below, that is reliably past the knee — the match A/B ratio measured 8.0x and
    # 3.9x across repeats (always well above ~1.0), where 200 still occasionally
    # dipped to ~1.6x (near-knee variance). RETENTION at 300/20ms: stream ≈ 4 s,
    # start rate ≈ 75/s, over a 90 s window ≈ 6750 streams × 200 events × ~30 B ≈
    # 40 MB — bounded well under the 1 GB SUT heap.
    S_CONC="${K6_STREAM_CONCURRENCY:-300}"; S_DELAY="${K6_STREAM_DELAY_MS:-20}"; S_PATH="${K6_STREAM_PATH:-/stream}"
    sw="$(to_secs "$S_WARMUP")"; sb="$(to_secs "$S_BASE")"; sl="$(to_secs "$S_LOAD")"; sst="$(to_secs "$S_SETTLE")"
    STREAM_LOAD_START=$(( sw + sb ))
    READER="$REPO_ROOT/mockserver-performance-test/k6/tools/sse-fidelity-reader.py"
    # --- dedicated, deliberately-constrained streaming SUT ---------------------
    # WHY a separate SUT and not the shared 6-core $SERVER: on the pinned CI box
    # actionHandlerThreadCount = max(5, 6) = 6, and 200 concurrent streams at 50
    # tokens/s is ~10k trivial writeEvent tasks/s — nowhere near that pool's knee,
    # so match_p95_ratio reads ~1.0 and the tripwire measures nothing (the MAJOR
    # review finding). A dedicated SUT with a SMALL action-handler pool (default 2)
    # and a low CPU quota (default 1) puts the knee at a modest, DETERMINISTIC
    # concurrency independent of the agent size, so a real scheduling regression
    # moves the number. The scheduler (per-token delay) path is what this exercises;
    # a bounded --memory makes the heap-floor measurement meaningful (it GCs).
    # Constrain BOTH pools: the action-handler pool is where per-token delays queue
    # (fidelity), and the event-loop pool is what a concurrent match shares (the
    # A/B). Leaving the event loops at the default 5 let them absorb the streaming
    # writeAndFlush pressure, so the match A/B stayed flaky near ~1x (measured).
    STREAM_SUT_CPUS="${PERF_STREAM_SUT_CPUS:-1}"
    STREAM_SUT_THREADS="${PERF_STREAM_SUT_THREADS:-2}"
    STREAM_SUT_EVENTLOOP_THREADS="${PERF_STREAM_SUT_EVENTLOOP_THREADS:-2}"
    STREAM_SUT_MEMORY="${PERF_STREAM_SUT_MEMORY:-1g}"
    echo "--- streaming SUT (dedicated, constrained: cpus=${STREAM_SUT_CPUS}, action-handler-threads=${STREAM_SUT_THREADS}, event-loop-threads=${STREAM_SUT_EVENTLOOP_THREADS}, mem=${STREAM_SUT_MEMORY})"
    docker run -d --rm --name "$STREAM_SUT" --network "$NETWORK" --network-alias "mockserver-stream" \
      --cpus "$STREAM_SUT_CPUS" --memory "$STREAM_SUT_MEMORY" -p 127.0.0.1::1080 \
      -e MOCKSERVER_LOG_LEVEL=ERROR -e MOCKSERVER_DISABLE_SYSTEM_OUT=true -e MOCKSERVER_METRICS_ENABLED=true \
      -e "MOCKSERVER_ACTION_HANDLER_THREAD_COUNT=$STREAM_SUT_THREADS" \
      -e "MOCKSERVER_NIO_EVENT_LOOP_THREAD_COUNT=$STREAM_SUT_EVENTLOOP_THREADS" \
      "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null 2>&1 \
      || echo "WARNING: could not start dedicated streaming SUT — no streaming block this run (notify-only)" >&2
    wait_ready "$STREAM_SUT" || echo "WARNING: dedicated streaming SUT not ready" >&2
    # The reader + heap sampler hit the dedicated SUT's HOST-published port (serves
    # /stream AND /mockserver/metrics), so they run on the agent with no extra
    # container CPU and never touch the shared $SERVER's numbers.
    STREAM_HOSTPORT="$(docker port "$STREAM_SUT" 1080/tcp 2>/dev/null | head -1)"
    R_HOST="${STREAM_HOSTPORT%%:*}"; R_PORT="${STREAM_HOSTPORT##*:}"
    [ -n "$R_HOST" ] || R_HOST="127.0.0.1"
    STREAM_METRICS_URL="http://${STREAM_HOSTPORT:-127.0.0.1:1080}/mockserver/metrics"
    # Heap FLOOR: min of N ~1s heap samples. %d avoids the E-notation some images
    # emit (jq --argjson could not parse "1.1E8"); floor tracks the live set far
    # better than an instantaneous sample on the GC saw-tooth.
    stream_heap_floor() {
      local n="$1" i
      for i in $(seq 1 "$n"); do
        curl -s --max-time 4 "$STREAM_METRICS_URL" 2>/dev/null \
          | awk -F' ' '/^jvm_memory_used_bytes\{area="heap"\}/{print $2}'
        sleep 1
      done | awk 'NF{v=$1+0; if(m==""||v<m)m=v} END{if(m=="")print "";else printf "%d",m}'
    }
    echo "--- streaming.js (item 12: LLM/SSE under concurrency=${S_CONC}, delay=${S_DELAY}ms, dedicated constrained SUT)"
    # shellcheck disable=SC2046
    docker run -d --rm --name "$STREAM_K6" --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
      -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" -v "$OUT_DIR:/out" \
      -e "BASE_URL=http://mockserver-stream:1080" \
      -e "K6_STREAM_CONCURRENCY=$S_CONC" \
      -e "K6_STREAM_RESULT_PATH=/out/streaming.json" \
      ${K6_STREAM_WARMUP:+-e K6_STREAM_WARMUP="$K6_STREAM_WARMUP"} \
      ${K6_STREAM_MATCH_BASELINE_DURATION:+-e K6_STREAM_MATCH_BASELINE_DURATION="$K6_STREAM_MATCH_BASELINE_DURATION"} \
      ${K6_STREAM_LOAD_DURATION:+-e K6_STREAM_LOAD_DURATION="$K6_STREAM_LOAD_DURATION"} \
      ${K6_STREAM_SETTLE:+-e K6_STREAM_SETTLE="$K6_STREAM_SETTLE"} \
      ${K6_STREAM_TOKENS:+-e K6_STREAM_TOKENS="$K6_STREAM_TOKENS"} \
      ${K6_STREAM_DELAY_MS:+-e K6_STREAM_DELAY_MS="$K6_STREAM_DELAY_MS"} \
      ${K6_STREAM_MATCH_RATE:+-e K6_STREAM_MATCH_RATE="$K6_STREAM_MATCH_RATE"} \
      "$K6_IMAGE" run /k6/streaming.js >/dev/null 2>&1 \
      || echo "WARNING: could not launch streaming.js — no streaming block this run (notify-only)" >&2
    ST0="$(date -u +%s)"
    stream_sleep_until() { local target="$1" now; now=$(( $(date -u +%s) - ST0 )); [ "$target" -gt "$now" ] && sleep $(( target - now )) || true; }
    # IDLE control: inside the match_baseline phase, before any stream opens.
    stream_sleep_until $(( sw + 3 ))
    STREAM_HEAP_IDLE="$(stream_heap_floor 5)"
    python3 "$READER" --host "$R_HOST" --port "$R_PORT" --path "$S_PATH" \
      --delay-ms "$S_DELAY" --streams 3 --max-tokens 60 --label idle \
      --out "$OUT_DIR/fidelity-idle.json" || echo "WARNING: idle fidelity reader failed" >&2
    # UNDER LOAD: after the stream-load phase start + its settle window.
    stream_sleep_until $(( STREAM_LOAD_START + sst + 3 ))
    STREAM_HEAP_LOAD="$(stream_heap_floor 6)"
    python3 "$READER" --host "$R_HOST" --port "$R_PORT" --path "$S_PATH" \
      --delay-ms "$S_DELAY" --streams 3 --max-tokens 60 --label load \
      --out "$OUT_DIR/fidelity-load.json" || echo "WARNING: load fidelity reader failed" >&2
    # Wait for the background k6 to finish writing its result.
    while docker ps --format '{{.Names}}' | grep -q "^${STREAM_K6}$"; do sleep 2; done
    K6_STREAM_OUT="$(cat "$OUT_DIR/streaming.json" 2>/dev/null || echo '{}')"; jq -e . >/dev/null 2>&1 <<<"$K6_STREAM_OUT" || K6_STREAM_OUT='{}'
    FID_IDLE="$(cat "$OUT_DIR/fidelity-idle.json" 2>/dev/null || echo '{}')"; jq -e . >/dev/null 2>&1 <<<"$FID_IDLE" || FID_IDLE='{}'
    FID_LOAD="$(cat "$OUT_DIR/fidelity-load.json" 2>/dev/null || echo '{}')"; jq -e . >/dev/null 2>&1 <<<"$FID_LOAD" || FID_LOAD='{}'
    HEAP_PER_STREAM="$(awk -v a="${STREAM_HEAP_IDLE:-}" -v b="${STREAM_HEAP_LOAD:-}" -v n="$S_CONC" 'BEGIN{ if(a!=""&&b!=""&&n+0>0&&(b-a)>0) printf "%.1f",(b-a)/n; else print "null" }')"
    STREAMING_JSON="$(jq -c \
      --argjson fi "$FID_IDLE" --argjson fl "$FID_LOAD" \
      --argjson hi "${STREAM_HEAP_IDLE:-null}" --argjson hl "${STREAM_HEAP_LOAD:-null}" \
      --argjson hps "${HEAP_PER_STREAM:-null}" '
      (.streaming // {}) as $s | $s + {
        intertoken_error_idle_p50_ms: ($fi.error_p50_ms // null),
        intertoken_error_idle_p95_ms: ($fi.error_p95_ms // null),
        intertoken_error_idle_p99_ms: ($fi.error_p99_ms // null),
        intertoken_error_load_p50_ms: ($fl.error_p50_ms // null),
        intertoken_error_load_p95_ms: ($fl.error_p95_ms // null),
        intertoken_error_load_p99_ms: ($fl.error_p99_ms // null),
        intertoken_error_p95_ratio: (if (($fi.error_p95_ms // 0) > 0 and $fl.error_p95_ms != null) then (($fl.error_p95_ms / $fi.error_p95_ms) * 1000 | round) / 1000 else null end),
        intertoken_reader_streams_idle: ($fi.streams_ok // null),
        intertoken_reader_streams_load: ($fl.streams_ok // null),
        heap_idle_floor_bytes: $hi, heap_streaming_floor_bytes: $hl, heap_bytes_per_stream: $hps
      }' <<<"$K6_STREAM_OUT")"
    jq -e . >/dev/null 2>&1 <<<"$STREAMING_JSON" || STREAMING_JSON='{}'
    echo "--- streaming: $(jq -c '{match_p95_ratio, match_under_stream_p95_ms, intertoken_error_load_p99_ms, heap_bytes_per_stream}' <<<"$STREAMING_JSON" 2>/dev/null)"
  else
    echo "WARNING: python3 not on the agent — streaming fidelity/heap metrics skipped (notify-only, no streaming block)" >&2
  fi
fi

# --- item 13: clustered state under load (within-run A/B) ---------------------
# The StateBackend SPI + Infinispan backend move expectation reads and event-log
# writes onto a network for the central deployment the owner named; no number
# existed for what that costs. This block runs regression.js UNCHANGED against two
# targets IN THE SAME RUN and records the per-arm RATIO (clustered / control):
#
#   control  : ONE MockServer, stateBackend=memory   (the default InMemory backend)
#   candidate: TWO MockServers, stateBackend=infinispan + clusterEnabled=true,
#              sharing state over a JGroups TCP/TCPPING transport (REPL_SYNC)
#
# BOTH targets run the SAME clustered image (PERF_CLUSTERED_IMAGE), so the ONLY
# variable between the arms is the backend — the CandidateIndexBenchmark within-run
# A/B discipline the plan names as the repo's gold standard: the ratio cancels
# host / JVM / GC / image noise almost entirely, which is why .clustered_state
# rides the FULL baseline (NOT the k6 arm-set fingerprint or the image digest) in
# perf-test-compare.sh.
#
# REUSE, DON'T REBUILD: PERF_CLUSTERED_IMAGE defaults to the snapshot-clustered
# image; locally point it at the container-tests `integration_testing_clustered`
# image, which is assembled from the SAME clustered-libs jars the container-tests
# pipeline already builds (netty fat jar + /libs/* = Infinispan + JGroups). We do
# NOT build Infinispan a second time here.
#
# WHAT THE HOT PATH ACTUALLY TOUCHES (why the ratio is what it is): seedRegression
# seeds every expectation with times:{unlimited:true}, so there is NO per-request
# Times CAS (clusterSharedTimesEnabled fires only on a bounded Times). Matching
# reads hit each node's LOCAL compiled-matcher cache (reconciled via invalidation),
# not a network read per request, and the event log is node-local. So the per-
# request network cost for this read/forward/template mix is ~zero; what the ratio
# captures is the STEADY-STATE overhead of running the clustered stack on the
# request path (Infinispan on the classpath, background JGroups FD_ALL3 heartbeats
# + STABLE gossip stealing a little CPU) plus the one-time seed replication. That
# is the honest, useful answer for the central deployment: how far a node's per-
# request latency moves merely by being a clustered member.
#
# RETENTION (the OOM lesson): the count-bounded event-log ring holds FULL bodies,
# residence = maxLogEntries / total_ACHIEVED_rps (LONGER, without bound, as achieved
# throughput falls). k6 drives ONLY the entry node (control, or cluster node A), so
# ONLY that node's ring fills with request bodies — exactly like the main SUT, and it
# gets the SAME PERF_CLUSTERED_MEMORY (2 GB default -> ~1.5 GB heap,
# maxLogEntries=100000). The per-arm rate x residence x body figures the config.js
# arithmetic derives (large_10mb@0.1rps -> ~111 MB, large_1mb@0.5rps -> ~55 MB,
# large-4KB@200rps -> ~89 MB) hold ONLY at residence 111 s; they run away as this node
# contends. So the clustered nodes get the SAME maxEventLogSizeInBytes body-byte OOM
# guard (start_clu, above) as the main SUT, which caps total retained body bytes
# regardless of residence — and applies identically on control and cluster, so it does
# not bias the ratio. The
# SECOND node (B) receives NO request load, so its ring stays ~empty; it holds only
# the seeded expectation set (tiny, seeded once) + bounded JGroups buffers (UFC/MFC
# 2M each, NAKACK2/UNICAST3 send/recv, FRAG2 60K — single-digit MB). REPL_SYNC
# replicates only expectation/scenario WRITES, of which there are ~none after the
# one-time seed, so node B never accumulates bodies. The clustered arm therefore
# adds no retention risk beyond the control's, per node.
#
# BEST-EFFORT / NOTIFY-ONLY like streaming/laptop: a failure defaults the block to
# {} and never costs the k6/growth result its baseline place. BUT — item 8's lesson
# — an empty block must not read as both "off" and "broken". So `clustered_attempted`
# turns true ONLY once the clustered image is present and we start forming the
# cluster; from then a cluster that forms but produces nothing (view < 2, state did
# not cross, or a null ratio) makes the `clustered_metrics_present` validity check
# FALSE and REDS the build (loud), while PERF_CLUSTERED=false or an absent image
# stays a green skip.
CLUSTERED_JSON='{}'
CLUSTERED_ATTEMPTED=false
CLU_CTRL_BEHAVIOURS='{}'
CLU_CAND_BEHAVIOURS='{}'
if [ "${PERF_CLUSTERED:-true}" = "true" ]; then
  CLU_IMAGE="${PERF_CLUSTERED_IMAGE:-mockserver/mockserver:mockserver-snapshot-clustered}"
  if ! docker image inspect "$CLU_IMAGE" >/dev/null 2>&1; then
    echo "WARNING: clustered image '$CLU_IMAGE' not present — clustered A/B SKIPPED (notify-only)." >&2
    echo "         Set PERF_CLUSTERED_IMAGE to the container-tests clustered image (built from the" >&2
    echo "         clustered-libs jars), or PERF_CLUSTERED=false to disable. NOT counted as attempted." >&2
  else
    CLUSTERED_ATTEMPTED=true
    CLU_CPUS="${PERF_CLUSTERED_CPUS:-2}"
    CLU_MEM="${PERF_CLUSTERED_MEMORY:-2g}"
    CLU_NAME="perf-cluster-${RUN_ID}"
    # Shorter measured window than the main run (the ratio is robust and both arms
    # are measured identically, so a full 2 m window buys little); still long enough
    # for the small-body arms to clear MIN_TAIL_SAMPLES for a p95 ratio.
    CLU_WARMUP="${K6_CLU_REG_WARMUP:-20s}"
    CLU_DURATION="${K6_CLU_REG_DURATION:-45s}"
    echo "--- clustered A/B: image=$CLU_IMAGE cpus=$CLU_CPUS mem=$CLU_MEM warmup=$CLU_WARMUP duration=$CLU_DURATION"

    # JGroups TCP + TCPPING transport for two SEPARATE containers (the built-in
    # loopback stack is in-JVM only and would form two clusters of one). initial_hosts
    # lists both node aliases; bind matches the container's eth0 on the docker network.
    # Written world-readable so the image's non-root user can read the mount.
    CLU_JG="$OUT_DIR/jgroups-tcp.xml"
    cat > "$CLU_JG" <<'JGROUPS_XML'
<config xmlns="urn:org:jgroups"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">
    <TCP bind_addr="match-interface:eth0,site_local,loopback"
         bind_port="7800"
         thread_pool.min_threads="0"
         thread_pool.max_threads="20"
         thread_pool.keep_alive_time="30000" />
    <org.jgroups.protocols.TCPPING
         initial_hosts="${JGROUPS_TCPPING_INITIAL_HOSTS}"
         port_range="1" />
    <MERGE3 max_interval="30000" min_interval="10000" />
    <FD_ALL3 timeout="40000" interval="5000" />
    <VERIFY_SUSPECT2 timeout="1500" />
    <pbcast.NAKACK2 use_mcast_xmit="false" />
    <UNICAST3 />
    <pbcast.STABLE desired_avg_gossip="50000" max_bytes="4M" />
    <pbcast.GMS print_local_addr="true" join_timeout="5000" />
    <UFC max_credits="2M" min_threshold="0.4" />
    <MFC max_credits="2M" min_threshold="0.4" />
    <FRAG2 frag_size="60K" />
</config>
JGROUPS_XML
    chmod 0644 "$CLU_JG"

    # Start a clustered-image MockServer. backend=memory|infinispan; when clustered,
    # pass the JGroups mount + initial_hosts + cluster name. The GraalJS + file-body
    # arms are OFF for the clustered A/B (the clustered image bundles no GraalJS, and
    # a JS arm would 500 and abort regression.js in setup()); the core arms
    # (match/forward/template/template_mustache/large/large_1mb/large_10mb) run
    # UNCHANGED — only env differs, the sanctioned parameterisation. start_clu also
    # carries the maxEventLogSizeInBytes body-byte OOM guard (below), for the same
    # reason the main SUT does: these MB arms run here too, on a 1.5 GB clustered
    # heap. The budget is identical on control and cluster, so it evicts symmetrically
    # and cannot bias the within-run clustered/control ratio item 13 measures.
    start_clu() { # name  alias  backend(memory|infinispan)  cluster_name_or_empty
      local name="$1" alias="$2" backend="$3" cname="${4:-}"
      local extra=()
      if [ "$backend" = "infinispan" ]; then
        extra=(
          -e MOCKSERVER_CLUSTER_ENABLED=true
          -e "MOCKSERVER_CLUSTER_NAME=$cname"
          -e MOCKSERVER_CLUSTER_TRANSPORT_CONFIG=/config/jgroups-tcp.xml
          -e "JGROUPS_TCPPING_INITIAL_HOSTS=${CLU_A}[7800],${CLU_B}[7800]"
          -v "$CLU_JG:/config/jgroups-tcp.xml:ro"
        )
      fi
      # ${extra[@]+"${extra[@]}"} — safe expansion of a possibly-empty array under
      # `set -u` (the memory-backend control passes no extra flags), matching the
      # start_mockserver java_opts_arg idiom above.
      docker run -d --rm --name "$name" --network "$NETWORK" --network-alias "$alias" \
        --cpus "$CLU_CPUS" --memory "$CLU_MEM" -p 127.0.0.1::1080 \
        -e MOCKSERVER_LOG_LEVEL=WARN -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
        -e MOCKSERVER_METRICS_ENABLED=true \
        -e MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES="$PERF_MAX_EVENT_LOG_BYTES" \
        -e "MOCKSERVER_STATE_BACKEND=$backend" \
        ${extra[@]+"${extra[@]}"} \
        "$CLU_IMAGE" -serverPort 1080 >/dev/null 2>&1
    }

    clu_hostport() { docker port "$1" 1080/tcp 2>/dev/null | head -1; }
    clu_members()  { curl -s --max-time 4 "http://$1/mockserver/cluster" 2>/dev/null | jq -r '.memberCount // 0' 2>/dev/null || echo 0; }
    clu_clustered(){ curl -s --max-time 4 "http://$1/mockserver/cluster" 2>/dev/null | jq -r '.clustered // false' 2>/dev/null || echo false; }
    # Run regression.js UNCHANGED against one clustered target. Distinct PROTO so its
    # behaviour keys (<op>_<proto>) do not collide with the main run's.
    run_clu_regression() { # proto  base_url  out
      # shellcheck disable=SC2046
      docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
        -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" -v "$OUT_DIR:/out" \
        -e "BASE_URL=$2" -e "PROTO=$1" -e "INSECURE_SKIP_TLS_VERIFY=false" \
        -e "K6_RESULT_PATH=/out/$3" \
        -e "K6_REG_JS_TEMPLATE=false" -e "K6_REG_FILE_BODY_PATH=" \
        -e "K6_REG_WARMUP=$CLU_WARMUP" -e "K6_REG_DURATION=$CLU_DURATION" \
        ${K6_REG_RATE:+-e K6_REG_RATE="$K6_REG_RATE"} \
        ${K6_CLU_REG_RATE:+-e K6_REG_RATE="$K6_CLU_REG_RATE"} \
        "$K6_IMAGE" run /k6/regression.js >/dev/null 2>&1
    }
    # Run the per-request-CROSSING arm (clustered_crossing.js). regression.js above
    # measures MEMBERSHIP overhead (its unlimited-Times matches never leave the node);
    # this drives a BOUNDED-Times match, whose shared-Times compareAndSet is a local
    # ConcurrentHashMap swap on the memory control but a SYNCHRONOUS REPL_SYNC round
    # trip on the cluster — so its clustered/control ratio is the real per-request
    # NETWORK cost (RequestMatchers shared-Times CAS -> InfinispanKeyValueStore
    # .compareAndSet -> cache.replace). Emits the same behaviours shape (op `crossing`).
    run_clu_crossing() { # proto  base_url  out
      # shellcheck disable=SC2046
      docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
        -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" -v "$OUT_DIR:/out" \
        -e "BASE_URL=$2" -e "PROTO=$1" -e "INSECURE_SKIP_TLS_VERIFY=false" \
        -e "K6_CLU_CROSS_RESULT_PATH=/out/$3" \
        -e "K6_CLU_CROSS_WARMUP=$CLU_WARMUP" -e "K6_CLU_CROSS_DURATION=$CLU_DURATION" \
        ${K6_CLU_CROSS_RATE:+-e K6_CLU_CROSS_RATE="$K6_CLU_CROSS_RATE"} \
        "$K6_IMAGE" run /k6/clustered_crossing.js >/dev/null 2>&1
    }

    # --- CONTROL arm: single node, in-memory backend --------------------------
    echo "--- clustered A/B: starting CONTROL (stateBackend=memory)"
    if start_clu "$CLU_CTRL" clu-ctrl memory ""; then wait_ready "$CLU_CTRL" || true; fi
    CTRL_HP="$(clu_hostport "$CLU_CTRL")"
    CTRL_MEMBERS="$(clu_members "$CTRL_HP")"
    CTRL_CLUSTERED="$(clu_clustered "$CTRL_HP")"
    echo "--- clustered A/B: control cluster view members=$CTRL_MEMBERS clustered=$CTRL_CLUSTERED (expect 1 / false)"
    run_clu_regression "clustered_control" "http://clu-ctrl:1080" "clu-control.json" \
      || echo "WARNING: clustered CONTROL regression run failed" >&2
    run_clu_crossing "clustered_control" "http://clu-ctrl:1080" "clu-cross-control.json" \
      || echo "WARNING: clustered CONTROL crossing run failed" >&2
    docker rm -f "$CLU_CTRL" >/dev/null 2>&1 || true

    # --- CANDIDATE arm: two-node Infinispan/JGroups cluster -------------------
    # Positive control (degrade-and-confirm-red): PERF_CLU_BREAK=cluster starts node
    # B in a DIFFERENT cluster so the view never reaches 2 — the genuineness gate
    # below must then RED. Proves the check can go red (not a silent two-of-one).
    CLU_B_NAME="$CLU_NAME"
    if [ "${PERF_CLU_BREAK:-}" = "cluster" ]; then
      CLU_B_NAME="${CLU_NAME}-BROKEN"
      echo "--- clustered A/B: PERF_CLU_BREAK=cluster — node B joins '$CLU_B_NAME' (SELF-TEST: view must stay 1)" >&2
    fi
    echo "--- clustered A/B: starting CANDIDATE 2-node cluster (stateBackend=infinispan, JGroups TCPPING)"
    start_clu "$CLU_A" clu-a infinispan "$CLU_NAME" || echo "WARNING: cluster node A did not start" >&2
    start_clu "$CLU_B" clu-b infinispan "$CLU_B_NAME" || echo "WARNING: cluster node B did not start" >&2
    wait_ready "$CLU_A" || true
    wait_ready "$CLU_B" || true
    A_HP="$(clu_hostport "$CLU_A")"; B_HP="$(clu_hostport "$CLU_B")"
    # Wait (bounded) for the JGroups view to converge to 2 on node A.
    A_MEMBERS=1
    for _ in $(seq 1 30); do
      A_MEMBERS="$(clu_members "$A_HP")"
      [ "${A_MEMBERS:-0}" -ge 2 ] && break
      sleep 2
    done
    B_MEMBERS="$(clu_members "$B_HP")"
    A_CLUSTERED="$(clu_clustered "$A_HP")"
    echo "--- clustered A/B: candidate view members A=$A_MEMBERS B=$B_MEMBERS clustered(A)=$A_CLUSTERED (expect 2 / 2 / true)"
    # DIAGNOSTIC on formation failure: if the view did not converge to 2, dump each
    # node's cluster snapshot + the tail of its JGroups startup logs so a real
    # TCPPING/discovery failure (once the image is wired live) is debuggable rather
    # than a bare "members A=1". The genuineness gate below still REDs — this only
    # explains WHY. (Expected + intentional under the PERF_CLU_BREAK self-test.)
    if [ "${A_MEMBERS:-0}" -lt 2 ]; then
      echo "--- clustered A/B: DIAGNOSTIC — JGroups view did not reach 2; dumping node state" >&2
      echo "    node A /mockserver/cluster: $(curl -s --max-time 4 "http://$A_HP/mockserver/cluster" 2>/dev/null)" >&2
      echo "    node B /mockserver/cluster: $(curl -s --max-time 4 "http://$B_HP/mockserver/cluster" 2>/dev/null)" >&2
      docker logs "$CLU_A" 2>&1 | grep -iE "jgroups|ISPN|view|GMS|TCPPING|cluster" | tail -8 | sed 's/^/    A| /' >&2 || true
      docker logs "$CLU_B" 2>&1 | grep -iE "jgroups|ISPN|view|GMS|TCPPING|cluster" | tail -8 | sed 's/^/    B| /' >&2 || true
    fi

    # PROVE STATE CROSSES THE NETWORK: seed a UNIQUE expectation ONLY on node A, then
    # match it on node B (which never received the seed directly). This is the
    # anti-"two independent in-memory servers" gate — the textbook false green.
    CROSS_TOKEN="cross-$RUN_ID"
    CROSS_CODE=0; NEG_CODE=0
    if [ -n "$A_HP" ] && [ -n "$B_HP" ]; then
      curl -s --max-time 5 -X PUT "http://$A_HP/mockserver/expectation" -H 'Content-Type: application/json' \
        -d "[{\"httpRequest\":{\"path\":\"/$CROSS_TOKEN\"},\"httpResponse\":{\"statusCode\":222,\"body\":\"$CROSS_TOKEN\"},\"times\":{\"unlimited\":true}}]" \
        -o /dev/null 2>/dev/null || true
      # Give REPL_SYNC a beat, then probe node B for the A-only expectation. Body is
      # written under OUT_DIR (per the repo tmp-file convention), not /tmp.
      CROSS_BODY="$OUT_DIR/clu-cross-probe.$$"
      for _ in $(seq 1 10); do
        CROSS_CODE="$(curl -s --max-time 5 -o "$CROSS_BODY" -w '%{http_code}' "http://$B_HP/$CROSS_TOKEN" 2>/dev/null || echo 0)"
        [ "$CROSS_CODE" = 222 ] && grep -q "$CROSS_TOKEN" "$CROSS_BODY" 2>/dev/null && break
        sleep 1
      done
      # Negative control: a path seeded NOWHERE must NOT match on B (proves the 222
      # above is the replicated expectation, not a catch-all).
      NEG_CODE="$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' "http://$B_HP/never-seeded-$RUN_ID" 2>/dev/null || echo 0)"
      rm -f "$CROSS_BODY" 2>/dev/null || true
    fi
    STATE_CROSSED=false
    [ "$CROSS_CODE" = 222 ] && STATE_CROSSED=true
    echo "--- clustered A/B: state-crossed(A->B)=$STATE_CROSSED (probe HTTP $CROSS_CODE, negative-control HTTP $NEG_CODE)"

    run_clu_regression "clustered" "http://clu-a:1080" "clu-clustered.json" \
      || echo "WARNING: clustered CANDIDATE regression run failed" >&2
    run_clu_crossing "clustered" "http://clu-a:1080" "clu-cross-clustered.json" \
      || echo "WARNING: clustered CANDIDATE crossing run failed" >&2
    docker rm -f "$CLU_A" "$CLU_B" >/dev/null 2>&1 || true

    # Load both behaviour blocks (guarded to {} on any parse failure), then MERGE in
    # the crossing arm (op `crossing`) so the ratio machinery treats it identically —
    # it becomes clustered_state.arms.crossing, the per-request NETWORK-cost headline,
    # alongside the non-crossing membership arms.
    CLU_CTRL_BEHAVIOURS="$(jq -sc '(.[0].behaviours // {}) + (.[1].behaviours // {})' \
      "$OUT_DIR/clu-control.json" "$OUT_DIR/clu-cross-control.json" 2>/dev/null || echo '{}')"
    jq -e . >/dev/null 2>&1 <<<"$CLU_CTRL_BEHAVIOURS" || CLU_CTRL_BEHAVIOURS='{}'
    CLU_CAND_BEHAVIOURS="$(jq -sc '(.[0].behaviours // {}) + (.[1].behaviours // {})' \
      "$OUT_DIR/clu-clustered.json" "$OUT_DIR/clu-cross-clustered.json" 2>/dev/null || echo '{}')"
    jq -e . >/dev/null 2>&1 <<<"$CLU_CAND_BEHAVIOURS" || CLU_CAND_BEHAVIOURS='{}'

    # Assemble .clustered_state: the per-arm ratio (the metric), plus the cluster
    # genuineness proof. Ratios pair on the arm op (behaviour key minus its _<proto>
    # suffix). p50 is the primary ratio (robust at low N); p95 only where BOTH arms
    # cleared MIN_TAIL_SAMPLES (non-null p95). throughput_ratio is dir:down.
    CLUSTERED_JSON="$(jq -nc \
      --argjson ctrl "$CLU_CTRL_BEHAVIOURS" --argjson cand "$CLU_CAND_BEHAVIOURS" \
      --argjson a_members "${A_MEMBERS:-0}" --argjson b_members "${B_MEMBERS:-0}" \
      --argjson ctrl_members "${CTRL_MEMBERS:-0}" \
      --arg a_clustered "${A_CLUSTERED:-false}" --arg ctrl_clustered "${CTRL_CLUSTERED:-false}" \
      --arg state_crossed "$STATE_CROSSED" \
      --argjson cross_code "${CROSS_CODE:-0}" --argjson neg_code "${NEG_CODE:-0}" \
      --arg image "$CLU_IMAGE" '
      # strip a trailing _<proto> to recover the arm op
      def op(k; p): (k | sub("_"+p+"$"; ""));
      ($ctrl | to_entries | map({key: op(.key; "clustered_control"), value: .value}) | from_entries) as $C |
      ($cand | to_entries | map({key: op(.key; "clustered"),         value: .value}) | from_entries) as $K |
      {
        attempted: true,
        control_backend: "memory",
        candidate_backend: "infinispan",
        image: $image,
        cluster: {
          member_count_node_a: $a_members,
          member_count_node_b: $b_members,
          control_member_count: $ctrl_members,
          candidate_clustered: ($a_clustered == "true"),
          control_clustered: ($ctrl_clustered == "true"),
          state_crossed_network: ($state_crossed == "true"),
          cross_node_probe_http_code: $cross_code,
          negative_control_http_code: $neg_code
        },
        arms: (
          [ $K | keys[] | select($C[.] != null) | . as $op |
            { key: $op, value: (
              ($C[$op]) as $c | ($K[$op]) as $k |
              {
                p50_control_ms: $c.p50_ms, p50_clustered_ms: $k.p50_ms,
                p50_ratio: (if ($c.p50_ms // 0) > 0 and $k.p50_ms != null
                            then (($k.p50_ms / $c.p50_ms) * 1000 | round) / 1000 else null end),
                p95_control_ms: $c.p95_ms, p95_clustered_ms: $k.p95_ms,
                p95_ratio: (if ($c.p95_ms // null) != null and ($c.p95_ms // 0) > 0 and ($k.p95_ms // null) != null
                            then (($k.p95_ms / $c.p95_ms) * 1000 | round) / 1000 else null end),
                throughput_control_rps: $c.throughput_rps, throughput_clustered_rps: $k.throughput_rps,
                throughput_ratio: (if ($c.throughput_rps // 0) > 0 and $k.throughput_rps != null
                            then (($k.throughput_rps / $c.throughput_rps) * 1000 | round) / 1000 else null end),
                control_error_rate: $c.error_rate, clustered_error_rate: $k.error_rate,
                sample_count_control: $c.sample_count, sample_count_clustered: $k.sample_count,
                # true ONLY for the bounded-Times `crossing` arm (a per-request REPL_SYNC
                # round trip); the regression arms are node-local membership overhead.
                # So a reader never mistakes a membership ratio for total clustering cost.
                crosses_network: ($k.crosses_network // false)
              }) } ] | from_entries
        )
      }')"
    jq -e . >/dev/null 2>&1 <<<"$CLUSTERED_JSON" || CLUSTERED_JSON='{}'
    echo "--- clustered A/B result (crossing = per-request NETWORK cost; the rest = membership-only):"
    jq -c '{cluster,
            crossing_arm: (.arms | to_entries | map(select(.value.crosses_network)) | from_entries | with_entries(.value |= {p50_ratio, p95_ratio, throughput_ratio})),
            membership_arms: (.arms | to_entries | map(select(.value.crosses_network|not)) | from_entries | with_entries(.value |= {p50_ratio, p95_ratio, throughput_ratio}))}' \
      <<<"$CLUSTERED_JSON" 2>/dev/null || echo "$CLUSTERED_JSON"
  fi
fi

# --- derive resource slope ratios from the sample log -------------------------
# start = first non-empty sample, end = last, peak = max. ratio = end/start.
# PROVOCATION (observed false): with the metrics port unreachable the sampler
# writes only its header row, wc -l is 1, and this check evaluates false — which
# is exactly the empty-sample-log case the plan wants promoted from a warning to a
# baseline refusal (a run with no resource trajectory cannot assess growth).
if [ "$(wc -l < "$SAMPLE_LOG" 2>/dev/null || echo 0)" -le 1 ]; then
  echo "WARNING: resource sample log is empty — growth resource metrics will be 0/null for this run" >&2
  add_check "resource_samples_present" false "resource sample log empty — CPU/heap growth metrics are 0/null (cannot assess growth)"
else
  add_check "resource_samples_present" true "$(( $(wc -l < "$SAMPLE_LOG") - 1 )) resource samples captured during growth"
fi
# HEAP_MIN_FIRST / HEAP_MIN_LAST: minimum heap over the first / last LIVESET_WINDOW_S
# seconds of the sample log — the saw-tooth FLOOR that approximates the live set.
LIVESET_WINDOW_S="${PERF_LIVESET_WINDOW_S:-60}"
read -r CPU_START CPU_END CPU_PEAK HEAP_START HEAP_END HEAP_PEAK GC_DELTA THREADS_PEAK HEAP_MIN_FIRST HEAP_MIN_LAST <<EOF
$(awk -F',' -v W="$LIVESET_WINDOW_S" 'NR>1 && $2!="" {
    if (cs=="") {cs=$2} ce=$2; if ($2+0>cp) cp=$2;
  }
  NR>1 && $3!="" {
    if (hs=="") {hs=$3} he=$3; if ($3+0>hp) hp=$3;
    n++; T[n]=$1+0; H[n]=$3+0;
  }
  NR>1 && $4!="" { if (gcs=="") gcs=$4; gce=$4 }
  NR>1 && $5!="" { if ($5+0>tp) tp=$5 }
  END {
    hminf=""; hminl="";
    if (n>0) {
      ft=T[1]; lt=T[n];
      for (i=1;i<=n;i++) {
        if (T[i] <= ft + W) { if (hminf=="" || H[i] < hminf) hminf=H[i] }
        if (T[i] >= lt - W) { if (hminl=="" || H[i] < hminl) hminl=H[i] }
      }
    }
    printf "%s %s %s %s %s %s %s %s %s %s", cs+0, ce+0, cp+0, hs+0, he+0, hp+0, (gce-gcs)+0, tp+0, hminf+0, hminl+0
  }' "$SAMPLE_LOG")
EOF
ratio() { awk -v a="$1" -v b="$2" 'BEGIN{ if (b+0>0) printf "%.4f", a/b; else print "null" }'; }
CPU_RATIO="$(ratio "$CPU_END" "$CPU_START")"
# Live-set heap ratio = min heap over the LAST window / min over the FIRST window.
# The post-GC saw-tooth floor tracks the live set far better than an instantaneous
# end/start point sample (which lands at a random point on the GC saw-tooth), with
# no forced GC and much less noise. REPLACES the old instantaneous heap ratio.
HEAP_RATIO="$(ratio "$HEAP_MIN_LAST" "$HEAP_MIN_FIRST")"

# --- item 18: req/s per core for the SERVING path -----------------------------
# Pin ONE SUT to C cores in {1,2,4,8,16} and drive the sweep ladder against it
# from a k6 on DISJOINT cores, recording peak_achieved_rps, healthy_ceiling_rps
# and rps_per_core per C (lib/perf-percore.sh does the work + the C=16 feasibility
# handling + the pinning proof). DEFAULT OFF (opt-in): unlike the streaming /
# clustered arms, this does NOT share the main SUT — it spins up and tears down one
# fresh pinned SUT per core-count and runs a full ladder against each, ~4-5 extra
# SUT lifecycles, so it is scheduled deliberately (the plan's Tier-3 classification)
# rather than added to every daily regression run. Enable with PERF_SERVING_PERCORE=true.
# Every serving_percore.* metric is NOTIFY-ONLY (perf-budgets.json). Records INTENT
# (`serving_percore_attempted`) separately from success so compare can tell "attempted
# and produced no points" (RED) apart from "profile disabled" (silent), the same
# two-axis split the laptop profile uses.
SERVING_PERCORE_JSON='{}'
SERVING_PERCORE_ATTEMPTED=false
if [ "${PERF_SERVING_PERCORE:-false}" = "true" ]; then
  SERVING_PERCORE_ATTEMPTED=true
  echo "--- item 18 serving per-core (opt-in; PERF_SERVING_PERCORE=true)"
  # NOT an add_check on failure by itself: the presence gate lives in compare
  # (serving_percore_attempted + a non-empty points/skipped set), matching laptop.
  if MOCKSERVER_IMAGE="$MOCKSERVER_IMAGE" PERF_PERCORE_REPO_ROOT="$REPO_ROOT" \
       bash "$SCRIPT_DIR/lib/perf-percore.sh" "$OUT_DIR/serving-percore.json"; then
    SERVING_PERCORE_JSON="$(cat "$OUT_DIR/serving-percore.json" 2>/dev/null || echo '{}')"
    jq -e . >/dev/null 2>&1 <<<"$SERVING_PERCORE_JSON" || SERVING_PERCORE_JSON='{}'
    echo "--- serving_percore: points=$(jq -r '(.points|length)//0' <<<"$SERVING_PERCORE_JSON") max_cores_measured=$(jq -r '.max_cores_measured//"?"' <<<"$SERVING_PERCORE_JSON") skipped=$(jq -r '(.skipped|length)//0' <<<"$SERVING_PERCORE_JSON")"
  else
    echo "WARNING: serving per-core profile failed — result carries no serving_percore points this run (notify-only)" >&2
  fi
fi

# --- assemble result JSON -----------------------------------------------------
COMMIT="${BUILDKITE_COMMIT:-$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}"
BRANCH="${BUILDKITE_BRANCH:-$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# curl -s exits 0 on an EMPTY body, so the old `curl -s ... || echo fallback`
# never fell back off-EC2 (or on IMDSv2) and every stored run carried
# instance_type:"" — which silently defeats the cross-hardware baseline guard.
# Use -f (fail on non-2xx) AND validate the body is non-empty before trusting it.
INSTANCE_TYPE="$(curl -sf --max-time 2 http://169.254.169.254/latest/meta-data/instance-type 2>/dev/null || true)"
[ -n "$INSTANCE_TYPE" ] || INSTANCE_TYPE="${PERF_INSTANCE_TYPE:-unknown}"
GROWTH_JSON="$(cat "$OUT_DIR/growth.json" 2>/dev/null || echo '{}')"
SWEEP_JSON="$(cat "$OUT_DIR/sweep.json" 2>/dev/null || echo '{}')"

# --- regression-presence validity check + assemble the validity block ---------
# PROVOCATION (observed false): if regression.js fails to seed/reach the SUT it
# writes an empty behaviours object (or no file), the jq -e finds zero non-null
# p95 entries and this check evaluates false — a run with no latency numbers must
# not be baselined as if it had them.
if jq -e '((.behaviours // {}) | to_entries | map(select(.value.p95_ms != null)) | length) > 0' \
     "$OUT_DIR/regression-http.json" >/dev/null 2>&1; then
  add_check "regression_metrics_present" true "regression behaviours present with non-null latency percentiles"
else
  add_check "regression_metrics_present" false "regression-http.json missing/empty behaviours — latency measurement failed"
fi
# resource_samples_present keys on ROW COUNT, but rows accrue even when only the
# CPU column is populated (docker stats up, /mockserver/metrics unreachable for
# the whole growth phase). Heap is pushed to H[] only when the heap column is
# non-empty, so in that case HEAP_MIN_FIRST/LAST collapse to 0 and
# growth.live_set_bytes would be a 0 that (as a dir:up metric) never exceeds its
# threshold — silently BASELINED, dragging the rolling median down for every
# future run. So gate on the live-set floor being a plausible non-zero value.
# PROVOCATION (observed false): make /mockserver/metrics unreachable for the whole
# growth phase — CPU rows still accrue but HEAP_MIN_LAST is 0 and this fails.
if awk -v v="$HEAP_MIN_LAST" 'BEGIN{exit !(v+0>0)}'; then
  add_check "growth_heap_sampled" true "live-set floor min_last_window=${HEAP_MIN_LAST} bytes (heap metrics captured)"
else
  add_check "growth_heap_sampled" false "no heap samples during growth (/mockserver/metrics unreachable?) — live-set floor is 0, growth.live_set_bytes would poison the baseline as a zero"
fi
# item 12 — streaming presence. Only asserted when the profile was ATTEMPTED
# (PERF_STREAMING on AND python3 present); a deliberately-skipped profile is not a
# validity failure. When attempted, the match A/B ratio must be present — a null
# ratio means the streaming run produced no comparable latency and the .streaming
# metrics would baseline as nulls/zeros.
if [ "${PERF_STREAMING:-true}" = "true" ] && command -v python3 >/dev/null 2>&1; then
  # $STREAMING_JSON is the FLAT block (its top-level keys are match_p95_ratio etc.
  # — it was built as `(.streaming // {}) as $s | $s + {…}`, already unwrapped), so
  # query match_p95_ratio at the TOP level, NOT `.streaming.match_p95_ratio`. The
  # sibling regression check reads `.behaviours` because it queries the k6 result
  # FILE (top level {proto, behaviours}); this reads the assembled block — same
  # idiom, different shape.
  if jq -e '(.match_p95_ratio != null)' <<<"$STREAMING_JSON" >/dev/null 2>&1; then
    add_check "streaming_metrics_present" true "streaming match A/B present (ratio=$(jq -r '.match_p95_ratio' <<<"$STREAMING_JSON" 2>/dev/null))"
  else
    add_check "streaming_metrics_present" false "streaming profile attempted but match_p95_ratio is null — streaming.js produced no comparable match latency this run"
  fi
fi
# item 13 — clustered A/B presence + GENUINENESS. Only asserted when ATTEMPTED
# (PERF_CLUSTERED on AND the clustered image present) — a disabled/absent-image run
# is a green skip, not a failure. When attempted, ALL of: the candidate cluster
# formed a >=2 view, state actually crossed the network (an A-only expectation
# matched on B), the control was genuinely single-node (view 1), and at least one
# arm yielded a p50 ratio. This is the anti-false-green gate: two independent
# in-memory servers (view 1/1) or a non-crossing state would otherwise pass with an
# empty/degenerate block. Proven both directions by PERF_CLU_BREAK=cluster.
if [ "$CLUSTERED_ATTEMPTED" = "true" ]; then
  if jq -e '
      (.cluster.member_count_node_a >= 2)
      and (.cluster.state_crossed_network == true)
      and (.cluster.control_member_count == 1)
      and ((.arms // {}) | to_entries | map(select(.value.p50_ratio != null)) | length > 0)
    ' <<<"$CLUSTERED_JSON" >/dev/null 2>&1; then
    add_check "clustered_metrics_present" true \
      "clustered A/B genuine: view=$(jq -r '.cluster.member_count_node_a' <<<"$CLUSTERED_JSON") state_crossed=$(jq -r '.cluster.state_crossed_network' <<<"$CLUSTERED_JSON") arms=$(jq -r '(.arms // {}) | length' <<<"$CLUSTERED_JSON")"
  else
    add_check "clustered_metrics_present" false \
      "clustered profile attempted but not genuinely clustered/measured: view_a=$(jq -r '.cluster.member_count_node_a // "?"' <<<"$CLUSTERED_JSON") state_crossed=$(jq -r '.cluster.state_crossed_network // "?"' <<<"$CLUSTERED_JSON") control_members=$(jq -r '.cluster.control_member_count // "?"' <<<"$CLUSTERED_JSON") arms_with_ratio=$(jq -r '(.arms // {}) | to_entries | map(select(.value.p50_ratio != null)) | length' <<<"$CLUSTERED_JSON" 2>/dev/null) — would be a false green (two independent servers or no ratio)"
  fi
fi
if [ "${#VALIDITY_CHECKS[@]}" -gt 0 ]; then
  VALIDITY_JSON="$(printf '%s\n' "${VALIDITY_CHECKS[@]}" | jq -sc '{valid: (map(.ok) | all), checks: .}')"
else
  VALIDITY_JSON='{"valid":false,"checks":[]}'
fi

jq -n \
  --arg commit "$COMMIT" --arg branch "$BRANCH" --arg ts "$TS" \
  --arg build_number "${BUILDKITE_BUILD_NUMBER:-}" --arg build_url "${BUILDKITE_BUILD_URL:-}" \
  --arg instance_type "$INSTANCE_TYPE" --arg image "$MOCKSERVER_IMAGE" \
  --arg server_cpus "${SERVER_CPUS:-none}" --arg k6_cpus "${K6_CPUS:-none}" \
  --slurpfile http "$OUT_DIR/regression-http.json" \
  --slurpfile https "$OUT_DIR/regression-https.json" \
  --argjson growth "$GROWTH_JSON" \
  --argjson sweep "$SWEEP_JSON" \
  --argjson saturation "$SATURATION_JSON" \
  --arg saturation_rps "$SATURATION_RPS" \
  --arg peak_achieved_rps "$PEAK_ACHIEVED_RPS" \
  --argjson forward "$FORWARD_JSON" \
  --arg forward_exit "$FORWARD_EXIT" \
  --argjson proxyfwd "$PROXY_FWD_JSON" \
  --argjson handshake "$HANDSHAKE_JSON" \
  --argjson streaming "$STREAMING_JSON" \
  --argjson clustered_state "$CLUSTERED_JSON" \
  --argjson clustered_attempted "$CLUSTERED_ATTEMPTED" \
  --argjson clu_ctrl "$CLU_CTRL_BEHAVIOURS" \
  --argjson clu_cand "$CLU_CAND_BEHAVIOURS" \
  --argjson validity "$VALIDITY_JSON" \
  --argjson config "$CONFIG_JSON" \
  --argjson laptop "$LAPTOP_JSON" \
  --argjson laptop_attempted "$LAPTOP_ATTEMPTED" \
  --argjson serving_percore "$SERVING_PERCORE_JSON" \
  --argjson serving_percore_attempted "$SERVING_PERCORE_ATTEMPTED" \
  --argjson info_log_level_arm "$INFO_ARM_JSON" \
  --argjson info_log_level_arm_attempted "$INFO_ARM_ATTEMPTED" \
  --arg cpu_start "$CPU_START" --arg cpu_end "$CPU_END" --arg cpu_peak "$CPU_PEAK" --arg cpu_ratio "$CPU_RATIO" \
  --arg heap_start "$HEAP_START" --arg heap_end "$HEAP_END" --arg heap_peak "$HEAP_PEAK" --arg heap_ratio "$HEAP_RATIO" \
  --arg heap_min_first "$HEAP_MIN_FIRST" --arg heap_min_last "$HEAP_MIN_LAST" \
  --arg gc_delta "$GC_DELTA" --arg threads_peak "$THREADS_PEAK" \
  '{
    # schema_version 2: a `config` block now records what the run WAS (JVM/JDK/GC,
    # resolved heap, log level, image digest, k6 pin) so runs are only ever compared
    # when configured alike. A stored run with schema_version 1 has no config block
    # and predates this guarantee — perf-test-compare.sh annotates that boundary.
    schema_version: 2,
    commit: $commit, branch: $branch, timestamp_utc: $ts,
    build_number: $build_number, build_url: $build_url,
    agent: { instance_type: $instance_type, queue: "perf", server_cpus: $server_cpus, k6_cpus: $k6_cpus },
    config: $config,
    mockserver_image: $image,
    # regression (http + https_h2) + proxy.js FORWARD arms all live in .behaviours
    # (same shape), so the compare step behaviours.* budgets cover them with no jq
    # change. Adding the two forward_*_proxy arms grows the k6 arm-set fingerprint,
    # so compare resets the k6 baseline ONCE (intended — see item 9a). CONSEQUENCE
    # for whoever reads the first post-landing runs: because the fingerprint changed,
    # ALL behaviours.* arms (the existing match/forward/template/large arms included)
    # go :new: / no-baseline and are NOT flagged until MIN_BASELINE arm-set-matching
    # runs accrue — a one-off, expected gap in behaviour-arm coverage, surfaced by the
    # compare step "k6 behaviour baseline reset" note. (These arms are notify-only,
    # so no GATING coverage is lost; forward.error_rate and the JMH gates are
    # unaffected as they are not behaviours.* and not fingerprint-filtered.)
    # item 13 — the clustered A/B raw per-arm latencies land here too (distinct
    # <op>_clustered / <op>_clustered_control keys), so the existing behaviours.*
    # budgets cover them with no new key. Adding these arms GROWS the k6 arm-set
    # fingerprint, so compare resets the k6 behaviour baseline ONCE — the same
    # self-healing, notify-only reset the item 9a forward-proxy arms already trigger
    # (no GATING coverage lost). The headline metric — the RATIO — is in
    # .clustered_state (below), which rides the FULL baseline, not this fingerprint.
    # When the clustered profile is disabled/skipped, both objects are {}, so no arms
    # are added and the fingerprint is unchanged.
    behaviours: (($http[0].behaviours // {}) + ($https[0].behaviours // {}) + ($proxyfwd.behaviours // {}) + $clu_ctrl + $clu_cand),
    # item 14 — TLS/mTLS/native-absent handshake cost (proxy.js handshake mode +
    # per-SUT CPU/alloc augmentation above). NOT part of .behaviours, so it does not
    # touch the k6 fingerprint; compare reads it as its own non-gating metric family.
    tls_handshake: ($handshake.tls_handshake // {}),
    # item 12 — LLM/SSE streaming under concurrency: the match-p95 A/B, the
    # server-side inter-token delay-error distribution (idle vs load) and heap per
    # open stream. NOT part of .behaviours, so it does not touch the k6 fingerprint;
    # compare reads a CURATED subset as its own non-gating metric family.
    streaming: $streaming,
    # item 13 — clustered state under load: the per-arm ratio (clustered / in-memory
    # control) measured in this run, plus the cluster-genuineness proof (2-node view,
    # state-crossed-the-network, control-is-single-node). NOT part of .behaviours, so
    # it does not touch the k6 fingerprint; compare reads .clustered_state.arms.* as
    # its own non-gating family on the FULL baseline (the within-run ratio already
    # cancels environment, so it must NOT be keyed on the arm set or image digest).
    # {} when the profile was disabled or the clustered image was absent.
    clustered_state: $clustered_state,
    clustered_attempted: $clustered_attempted,
    growth: {
      duration_s: ($growth.duration_s // null),
      p95_ms: ($growth.p95_ms // null),
      cpu_pct: { start: ($cpu_start|tonumber), end: ($cpu_end|tonumber), peak: ($cpu_peak|tonumber), ratio: (try ($cpu_ratio|tonumber) catch null) },
      heap_used_bytes: {
        start: ($heap_start|tonumber), end: ($heap_end|tonumber), peak: ($heap_peak|tonumber),
        # ratio is now the LIVE-SET floor ratio (min last-window / min first-window),
        # not end/start; min_first/last_window are the saw-tooth floors it divides.
        ratio: (try ($heap_ratio|tonumber) catch null),
        min_first_window: ($heap_min_first|tonumber), min_last_window: ($heap_min_last|tonumber)
      },
      gc_seconds_delta: ($gc_delta|tonumber),
      threads_peak: ($threads_peak|tonumber)
    },
    sweep: $sweep,
    peak_achieved_rps: (try ($peak_achieved_rps|tonumber) catch null),
    saturation_rps: (try ($saturation_rps|tonumber) catch null),
    saturation: $saturation,
    # forward_guard.status distinguishes an INFRA failure (k6 exited non-zero but
    # produced no error_rate — upstream/container error, the guard did not actually
    # run) from a real BREACH (non-zero exit WITH a high error_rate — the pool
    # regression it exists to catch). Same exit code, opposite meaning; compare
    # surfaces the infra case as a loud "guard did not run" annotation so a
    # silently-absent guard cannot pass unnoticed.
    forward_guard: (($forward.forward_guard // {}) + {
      k6_exit: ($forward_exit|tonumber),
      status: (
        if ($forward_exit|tonumber) == 0 then "passed"
        elif ($forward.forward_guard.error_rate) == null then "infra_error"
        else "breached" end) }),
    validity: $validity,
    # Item 8 laptop startup/footprint profile (notify-only). `{}` when the profile
    # was disabled or its measurement failed; compare iterates head metrics, so an
    # empty object simply emits zero laptop.* metrics (no missing-budget trip). The
    # separate `laptop_attempted` flag lets compare RED a wholesale failure (attempted
    # but empty/docker-incomplete) instead of mistaking it for a disabled profile.
    laptop: ($laptop.laptop // {}),
    laptop_attempted: $laptop_attempted,
    # item 18 — req/s per core for the SERVING path. `{}` when the profile was
    # disabled or its measurement failed; compare iterates .serving_percore.points,
    # so an empty object emits zero serving_percore.* metrics (no missing-budget
    # trip). serving_percore_attempted lets compare RED a wholesale failure
    # (attempted but no points) apart from a disabled profile — the laptop split.
    serving_percore: $serving_percore,
    serving_percore_attempted: $serving_percore_attempted,
    # Plan open question 5 — the INFO-log-level PUBLICATION arm. The two PUBLISHED
    # figure families (knee curve + per-behaviour percentiles) re-measured against a
    # SUT at the shipped-default log level, so the site can show an honest
    # out-of-the-box number ALONGSIDE the (legitimate, labelled) ERROR baseline. Lives
    # here under a DISTINCT key — NOT in .behaviours / .sweep / peak_achieved_rps — so
    # an INFO number can never be confused with or diffed against the ERROR series, and
    # carries its own .config.log_level so it is self-describing in the DATA. `{}` when
    # the arm was disabled (PERF_INFO_ARM=false); the sibling *_attempted flag
    # distinguishes disabled from attempted-but-failed (.info_log_level_arm.measured ==
    # false). NON-GATING and EXCLUDED from validity — it never blocks the ERROR
    # baseline. compare.sh does NOT yet read these keys (publication is sequenced after
    # a run emits both series); the info_* budget entries are staged notify-only /
    # provisional for when it does.
    info_log_level_arm: $info_log_level_arm,
    info_log_level_arm_attempted: $info_log_level_arm_attempted
  }' > "$RESULT_JSON"

echo "--- result.json"
cat "$RESULT_JSON"

# Standalone sweep artifact (also embedded under .sweep in result.json above).
cp "$OUT_DIR/sweep.json" "$REPO_ROOT/perf-sweep.json" 2>/dev/null || echo '{}' > "$REPO_ROOT/perf-sweep.json"
# Standalone serving per-core artifact (also embedded under .serving_percore).
# Emitted only when the profile ran; the website's serving per-core chart (item 19)
# and a dated trend read this file, mirroring perf-sweep.json / inject-percore.json.
if [ -f "$OUT_DIR/serving-percore.json" ]; then
  cp "$OUT_DIR/serving-percore.json" "$REPO_ROOT/serving-percore.json" 2>/dev/null || true
fi

if command -v buildkite-agent >/dev/null 2>&1; then
  cp "$RESULT_JSON" "$REPO_ROOT/perf-result.json"
  buildkite-agent artifact upload "perf-result.json" || true
  buildkite-agent artifact upload "perf-sweep.json" || true
  [ -f "$REPO_ROOT/serving-percore.json" ] && buildkite-agent artifact upload "serving-percore.json" || true
  # Record the commit this run actually executed against. perf-test-guard.sh
  # reads this (via last_perf_run_commit) to decide "new commit since last run"
  # — keyed off real runs, NOT the lint build that passes on every push.
  buildkite-agent meta-data set "perf_regression_ran_commit" "$COMMIT" || true
else
  cp "$RESULT_JSON" "$REPO_ROOT/perf-result.json"
  echo "(local run) result + sweep copied to $REPO_ROOT/perf-result.json, $REPO_ROOT/perf-sweep.json"
fi
