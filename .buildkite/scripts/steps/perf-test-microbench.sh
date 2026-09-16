#!/usr/bin/env bash
set -euo pipefail

# Micro-benchmark step (perf queue) — the ABSOLUTE backstop the rolling-history
# baseline can't provide. Runs the JMH MatchingBenchmark (matcher hot path) and
# captures gc.alloc.rate.norm (bytes/op) + time/op per param combo. JMH is
# low-noise, so an absolute regression here is trustworthy even on cloud CI and
# independent of the stored baseline — this is the class of signal that proved
# issue #2329 (O(n)-vs-O(1) per-op cost).
#
# Emits perf-microbench.json {microbench: {<matcherType>_<count>: {...}}} as a
# Buildkite artifact; perf-test-compare.sh merges it into the run result.
#
# Heavy (builds mockserver-netty + its upstream reactor deps — the set the benchmark
# module compiles against — then forks a JVM per param), so it runs only in the
# scheduled/manual perf pipeline on the dedicated box. Tune JMH via JMH_ARGS.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

MAVEN_IMAGE="${MAVEN_IMAGE:-mockserver/mockserver:maven}"
# Focused, bounded param sweep: 3 matcher types at the realistic 100-expectation
# scan depth, INFO logging, short iterations. ~1-2 min of JMH after the build.
JMH_ARGS="${JMH_ARGS:--f 1 -wi 3 -i 5 -r 2 -w 2 -p matcherType=EXACT,REGEX,JSON_BODY -p expectationCount=100 -p logLevel=INFO -prof gc}"

RESULT_RAW="mockserver/mockserver-benchmark/target/jmh-result.json"
OUT_JSON="$REPO_ROOT/perf-microbench.json"

# Make a failure of THIS step less silent. perf-test-compare.sh owns the Buildkite
# regression annotation, but it runs only AFTER this step passes
# (it sits behind the `wait: ~` in perf-test-guard.sh). So when this backstop dies
# — as it did silently from 2026-09-12, when a reactor-target/pom drift stopped the
# benchmark deps resolving — the ONLY signal is a red square nobody watches. Emit a
# failure annotation ourselves so a broken backstop is visible on the build itself.
annotate_on_failure() {
  local ec=$?
  if [ "$ec" -ne 0 ] && command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' ":x: **Perf micro-benchmark backstop FAILED** (exit ${ec}) — the JMH MatchingBenchmark produced NO signal for this run. This is a harness/build failure (e.g. the benchmark reactor did not build/resolve), NOT a measured regression. See this step's log." \
      | buildkite-agent annotate --style error --context perf-microbench || true
  fi
}
trap annotate_on_failure EXIT

echo "--- building mockserver-netty + upstream (the benchmark's compile deps), then running MatchingBenchmark"
# shellcheck disable=SC2016
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i "$MAVEN_IMAGE" \
  -m "${MAVEN_MEMORY:-7g}" \
  --entrypoint bash \
  -w /build \
  -e "JMH_ARGS=$JMH_ARGS" \
  -- -c '
    set -euo pipefail
    cd /build/mockserver                       # the Maven reactor root (pom.xml lives here, not /build)
    # Install mockserver-netty AND its upstream (-am). That set covers BOTH org.mock-server
    # module dependencies the benchmark declares (mockserver-benchmark/pom.xml depends on
    # mockserver-core AND mockserver-netty), which is why targeting only mockserver-core
    # here silently broke the step from 2026-09-12 (issue #2669 added the netty dep).
    # This mirrors the sibling perf-test-h2multiplex.sh. We deliberately do NOT use
    # -pl mockserver-benchmark: the benchmark module is intentionally absent from the
    # parent <modules> (its JMH annotation processor must not enter the default build),
    # so -pl mockserver-benchmark fails with "Could not find the selected project in the
    # reactor". The target must therefore name an in-reactor module. If the benchmark ever
    # gains an org.mock-server dependency OUTSIDE the upstream of mockserver-netty, the
    # mvn compile below fails to resolve it and the annotate_on_failure trap surfaces that
    # LOUDLY as a red build instead of the silent red square this step became.
    mvn -q -pl mockserver-netty -am install -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true
    cd mockserver-benchmark
    mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Djacoco.skip=true
    CP="target/classes:$(cat target/classpath.txt)"
    # shellcheck disable=SC2086
    java -cp "$CP" org.openjdk.jmh.Main $JMH_ARGS -rf json -rff target/jmh-result.json
  '

if [ ! -f "$REPO_ROOT/$RESULT_RAW" ]; then
  echo "ERROR: JMH did not produce $RESULT_RAW" >&2
  exit 1
fi

# Reshape JMH's array into {microbench: {<matcherType>_<count>: {time_per_op, time_unit, alloc_bytes_per_op}}}.
jq '[.[] | {
      key: (.params.matcherType + "_" + .params.expectationCount),
      value: {
        time_per_op: .primaryMetric.score,
        time_unit: .primaryMetric.scoreUnit,
        alloc_bytes_per_op: (.secondaryMetrics["gc.alloc.rate.norm"].score // null)
      }
    }] | from_entries | {microbench: .}' \
  "$REPO_ROOT/$RESULT_RAW" > "$OUT_JSON"

echo "--- perf-microbench.json"
cat "$OUT_JSON"

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "perf-microbench.json" || true
fi

# --- scaling sweep ------------------------------------------------------------
# Second JMH backstop: run-scaling.sh runs MatchingBenchmark (scan cost GROWS with
# expectationCount) + CandidateIndexBenchmark (SCAN grows, INDEX stays flat) over a
# FIXED param sweep and emits perf-scaling.json {scaling:{matching,candidate_index}}.
# It rebuilds mockserver-netty + its upstream reactor deps (same prep as above) and
# uploads its own artifact when buildkite-agent is present. Bounded by JMH_ARGS_SCALING
# (consistent with the microbench iteration budget above) so it stays inside the step
# timeout; the sweep crosses MANY param combos, so a JVM-fork is spawned per combo.
SCALING_RAW="mockserver/mockserver-benchmark/perf-scaling.json"
JMH_ARGS_SCALING="${JMH_ARGS_SCALING:--f 1 -wi 3 -i 5 -r 2 -w 2}"

echo "--- running scaling sweep (run-scaling.sh)"
# shellcheck disable=SC2016
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i "$MAVEN_IMAGE" \
  -m "${MAVEN_MEMORY:-7g}" \
  --entrypoint bash \
  -w /build \
  -e "JMH_ARGS_SCALING=$JMH_ARGS_SCALING" \
  -- -c '
    set -euo pipefail
    cd /build/mockserver/mockserver-benchmark        # run-scaling.sh resolves the reactor root from here
    SCALING_RESULT_PATH="$(pwd)/perf-scaling.json" ./run-scaling.sh
  '

if [ ! -f "$REPO_ROOT/$SCALING_RAW" ]; then
  echo "ERROR: scaling sweep did not produce $SCALING_RAW" >&2
  exit 1
fi
cp "$REPO_ROOT/$SCALING_RAW" "$REPO_ROOT/perf-scaling.json"

echo "--- perf-scaling.json"
cat "$REPO_ROOT/perf-scaling.json"

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "perf-scaling.json" || true
fi
