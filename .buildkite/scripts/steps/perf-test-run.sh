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
SAMPLE_INTERVAL="${PERF_SAMPLE_INTERVAL:-5}"
# Hard memory bound for the SUT (item: measure growth against a realistic heap).
# Unbounded on a 32 GB box, MaxRAMPercentage=75 yields a ~24 GB heap that barely
# GCs, so a slow leak is invisible and the "live set" is unobservable. A bounded
# heap that actually cycles is what the documented central-deployment guidance
# runs, and is what makes the saw-tooth floor (see the live-set ratio below) mean
# something. Applied to the SUT only, never the upstream. Overridable for a re-run.
SERVER_MEMORY="${PERF_SERVER_MEMORY:-2g}"

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
cleanup() {
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" >/dev/null 2>&1 || true
  [ -n "$SWEEP_SAMPLER_PID" ] && kill "$SWEEP_SAMPLER_PID" >/dev/null 2>&1 || true
  [ -n "${HS_CPU_PID:-}" ] && kill "$HS_CPU_PID" >/dev/null 2>&1 || true
  # The item 14 handshake SUTs (deterministic names from RUN_ID) — removed here too
  # so an early exit before the proxy block's own cleanup never leaks them.
  docker rm -f "$SERVER" "$UPSTREAM" "$SWEEP_K6" \
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
  local name="$1" cpus="$2" alias="$3" publish="${4:-}" mem="${5:-}" mount="${6:-}"
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
    -e MOCKSERVER_LOG_LEVEL=ERROR \
    -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
    -e MOCKSERVER_METRICS_ENABLED=true \
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
echo "--- SUT started with --memory=$SERVER_MEMORY (bounded heap so GC cycles)"
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
container_env() { # VAR_NAME
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$SERVER" 2>/dev/null \
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
      java_tool_options:"observed", perf_server_java_opts:"declared",
      k6_image_digest:"observed", cpusets:"declared", k6_cpu_pin_pct:"declared"
    }
  }')"
echo "--- config resolved: ${MS_VERSION} gc='${GC_IN_USE}' heap_max=${HEAP_MAX_BYTES} jdk='${JDK_BUILD}' log_level=${LOG_LEVEL_VAL} (schema_version=2)"

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

# Background sampler of the k6 CLIENT container's CPU while the sweep runs — the
# missing "was the client the bottleneck?" evidence. A rung where k6 is near its
# own CPU pin is a CLIENT ceiling, not MockServer's, and is excluded below.
sweep_k6_sampler() {
  echo "ts,cpu_pct" > "$SWEEP_CPU_LOG"
  while true; do
    local ts cpu
    ts="$(date -u +%s)"
    cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$SWEEP_K6" 2>/dev/null | tr -d '% ' || echo '')"
    [ -n "$cpu" ] && printf '%s,%s\n' "$ts" "$cpu" >> "$SWEEP_CPU_LOG"
    sleep "${PERF_SWEEP_SAMPLE_INTERVAL:-3}"
  done
}

echo "--- sweep.js (throughput-vs-latency knee curve; ladder=$SWEEP_RATES)"
SWEEP_T0="$(date -u +%s)"
sweep_k6_sampler & SWEEP_SAMPLER_PID=$!
# shellcheck disable=SC2046
docker run --rm --name "$SWEEP_K6" --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
  -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
  -v "$OUT_DIR:/out" \
  -e "BASE_URL=http://${SERVER_ALIAS}:1080" \
  -e "PROTO=http" \
  -e "K6_SWEEP_RATES=$SWEEP_RATES" \
  -e "K6_SWEEP_STEP=$SWEEP_STEP" \
  -e "K6_SWEEP_GAP=$SWEEP_GAP" \
  -e "K6_SWEEP_RESULT_PATH=/out/sweep.json" \
  ${K6_SWEEP_PRE_VUS:+-e K6_SWEEP_PRE_VUS="$K6_SWEEP_PRE_VUS"} \
  ${K6_SWEEP_MAX_VUS:+-e K6_SWEEP_MAX_VUS="$K6_SWEEP_MAX_VUS"} \
  "$K6_IMAGE" run /k6/sweep.js
kill "$SWEEP_SAMPLER_PID" >/dev/null 2>&1 || true; SWEEP_SAMPLER_PID=""

# --- derive saturation_rps from the sweep (item: prove the client had headroom) -
# Per rung: attribute the max k6-container CPU seen during that rung's hold window
# (from the sampler log + the known ladder schedule), pair it with k6's per-rung
# dropped_iterations, and mark the rung CLEAN iff achieved >= 0.95*offered AND the
# client had CPU headroom (< 85% of its pin) AND k6 dropped no iterations. The
# highest CLEAN rung's offered rate is saturation_rps. If nothing is clean, the
# client was the bottleneck everywhere and the run is flagged invalid below.
STEP_S="$(to_secs "$SWEEP_STEP")"
GAP_S="$(to_secs "$SWEEP_GAP")"
SETTLE_S="${PERF_SWEEP_SETTLE_S:-3}"
K6_CORES="$(k6_core_count "$K6_CPUS")"
K6_PIN_PCT=$((K6_CORES * 100))

# Max k6 CPU% per rung, keyed by offered rate, from the CPU sample log + schedule.
CPU_MAP="{}"
IFS=',' read -ra RATE_ARR <<< "$SWEEP_RATES"
for i in "${!RATE_ARR[@]}"; do
  r="${RATE_ARR[$i]}"
  ws=$(( SWEEP_T0 + i * (STEP_S + GAP_S) + SETTLE_S ))
  we=$(( SWEEP_T0 + i * (STEP_S + GAP_S) + STEP_S ))
  maxcpu="$(awk -F',' -v a="$ws" -v b="$we" 'NR>1 && $1>=a && $1<=b { if($2+0>m) m=$2+0 } END{ printf "%.1f", m+0 }' "$SWEEP_CPU_LOG" 2>/dev/null || echo 0)"
  CPU_MAP="$(jq -c --arg k "$r" --argjson v "${maxcpu:-0}" '. + {($k): $v}' <<<"$CPU_MAP")"
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
SWEEP_ERR_EPS="${PERF_SWEEP_ERROR_EPS:-0.01}"
SATURATION_JSON="$(jq -n \
  --slurpfile sweep "$OUT_DIR/sweep.json" \
  --argjson cpu "$CPU_MAP" \
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
                 | {offered_rps, achieved_rps, k6_cpu_pct, dropped_iterations, error_rate, reason:.exclude_reason} ] }')"
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
  --argjson validity "$VALIDITY_JSON" \
  --argjson config "$CONFIG_JSON" \
  --argjson laptop "$LAPTOP_JSON" \
  --argjson laptop_attempted "$LAPTOP_ATTEMPTED" \
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
    behaviours: (($http[0].behaviours // {}) + ($https[0].behaviours // {}) + ($proxyfwd.behaviours // {})),
    # item 14 — TLS/mTLS/native-absent handshake cost (proxy.js handshake mode +
    # per-SUT CPU/alloc augmentation above). NOT part of .behaviours, so it does not
    # touch the k6 fingerprint; compare reads it as its own non-gating metric family.
    tls_handshake: ($handshake.tls_handshake // {}),
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
    laptop_attempted: $laptop_attempted
  }' > "$RESULT_JSON"

echo "--- result.json"
cat "$RESULT_JSON"

# Standalone sweep artifact (also embedded under .sweep in result.json above).
cp "$OUT_DIR/sweep.json" "$REPO_ROOT/perf-sweep.json" 2>/dev/null || echo '{}' > "$REPO_ROOT/perf-sweep.json"

if command -v buildkite-agent >/dev/null 2>&1; then
  cp "$RESULT_JSON" "$REPO_ROOT/perf-result.json"
  buildkite-agent artifact upload "perf-result.json" || true
  buildkite-agent artifact upload "perf-sweep.json" || true
  # Record the commit this run actually executed against. perf-test-guard.sh
  # reads this (via last_perf_run_commit) to decide "new commit since last run"
  # — keyed off real runs, NOT the lint build that passes on every push.
  buildkite-agent meta-data set "perf_regression_ran_commit" "$COMMIT" || true
else
  cp "$RESULT_JSON" "$REPO_ROOT/perf-result.json"
  echo "(local run) result + sweep copied to $REPO_ROOT/perf-result.json, $REPO_ROOT/perf-sweep.json"
fi
