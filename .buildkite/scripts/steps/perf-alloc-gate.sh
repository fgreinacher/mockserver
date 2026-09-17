#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# PER-MERGE allocation backstop (performance-programme item 16).
# =============================================================================
#
# WHAT: runs the three JMH ALLOCATION benchmarks at PINNED representative params
# — MatchingBenchmark (matching), InboundDecodeBenchmark (request decode),
# ResponseWriteBenchmark (response serialise + write) — captures each one's
# gc.alloc.rate.norm (bytes/op), and FAILS the build if any exceeds its ABSOLUTE
# committed floor in mockserver-performance-test/perf-budgets.json
# (premerge_alloc.<Class>.alloc_bytes_per_op).
#
# WHY it is separate from the daily perf-test-microbench.sh + perf-test-compare.sh:
#   * Coverage. MatchingBenchmark alone measures MATCHING only — not Netty decode
#     or response serialisation/write. An allocation regression that moves bytes
#     OUT of the matcher and INTO decode/response-write reads as an IMPROVEMENT
#     against a matcher-only gate. Adding the decode + response-write arms with
#     absolute floors makes "bytes moved into decode/write" trip a floor.
#   * Absolute, not rolling. The daily compare uses a rolling median + MAD over
#     per-merge history, which ABSORBS the slow drift the gate exists to catch. A
#     committed absolute floor cannot be normalised down by a quiet window.
#   * Per merge is a property of WIRING. This step is wired UNCONDITIONALLY into
#     .buildkite/pipeline-java.yml (no `if: build.branch == 'master'`), BEFORE the
#     `wait` that precedes the master-gated block, so it runs PRE-merge and blocks
#     the PR. A branch-conditioned step, or one in the master-only container suite,
#     would silently be a POST-merge gate. (Note: pipeline-java.yml is itself
#     orchestrator-path-filtered to mockserver/ + mockserver-ui/ changes, so a JDK
#     or base-image bump that shifts allocation reaches this gate only via the
#     daily run — this step does NOT cover that class of change.)
#
# gc.alloc.rate.norm is DETERMINISTIC (bytes allocated per op — independent of CPU
# speed), so an absolute regression here is trustworthy on cloud CI. Some floors
# are PROVISIONAL (see perf-budgets.json): derived from local measurement with
# headroom rather than from >=10 notify-only runs, because these benchmarks have
# no history yet. ResponseWrite is ALSO promoted notify-only into the daily
# microbench step so history accrues and the floors can graduate.
#
# Heavy (builds mockserver-netty + upstream reactor deps, then forks a JVM per
# benchmark), same prep as perf-test-microbench.sh. Runs on the default queue.
#
# LOCAL / TEST HOOK: set PERF_ALLOC_JMH_RESULT to a pre-produced JMH -rf json to
# skip the Docker build+run and only evaluate that file against the floors. Used
# by the degrade-and-confirm-red verification and for fast local iteration.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

MAVEN_IMAGE="${MAVEN_IMAGE:-mockserver/mockserver:maven}"
BUDGETS_FILE="${PERF_BUDGETS_FILE:-$REPO_ROOT/mockserver-performance-test/perf-budgets.json}"

# The three allocation benchmarks and the params that pin each to EXACTLY ONE row.
# MatchingBenchmark reads matcherType/expectationCount/logLevel; InboundDecode reads
# bodySize; ResponseWrite reads responseSize. JMH applies each -p only to the
# benchmarks that declare it, so passing all five yields one row per class = 3 rows.
JMH_INCLUDE="${PERF_ALLOC_INCLUDE:-org\.mockserver\.benchmark\.(MatchingBenchmark|InboundDecodeBenchmark|ResponseWriteBenchmark)\.}"
JMH_ARGS="${PERF_ALLOC_JMH_ARGS:--bm avgt -prof gc -f 1 -wi 3 -i 5 -r 1 -w 1 -p matcherType=EXACT -p expectationCount=100 -p logLevel=INFO -p bodySize=16384 -p responseSize=16384}"

# EXACT expected row count — a fail-closed guard against include/param drift (a
# renamed class, or a param that stops pinning, silently drops a row). Three
# benchmarks, one row each.
EXPECTED_ROWS="${PERF_ALLOC_EXPECTED_ROWS:-3}"

RESULT_RAW="mockserver/mockserver-benchmark/target/jmh-alloc-gate.json"

annotate_on_failure() {
  local ec=$?
  if [ "$ec" -ne 0 ] && command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' ":x: **Per-merge allocation gate FAILED** (exit ${ec}) — a benchmark allocated more bytes/op than its committed floor in perf-budgets.json, OR the gate could not measure (build/resolve failure, include matched wrong row count, missing budget). See this step's log for the per-benchmark table." \
      | buildkite-agent annotate --style error --context perf-alloc-gate || true
  fi
}
trap annotate_on_failure EXIT

# -----------------------------------------------------------------------------
# evaluate <jmh-result.json> : compare each row's alloc_bytes_per_op to its floor.
# Exit 0 = all within floor; exit 1 = a regression OR a fail-closed violation.
# -----------------------------------------------------------------------------
evaluate() {
  local result="$1"
  if [ ! -f "$result" ]; then
    echo "ERROR: JMH result not found: $result" >&2
    return 1
  fi
  if [ ! -f "$BUDGETS_FILE" ]; then
    echo "ERROR: perf-budgets.json missing: $BUDGETS_FILE (fail-closed: a missing budget must never mean 'no floor')" >&2
    return 1
  fi

  # Reshape JMH rows -> one record per benchmark class with its measured bytes/op,
  # its budget key, floor and verdict. jq -e sets exit status from the last value:
  # true (all ok, correct row count, no missing/absent floor) -> 0, else -> 1.
  local report
  report="$(jq -n \
    --slurpfile jmh "$result" \
    --slurpfile budgets "$BUDGETS_FILE" \
    --argjson expected "$EXPECTED_ROWS" '
      ($budgets[0].budgets) as $b
      | [ $jmh[0][]
          | (.benchmark | sub("^org\\.mockserver\\.benchmark\\.";"") | sub("\\..*$";"")) as $cls
          | ("premerge_alloc." + $cls + ".alloc_bytes_per_op") as $bkey
          | (.secondaryMetrics["gc.alloc.rate.norm"].score) as $alloc
          | ($b[$bkey]) as $budget
          | ($budget.floor) as $floor
          | {
              cls: $cls,
              alloc: $alloc,
              bkey: $bkey,
              floor: $floor,
              provisional: ($budget.provisional == true),
              # fail-closed: a row with no bytes/op reading (gc profiler absent) or
              # no committed floor cannot be judged, so it is a HARD failure.
              measured: ($alloc != null),
              haveFloor: ($budget != null and $floor != null),
              ok: ($alloc != null and $budget != null and $floor != null and ($alloc <= $floor))
            }
        ] as $rows
      | {
          rows: $rows,
          rowCount: ($rows | length),
          expected: $expected,
          countOk: (($rows | length) == $expected),
          allOk: (($rows | all(.ok)) and (($rows | length) == $expected))
        }
    ')"

  # Human-readable table.
  echo "--- per-merge allocation gate — measured bytes/op vs committed floor"
  echo "$report" | jq -r '
    .rows[]
    | "  " + (if .ok then ":white_check_mark:" else ":x:" end)
      + " " + .cls
      + "  alloc=" + ((.alloc // 0) | floor | tostring) + " B/op"
      + "  floor=" + ((.floor // "MISSING") | tostring)
      + (if .provisional then "  (provisional)" else "" end)
      + (if (.measured | not) then "  [NO gc.alloc.rate.norm — profiler absent]" else "" end)
      + (if (.haveFloor | not) then "  [NO FLOOR in perf-budgets.json — fail-closed]" else "" end)'

  local rowCount expected countOk
  rowCount="$(echo "$report" | jq -r '.rowCount')"
  expected="$(echo "$report" | jq -r '.expected')"
  countOk="$(echo "$report" | jq -r '.countOk')"
  if [ "$countOk" != "true" ]; then
    echo "ERROR: allocation gate measured ${rowCount} benchmark row(s), expected ${expected} — include/param drift, a crashed fork, or a deliberate surface change that did not bump PERF_ALLOC_EXPECTED_ROWS." >&2
    return 1
  fi

  if [ "$(echo "$report" | jq -r '.allOk')" = "true" ]; then
    echo "--- :white_check_mark: allocation gate PASSED — every benchmark within its floor"
    if command -v buildkite-agent >/dev/null 2>&1; then
      local tbl
      tbl="$(echo "$report" | jq -r '.rows[] | "| " + .cls + " | " + ((.alloc // 0) | floor | tostring) + " | " + ((.floor // "MISSING") | tostring) + (if .provisional then " (prov.)" else "" end) + " |"')"
      printf '%s\n' ":white_check_mark: **Per-merge allocation gate PASSED** — every allocation benchmark within its committed floor.

| benchmark | bytes/op | floor |
|---|---:|---:|
${tbl}" | buildkite-agent annotate --style success --context perf-alloc-gate || true
    fi
    return 0
  fi

  echo "ERROR: allocation gate FAILED — one or more benchmarks over floor (or unmeasurable/floor-less):" >&2
  echo "$report" | jq -r '.rows[] | select(.ok | not)
    | "  - " + .cls + ": alloc=" + ((.alloc // 0) | floor | tostring)
      + " B/op floor=" + ((.floor // "MISSING") | tostring)' >&2
  return 1
}

# -----------------------------------------------------------------------------
# Fast path: evaluate a pre-produced JMH result (local test / degrade-and-confirm).
# -----------------------------------------------------------------------------
if [ -n "${PERF_ALLOC_JMH_RESULT:-}" ]; then
  echo "--- evaluating pre-produced JMH result: ${PERF_ALLOC_JMH_RESULT}"
  evaluate "$PERF_ALLOC_JMH_RESULT"
  exit $?
fi

# -----------------------------------------------------------------------------
# Full path: build the benchmark's compile deps, compile, run JMH, then evaluate.
# Mirrors perf-test-microbench.sh's build prep (mockserver-benchmark is out of the
# reactor, so its in-reactor deps must be installed via a named module + -am).
# -----------------------------------------------------------------------------
echo "--- building mockserver-netty + upstream (benchmark compile deps), then running the allocation benchmarks"
# shellcheck disable=SC2016
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i "$MAVEN_IMAGE" \
  -m "${MAVEN_MEMORY:-7g}" \
  --entrypoint bash \
  -w /build \
  -e "JMH_ARGS=$JMH_ARGS" \
  -e "JMH_INCLUDE=$JMH_INCLUDE" \
  -- -c '
    set -euo pipefail
    cd /build/mockserver
    mvn -q -pl mockserver-netty -am install -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true
    cd mockserver-benchmark
    mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Djacoco.skip=true
    CP="target/classes:$(cat target/classpath.txt)"
    # shellcheck disable=SC2086
    java -cp "$CP" org.openjdk.jmh.Main "$JMH_INCLUDE" $JMH_ARGS -rf json -rff target/jmh-alloc-gate.json
  '

if [ ! -f "$REPO_ROOT/$RESULT_RAW" ]; then
  echo "ERROR: JMH did not produce $RESULT_RAW" >&2
  exit 1
fi

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "$RESULT_RAW" || true
fi

evaluate "$REPO_ROOT/$RESULT_RAW"
