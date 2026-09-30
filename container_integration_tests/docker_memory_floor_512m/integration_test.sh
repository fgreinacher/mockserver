#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/../docker-compose.sh"
source "${SCRIPT_DIR}/../logging.sh"
source "${SCRIPT_DIR}/../../.buildkite/scripts/steps/lib/perf-cpu-topology.sh"

# Regression guard for the documented memory floor: the image AS SHIPPED (its own HEALTHCHECK, no
# -Xmx, default JAVA_TOOL_OPTIONS) must survive sustained load in a 512 MiB, one-core container.
# It fails when the container is OOM-killed, when unreclaimable memory (anon + shmem + kernel +
# sock) peaks above 90% of the limit, when the JVM's file-backed pages (code, CDS archive) are evicted
# below 70% of idle or 17 MiB (reclaim thrash), when the HEALTHCHECK goes unhealthy, when over 1% of
# requests fail, or when the rig did not deliver the load (a run that stressed nothing must not pass).

TEST_CASE="${TEST_CASE:-docker_memory_floor_512m}"
IMAGE="${MEMORY_FLOOR_IMAGE:-mockserver/mockserver:integration_testing}"
LIMIT_MIB="${MEMORY_FLOOR_LIMIT_MIB:-512}"
RATE="${MEMORY_FLOOR_RATE:-16000}"
VUS="${MEMORY_FLOOR_VUS:-4500}"
RAMP_S="${MEMORY_FLOOR_RAMP_S:-15}"
HOLD_S="${MEMORY_FLOOR_HOLD_S:-90}"
MAX_PEAK_PCT=90
MIN_RSSFILE_PCT=70
MIN_RSSFILE_MIB="${MEMORY_FLOOR_MIN_RSSFILE_MIB:-17}"
MAX_FAILED_PCT=1
MIN_CONNECTIONS=3000
MIN_REQUESTS="${MEMORY_FLOOR_MIN_REQUESTS:-400000}"
K6_IMAGE="grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f"
PROBE_IMAGE="alpine:3.24@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6"

# Standalone runs (outside integration_tests.sh) have no result logs to append to.
export PASS_LOG_FILE="${PASS_LOG_FILE:-/dev/null}" FAIL_LOG_FILE="${FAIL_LOG_FILE:-/dev/null}" WARN_LOG_FILE="${WARN_LOG_FILE:-/dev/null}"

RUN_ID="memfloor-$$"
SUT="${RUN_ID}-sut"
PROBE="${RUN_ID}-probe"
NET="${RUN_ID}-net"
WORK_DIR="$(mktemp -d)"
HEALTH_MONITOR_PID=""

printMessage "Start: \"${SCRIPT_DIR/\//}\" image=${IMAGE} limit=${LIMIT_MIB}MiB rate=${RATE}/s vus=${VUS} ramp=${RAMP_S}s hold=${HOLD_S}s"

# shellcheck disable=SC2329 # invoked via trap
function cleanup() {
  [[ -n "${HEALTH_MONITOR_PID}" ]] && kill "${HEALTH_MONITOR_PID}" 2>/dev/null || true
  docker rm -f "${SUT}" "${PROBE}" >/dev/null 2>&1 || true
  docker network rm "${NET}" >/dev/null 2>&1 || true
  rm -rf "${WORK_DIR}"
}

function finish() {
  local exit_code="$1" reason="${2:-}"
  if [[ "${exit_code}" != "0" ]]; then
    printFailureMessage "${TEST_CASE}: ${reason}"
    docker logs --tail 40 "${SUT}" 2>&1 || true
  fi
  if [[ "${MEMORY_FLOOR_BLOCKING:-false}" == "true" ]]; then
    logTestResult "${exit_code}" "${TEST_CASE}"
  else
    logTestResultNonBlocking "${exit_code}" "${TEST_CASE}"
  fi
  exit "${exit_code}"
}

# The server gets one logical CPU and its whole physical core: its hyperthread sibling stays idle and
# k6 + the sampler share the remaining cores. Without readable sysfs (a macOS Docker Desktop host,
# whose VM exposes no SMT) each logical CPU is treated as its own core.
function choose_cpusets() {
  local ncpu="$1" sut_key cpu load=""
  SUT_CPUS="${MEMORY_FLOOR_SUT_CPU:-0}"
  sut_key="$(phys_core_key "${SUT_CPUS}" || echo "cpu${SUT_CPUS}")"
  for ((cpu = 0; cpu < ncpu; cpu++)); do
    [[ "$(phys_core_key "${cpu}" || echo "cpu${cpu}")" == "${sut_key}" ]] && continue
    load="${load:+${load},}${cpu}"
  done
  LOAD_CPUS="${MEMORY_FLOOR_K6_CPUS:-${load}}"
}

function integration_test() {
  trap cleanup EXIT

  local ncpu
  ncpu="$(docker info --format '{{.NCPU}}')"
  if [[ "${ncpu}" -lt 4 ]]; then
    finish 1 "needs a Docker host with at least 4 CPUs (one physical core for the server, the rest for k6 and the sampler); found ${ncpu}"
  fi
  choose_cpusets "${ncpu}"
  cpusets_physically_disjoint server "${SUT_CPUS}" load "${LOAD_CPUS}" \
    || finish 1 "k6/sampler cpuset ${LOAD_CPUS} shares a physical core with the server (${SUT_CPUS})"

  docker network create "${NET}" >/dev/null
  docker run -d --name "${SUT}" --network "${NET}" --network-alias mockserver \
    --cpuset-cpus="${SUT_CPUS}" --memory="${LIMIT_MIB}m" --memory-swap="${LIMIT_MIB}m" -p 0:1080 \
    -e MOCKSERVER_LOG_LEVEL=ERROR -e MOCKSERVER_DISABLE_SYSTEM_OUT=true -e MOCKSERVER_METRICS_ENABLED=true \
    ${MEMORY_FLOOR_SUT_ENV:+${MEMORY_FLOOR_SUT_ENV}} "${IMAGE}" >/dev/null || finish 1 "could not start ${IMAGE}"

  local host_port status="000"
  host_port="$(docker port "${SUT}" 1080/tcp | head -1 | awk -F: '{print $NF}')"
  for _ in $(seq 1 60); do
    status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -X PUT "http://localhost:${host_port}/mockserver/status" || true)"
    [[ "${status}" == "200" ]] && break
    sleep 1
  done
  [[ "${status}" == "200" ]] || finish 1 "server never became ready (last status ${status})"
  curl -sf -o /dev/null -X PUT "http://localhost:${host_port}/mockserver/expectation" -H 'Content-Type: application/json' \
    -d '[{"httpRequest":{"path":"/simple"},"httpResponse":{"statusCode":200,"body":"simple"},"times":{"unlimited":true}}]' \
    || finish 1 "could not create the expectation"

  # The image's own HEALTHCHECK must report healthy before load starts (first probe runs after --interval).
  local health="starting"
  for _ in $(seq 1 45); do
    health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${SUT}")"
    [[ "${health}" == "healthy" || "${health}" == "none" ]] && break
    sleep 1
  done
  [[ "${health}" == "healthy" ]] || finish 1 "image HEALTHCHECK did not report healthy before load (status ${health})"

  # The probe shares the server's PID namespace (/proc/1 is the JVM) and the host cgroup namespace,
  # so it can read the container's cgroup v2 memory accounting every 200 ms.
  docker run -d --name "${PROBE}" --privileged --pid="container:${SUT}" --cgroupns=host --cpuset-cpus="${LOAD_CPUS}" \
    --entrypoint sleep "${PROBE_IMAGE}" 100000 >/dev/null
  local cgroup_dir
  cgroup_dir="/sys/fs/cgroup$(docker exec "${PROBE}" awk -F: '$1=="0"{print $3}' /proc/1/cgroup)"
  docker exec "${PROBE}" test -r "${cgroup_dir}/memory.stat" \
    || finish 1 "cgroup v2 memory accounting not readable at ${cgroup_dir} — cannot measure, failing closed"
  # shellcheck disable=SC2016
  docker exec -d "${PROBE}" sh -c '
    while :; do
      mem=$(awk '\''$1=="anon"{a=$2} $1=="shmem"{s=$2} $1=="sock"{n=$2} $1=="kernel"{k=$2; hk=1}
                  $1~/^(kernel_stack|pagetables|percpu|slab|vmalloc)$/{ks+=$2}
                  END{if(!hk)k=ks; print a+0, s+0, k+n+0}'\'' "$0/memory.stat")
      rssfile=$(awk '\''/^RssFile/{print $2}'\'' /proc/1/status)
      conns=$(awk '\''NR>1 && $4=="01" {split($2,p,":"); if (p[2]=="0438") n++} END{print n+0}'\'' /proc/1/net/tcp /proc/1/net/tcp6)
      echo "$(date +%s) ${mem} ${rssfile:-0} ${conns}" >> /tmp/samples
      sleep 0.2
    done' "${cgroup_dir}"

  (
    while sleep 2; do
      docker inspect -f '{{.State.Health.Status}} {{.State.Health.FailingStreak}}' "${SUT}" 2>/dev/null || echo "gone 0"
    done
  ) > "${WORK_DIR}/health" &
  HEALTH_MONITOR_PID=$!

  sleep 3
  local load_start load_end k6_summary
  load_start="$(date +%s)"
  k6_summary="$(docker run --rm -i --network "${NET}" --cpuset-cpus="${LOAD_CPUS}" \
    -e RATE="${RATE}" -e VUS="${VUS}" -e RAMP_S="${RAMP_S}" -e HOLD_S="${HOLD_S}" \
    "${K6_IMAGE}" run --quiet - < "${SCRIPT_DIR}/load.js" 2>&1 | grep '^K6_SUMMARY' || true)"
  load_end="$(date +%s)"
  sleep 3
  kill "${HEALTH_MONITOR_PID}" 2>/dev/null || true
  wait "${HEALTH_MONITOR_PID}" 2>/dev/null || true
  HEALTH_MONITOR_PID=""

  local oom_killed running
  oom_killed="$(docker inspect -f '{{.State.OOMKilled}}' "${SUT}")"
  running="$(docker inspect -f '{{.State.Running}}' "${SUT}")"
  docker cp "${PROBE}:/tmp/samples" "${WORK_DIR}/samples" >/dev/null 2>&1 || true
  [[ -s "${WORK_DIR}/samples" ]] || finish 1 "the memory sampler recorded nothing — cannot assert, failing closed"

  local reqs failed
  reqs="$(echo "${k6_summary}" | sed -n 's/.*"reqs":\([0-9]*\).*/\1/p')"
  failed="$(echo "${k6_summary}" | sed -n 's/.*"failed":\([0-9.eE+-]*\).*/\1/p')"

  local verdict
  verdict="$(awk -v limit_kib="$((LIMIT_MIB * 1024))" -v t0="${load_start}" -v t1="${load_end}" \
    -v max_peak_pct="${MAX_PEAK_PCT}" -v min_rssfile_pct="${MIN_RSSFILE_PCT}" -v min_conns="${MIN_CONNECTIONS}" \
    -v reqs="${reqs:-0}" -v min_reqs="${MIN_REQUESTS}" -v failed="${failed:-1}" -v max_failed_pct="${MAX_FAILED_PCT}" \
    -v min_rssfile_kib="$((MIN_RSSFILE_MIB * 1024))" '
    {
      unreclaimable = ($2 + $3 + $4) / 1024
      if (unreclaimable > peak) peak = unreclaimable
      if ($1 < t0) { idle_rssfile = $5 }
      else if ($1 <= t1) {
        if (min_rssfile == "" || $5 < min_rssfile) min_rssfile = $5
        if ($6 > conns) conns = $6
      }
    }
    END {
      peak_pct = peak * 100 / limit_kib
      rssfile_pct = idle_rssfile > 0 ? min_rssfile * 100 / idle_rssfile : 0
      printf "peak_unreclaimable=%.0fMiB(%.1f%%) idle_rssfile=%.1fMiB min_rssfile=%.1fMiB(%.0f%%) peak_connections=%d requests=%d\n", \
        peak / 1024, peak_pct, idle_rssfile / 1024, min_rssfile / 1024, rssfile_pct, conns, reqs
      if (peak_pct > max_peak_pct) printf "FAIL: unreclaimable memory peaked at %.1f%% of the limit (max %d%%)\n", peak_pct, max_peak_pct
      if (idle_rssfile <= 0 || rssfile_pct < min_rssfile_pct) printf "FAIL: JVM file-backed pages fell to %.0f%% of idle (min %d%%) - code-page reclaim thrash\n", rssfile_pct, min_rssfile_pct
      if (min_rssfile == "" || min_rssfile < min_rssfile_kib) printf "FAIL: JVM file-backed pages fell to %.1f MiB (min %d MiB) - code-page reclaim thrash\n", min_rssfile / 1024, min_rssfile_kib / 1024
      if (failed * 100 > max_failed_pct) printf "FAIL: %.2f%% of requests failed (max %d%%)\n", failed * 100, max_failed_pct
      if (conns < min_conns) printf "FAIL: only %d concurrent connections reached (min %d) - the rig did not deliver the load\n", conns, min_conns
      if (reqs * (1 - failed) < min_reqs) printf "FAIL: only %d requests succeeded (min %d) - the server was not loaded\n", reqs * (1 - failed), min_reqs
    }' "${WORK_DIR}/samples")"

  printMessage "${TEST_CASE}: ${verdict//$'\n'/ | } | oomKilled=${oom_killed} running=${running} k6=${k6_summary#K6_SUMMARY }"

  local failures=""
  [[ "${oom_killed}" == "false" && "${running}" == "true" ]] || failures+="container was killed (OOMKilled=${oom_killed}, running=${running}); "
  failures+="$(echo "${verdict}" | grep '^FAIL' | tr '\n' ';' || true)"
  if grep -q '^unhealthy' "${WORK_DIR}/health"; then
    failures+=" HEALTHCHECK reported unhealthy during load;"
  fi
  [[ -n "${reqs}" ]] || failures+=" k6 produced no summary;"

  if [[ -n "${failures}" ]]; then
    finish 1 "${failures}"
  fi
  finish 0
}

integration_test
