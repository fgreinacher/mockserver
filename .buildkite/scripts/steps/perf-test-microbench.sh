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
#
# FORK COUNT (item 15c): -f 2, not -f 1. A single fork never samples inter-fork
# JIT variance, so the reported MAD understates the real run-to-run dispersion and
# any timing budget derived from it is tighter than the data supports. Two forks
# capture that variance. To keep the doubled fork count from doubling wall-clock,
# warmup/measurement iterations are trimmed (-wi 3 -> 2, -i 5 -> 3): per param
# combo this goes from 1 fork x (3+5) iters to 2 forks x (2+3) iters — MORE
# measurement points (6 vs 5) and now spread across 2 JVMs, at ~+33% wall-clock
# instead of +100%. `firstMatchingExpectation_noMatch` is MatchingBenchmark's only
# @Benchmark, and the explicit class include below pins this run to it so the newly
# promoted dark benchmarks (run separately, below) cannot leak junk rows in here.
#
# detailedMatchFailures is pinned to `false` (the shipped default): this primary
# run's gating .microbench.*.time_per_op baseline describes the NON-detailed matcher
# hot path, and the reshape below keys rows by matcherType_expectationCount — leaving
# the param unpinned would expand each matcherType into two rows that collide on that
# key (from_entries silently keeps the last), half-dropping the run AND mixing the
# detailed path into a gating metric. The detailed arm is floored by the per-merge
# perf-alloc-gate.sh, not here.
JMH_ARGS="${JMH_ARGS:--f 2 -wi 2 -i 3 -r 2 -w 2 -p matcherType=EXACT,REGEX,JSON_BODY -p expectationCount=100 -p logLevel=INFO -p detailedMatchFailures=false -prof gc}"
JMH_INCLUDE="${JMH_INCLUDE:-org\.mockserver\.benchmark\.MatchingBenchmark\.}"

# --- item 15b: promote the dark benchmarks -----------------------------------
# These JMH classes were written but run by NO CI step (bit-rotting toward the
# same silent death MatchingBenchmark once had). Promote them into THIS daily
# step so they emit time_per_op / alloc_bytes_per_op through the same result path.
# They share the classpath the MatchingBenchmark run just built (no extra module
# build), run in one extra JMH invocation, and their metrics land NON-GATING (no
# baseline yet — perf-test-compare.sh reads them from `microbench_extra`, which has
# no `gating:true` flag, so a flagged regression is notify-only). -bm avgt forces a
# single average-time mode so every row is a comparable time_per_op (MetricsIncrement
# declares Throughput+AverageTime); -prof gc captures alloc_bytes_per_op.
JMH_INCLUDE_EXTRA="${JMH_INCLUDE_EXTRA:-org\.mockserver\.benchmark\.(InboundDecodeBenchmark|LocalCallbackDispatchBenchmark|ForwardPathBenchmark|OpenApiValidationBenchmark|MetricsIncrementBenchmark|ResponseWriteBenchmark|Http3RequestBridgeBenchmark)\.}"
JMH_ARGS_EXTRA="${JMH_ARGS_EXTRA:--f 2 -wi 2 -i 3 -r 2 -w 2 -bm avgt -prof gc}"

# EXACT expected row count for the promoted set — a fail-closed guard against
# PARTIAL include drift (a rename of one of the classes yields fewer rows and,
# being non-gating, would otherwise pass unnoticed). The default classes emit,
# with the default JMH_ARGS_EXTRA (-bm avgt collapses MetricsIncrement's dual mode):
#   InboundDecode 1 method x 3 bodySize             = 3
#   LocalCallbackDispatch 3 methods x 0 params      = 3
#   ForwardPath 8 methods x 0 params                = 8
#   OpenApiValidation 1 method x 2 mode x 2 schema  = 4
#   MetricsIncrement 2 methods x 0 params (avgt)    = 2
#   ResponseWrite 1 method x 3 responseSize x 2 declareBodyCharset = 6
#                                                        (item 16 response-write arm;
#                                                         declareBodyCharset splits the
#                                                         explicit-charset reuse path from
#                                                         the implicit-charset control)
#   Http3RequestBridge 1 method x 2 protocol x 3 bodySize = 6   (item 20a H3-vs-H2 A/B)
#                                          subtotal  = 32
# PLUS the item 9b/9c proxy-path benchmarks, run in a SEPARATE pinned invocation
# (JMH_INCLUDE_PROXY / JMH_ARGS_PROXY below) but MERGED into this same
# perf-microbench-extra.json, so this ONE guard covers both invocations:
#   RelayByteCopy   2 methods x 1 bodySize x 1 readSize (pinned) = 2   (9c relay leg)
#   SocksHandshake  3 methods x 1 scenario            (pinned)   = 3   (9b handshake)
#                                          subtotal  =  5
#                                             total  = 37
# Overridable so a narrowed local include/args run can set its own expected count;
# any change to the benchmark surface (a new @Param, a new @Benchmark) is a
# deliberate, reviewed bump of this number, not a silent row-count drift.
EXTRA_EXPECTED="${EXTRA_EXPECTED:-37}"

# --- items 9b + 9c: proxy-path benchmarks -------------------------------------
# The largest genuinely uncovered area the mandate names (performance-programme
# item 9). Two JMH classes, promoted here so they emit through the SAME notify-only
# microbench_extra path as the item-15b dark set (the existing
# `microbench_extra.*.{time_per_op,alloc_bytes_per_op}` budgets cover them with NO
# new budget key; time_per_op carries hw:true, alloc_bytes_per_op does not — the
# correct classification, since a relayed-byte timing moves with the box but
# bytes/op is machine-independent):
#   9c RelayByteCopyBenchmark  — the CONNECT/relay response leg (decode -> relay
#      aggregator -> re-encode); the matcher backstop says nothing about a byte-copy-
#      dominated path.
#   9b SocksHandshakeBenchmark — the per-connection SOCKS4/5 handshake codecs (the
#      plan's sanctioned downgrade from a k6 SOCKS arm: k6 has no SOCKS transport, and
#      the SOCKS steady-state relay is already 9c's territory, so the handshake is the
#      only uncovered SOCKS cost).
# WHY a SEPARATE invocation rather than appending both to JMH_INCLUDE_EXTRA:
#   (1) COST. Their full default @Param cartesians are RelayByteCopy 2x3x4=24 +
#       Socks 3x3=9 = 33 combos. At the guard's own ~1 min/combo-on-the-box budget
#       (see perf-test-guard.sh) that ~doubles the extra set and blows the 70m step
#       cap. A daily regression trend needs ONE representative cell per method, not
#       the full characterisation sweep (the same reason the PRIMARY MatchingBenchmark
#       run pins -p expectationCount=100). The full sweeps stay available on demand via
#       the benchmarks' own run.sh (documented in each class's javadoc).
#   (2) PARAM COLLISION. JMH_ARGS_EXTRA is shared and UNPINNED; adding `-p bodySize=...`
#       there would ALSO pin InboundDecodeBenchmark (which has a bodySize @Param),
#       collapsing its 3 rows to 1 and half-dropping that gated-adjacent metric. A
#       dedicated args string keeps the pin off the shared classes.
# The pinned cell: relay at a 256 KiB body fed in 1460-byte (MTU-sized) fragments — a
# ~180-fragment large-response relay, where the per-fragment object churn 9c measures
# is clearly visible and a regression would move alloc_bytes_per_op; SOCKS5_PASSWORD —
# the heaviest handshake (greeting + password-auth + command, two decoder swaps), with
# its own two controls (detect = allocation-free front-door floor; channelPlumbingOnly
# = the per-op EmbeddedChannel construction share) riding along for free. JMH applies
# each -p only to the class that declares it (bodySize/readSize -> relay, scenario ->
# socks; the other shows N/A) — verified: the combined invocation exits 0 and emits
# exactly the 5 rows above. Same classpath as the runs above (no extra module build).
JMH_INCLUDE_PROXY="${JMH_INCLUDE_PROXY:-org\.mockserver\.benchmark\.(RelayByteCopyBenchmark|SocksHandshakeBenchmark)\.}"
JMH_ARGS_PROXY="${JMH_ARGS_PROXY:--f 2 -wi 2 -i 3 -r 2 -w 2 -bm avgt -prof gc -p bodySize=262144 -p readSize=1460 -p scenario=SOCKS5_PASSWORD}"

# -f 2 (item 15c): scaling sweep gets the same 2-fork/trimmed-iteration treatment
# (defined here, not in the scaling section below, so the JMH-config fingerprint
# recorded with the microbench result — item 15c baseline-discontinuity guard — can
# name it before the scaling run).
JMH_ARGS_SCALING="${JMH_ARGS_SCALING:--f 2 -wi 2 -i 3 -r 2 -w 2}"

RESULT_RAW="mockserver/mockserver-benchmark/target/jmh-result.json"
EXTRA_RAW="mockserver/mockserver-benchmark/target/jmh-result-extra.json"
PROXY_RAW="mockserver/mockserver-benchmark/target/jmh-result-proxy.json"
OUT_JSON="$REPO_ROOT/perf-microbench.json"
EXTRA_JSON="$REPO_ROOT/perf-microbench-extra.json"

# Make a failure of THIS step less silent. perf-test-compare.sh owns the Buildkite
# regression annotation, but it does NOT surface a failure of this step at all:
# perf-test-guard.sh wires compare to depend on this step with the PER-DEPENDENCY
# property `allow_failure: true` (NOT the step-level `allow_dependency_failure`,
# which Buildkite rejects on a depends_on edge and which, used at step level, would
# apply to EVERY edge and destroy compare's fail-closed dependency on the
# measurement step). So compare WAITS for this step's artifact but runs regardless
# of its exit code. (That wiring is deliberate — see the DEPENDENCY
# GRAPH note in perf-test-guard.sh — and it strengthens the case for this
# self-annotation rather than weakening it.) So when this backstop dies — as it did
# silently from 2026-09-12, when a reactor-target/pom drift stopped the benchmark
# deps resolving — the ONLY signal is a red square nobody watches. Emit a failure
# annotation ourselves so a broken backstop is visible on the build itself.
annotate_on_failure() {
  local ec=$?
  if [ "$ec" -ne 0 ] && command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' ":x: **Perf micro-benchmark backstop FAILED** (exit ${ec}) — a JMH benchmark (MatchingBenchmark, the promoted dark benchmarks, or the scaling sweep) produced NO signal for this run. This is a harness/build failure (e.g. the benchmark reactor did not build/resolve, or a benchmark include matched nothing), NOT a measured regression. See this step's log." \
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
  -e "JMH_INCLUDE=$JMH_INCLUDE" \
  -e "JMH_ARGS_EXTRA=$JMH_ARGS_EXTRA" \
  -e "JMH_INCLUDE_EXTRA=$JMH_INCLUDE_EXTRA" \
  -e "JMH_ARGS_PROXY=$JMH_ARGS_PROXY" \
  -e "JMH_INCLUDE_PROXY=$JMH_INCLUDE_PROXY" \
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
    mvn -q -pl mockserver-netty -am install -DskipTests -DskipITs -Djacoco.skip=true -Dcheckstyle.skip=true
    cd mockserver-benchmark
    mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Djacoco.skip=true
    CP="target/classes:$(cat target/classpath.txt)"
    # shellcheck disable=SC2086
    java -cp "$CP" org.openjdk.jmh.Main "$JMH_INCLUDE" $JMH_ARGS -rf json -rff target/jmh-result.json
    # item 15b: the promoted dark benchmarks, same classpath, one extra invocation.
    # shellcheck disable=SC2086
    java -cp "$CP" org.openjdk.jmh.Main "$JMH_INCLUDE_EXTRA" $JMH_ARGS_EXTRA -rf json -rff target/jmh-result-extra.json
    # items 9b + 9c: the proxy-path benchmarks, same classpath, a SEPARATE pinned
    # invocation (its -p pins must NOT reach the shared extra classes above — see the
    # JMH_ARGS_PROXY comment). Merged into perf-microbench-extra.json below.
    # shellcheck disable=SC2086
    java -cp "$CP" org.openjdk.jmh.Main "$JMH_INCLUDE_PROXY" $JMH_ARGS_PROXY -rf json -rff target/jmh-result-proxy.json
  '

if [ ! -f "$REPO_ROOT/$RESULT_RAW" ]; then
  echo "ERROR: JMH did not produce $RESULT_RAW" >&2
  exit 1
fi

# Reshape JMH's array into {microbench: {<matcherType>_<count>: {time_per_op, time_unit, alloc_bytes_per_op}}}.
#
# Also record the JMH METHODOLOGY under .config.jmh (item 15c baseline-discontinuity
# guard). The .microbench.*.time_per_op metric GATES, and its S3 rolling baseline was
# built under the previous methodology (-f1, 6s warmup). Changing to -f2/4s warmup can
# shift absolute timings, which would flag a SPURIOUS gating regression against a
# differently-measured baseline. perf-test-compare.sh reads this fingerprint and skips
# baseline runs whose .config.jmh differs from the head run's, so a methodology change
# self-invalidates its own baseline (metrics go no-baseline until history repopulates)
# rather than firing a false red. Nested under .config to follow perf-test-run.sh's
# self-describing config block; the jq -s '*' merge in compare deep-merges it beside
# the SUT config fields. Recorded as the exact arg strings so ANY methodology change
# (fork/warmup/measurement) changes the fingerprint — conservatively self-invalidating.
#
# NOTE: JMH_ARGS_PROXY (items 9b/9c) is DELIBERATELY NOT part of this fingerprint.
# The proxy benchmarks run in their own JVM forks AFTER the primary MatchingBenchmark
# has finished and written jmh-result.json, so they cannot shift the matcher timings,
# and their own rows are NEW keys that self-start no-baseline regardless. Adding
# args_proxy here would, on first deploy and on any later proxy-only fork/iter tweak,
# needlessly reset the GATING microbench.*.time_per_op matcher baseline (the one real
# gate in this step) via the whole-fingerprint match below — a real loss of gating
# window for zero benefit. Keep it out; the proxy metrics are notify-only, so at worst
# a future proxy-arg change causes a harmless notify-only annotation on those rows.
jq --arg args "$JMH_ARGS" --arg argsExtra "$JMH_ARGS_EXTRA" --arg argsScaling "$JMH_ARGS_SCALING" \
   '[.[] | {
      key: (.params.matcherType + "_" + .params.expectationCount),
      value: {
        time_per_op: .primaryMetric.score,
        time_unit: .primaryMetric.scoreUnit,
        alloc_bytes_per_op: (.secondaryMetrics["gc.alloc.rate.norm"].score // null)
      }
    }] | from_entries
    | { microbench: ., config: { jmh: { args: $args, args_extra: $argsExtra, args_scaling: $argsScaling } } }' \
  "$REPO_ROOT/$RESULT_RAW" > "$OUT_JSON"

echo "--- perf-microbench.json"
cat "$OUT_JSON"

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "perf-microbench.json" || true
fi

# --- items 15b + 9b/9c: reshape the promoted extra benchmark results ----------
if [ ! -f "$REPO_ROOT/$EXTRA_RAW" ]; then
  echo "ERROR: promoted dark benchmarks did not produce $EXTRA_RAW (JMH include matched nothing, or the run crashed)" >&2
  exit 1
fi
# items 9b + 9c: the proxy-path invocation must have produced its raw too. Its absence
# is the same class of failure (include matched nothing / fork crashed) and must red
# LOUDLY here — otherwise `jq -s add` over a missing file would iterate null below.
if [ ! -f "$REPO_ROOT/$PROXY_RAW" ]; then
  echo "ERROR: proxy-path benchmarks (items 9b/9c) did not produce $PROXY_RAW (JMH include matched nothing, or the run crashed)" >&2
  exit 1
fi

# Reshape BOTH the item-15b dark set AND the item-9b/9c proxy set into ONE
# `microbench_extra` object. `jq -s add` slurps the two JMH result arrays and
# concatenates them, so the single key/value shaping below is the one source of truth
# for both invocations (keyed by <ClassName>.<method> plus any @Param values — these
# benchmarks have DIFFERENT params from Matching, so a benchmark+params key is used
# instead of the matcherType_count key). Emitted under `microbench_extra`, which
# perf-test-compare.sh consumes NON-GATING (notify-only until each has >=10 clean runs
# of history to derive a budget from).
jq -s 'add | [.[] | {
      key: ((.benchmark | sub("^org\\.mockserver\\.benchmark\\.";""))
            + (if ((.params // {}) | length) > 0
               then "_" + ((.params) | to_entries | sort_by(.key) | map(.key + "-" + (.value|tostring)) | join("_"))
               else "" end)),
      value: {
        time_per_op: .primaryMetric.score,
        time_unit: .primaryMetric.scoreUnit,
        alloc_bytes_per_op: (.secondaryMetrics["gc.alloc.rate.norm"].score // null)
      }
    }] | from_entries | {microbench_extra: .}' \
  "$REPO_ROOT/$EXTRA_RAW" "$REPO_ROOT/$PROXY_RAW" > "$EXTRA_JSON"

# Fail-closed guard (the whole point of item 15b, now also covering the 9b/9c proxy
# set merged in above): a benchmark that silently emits NO — or FEWER — rows is exactly
# the failure mode this step exists to catch. Assert the EXACT expected row count, not
# merely ">= 1": a total vanish (0 rows), a PARTIAL drift (e.g. one of the extra classes
# renamed in JMH_INCLUDE_EXTRA/JMH_INCLUDE_PROXY -> fewer rows), AND a proxy -p pin that
# stopped applying (which would multiply the relay/socks rows back to their full
# cartesian) all fail LOUDLY here. A drift is otherwise invisible because these metrics
# are non-gating, so compare would never flag the missing/extra rows. The trap surfaces
# this as a red build rather than shipping a green build that measured other than it
# claims. (perf-test-compare.sh iterates head metrics only, so it cannot detect a
# baseline-has-key-but-head-lacks-it drop — this producer-side count is where drift MUST
# be caught.)
EXTRA_COUNT="$(jq '.microbench_extra | length' "$EXTRA_JSON")"
if [ "${EXTRA_COUNT:-0}" -ne "$EXTRA_EXPECTED" ]; then
  echo "ERROR: promoted extra benchmarks (item 15b dark set + item 9b/9c proxy set) produced ${EXTRA_COUNT:-0} result rows in $EXTRA_JSON, expected ${EXTRA_EXPECTED} — benchmark rename/include drift, a crashed fork, a proxy -p pin that stopped applying, or a deliberate surface change that did not bump EXTRA_EXPECTED." >&2
  echo "Rows actually emitted:" >&2
  jq -r '.microbench_extra | keys[] | "  - " + .' "$EXTRA_JSON" >&2 || true
  exit 1
fi
echo "--- perf-microbench-extra.json (${EXTRA_COUNT}/${EXTRA_EXPECTED} promoted benchmark rows)"
cat "$EXTRA_JSON"

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "perf-microbench-extra.json" || true
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
# JMH_ARGS_SCALING is defined at the top (with the other JMH args) so the config
# fingerprint recorded with the microbench result can name it. -f 2 (item 15c): the
# scaling sweep's MatchingBenchmark arm reports time_per_op too, so it gets the same
# 2-fork treatment (and iteration trim) to sample inter-fork JIT variance.

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

# --- G1 churn gate ------------------------------------------------------------
# Third JMH backstop: run-g1-churn.sh runs CandidateIndexChurnBenchmark's ONE gated arm
# (n=15,000 expectations, candidate index engaged, STATIC vs CHURN readers, -t 1) and emits
# perf-churn.json {churn:{alloc_ratio_index_n15000, ...}}. Unlike the scaling sweep (recorded
# but never compared — notify-only-by-omission), THIS ratio is enumerated AND budgeted in
# perf-test-compare.sh / perf-budgets.json, so it GATES: a regression that reintroduced
# rebuild-on-read would move the churn/static allocation ratio ~1.06x -> ~1000x+ (three orders
# of magnitude), which the absolute floor catches. The FAIL-CLOSED guard that a CHURN arm
# actually churned — rather than silently degrading to a static measurement when its daemon
# writer thread dies, which would land the ratio at ~1.0 and pass the 1.5 floor GREEN — is the
# benchmark's OWN @TearDown assertion (it throws on unhealthy churn signals), enforced by JMH
# -foe true so the error exits non-zero and reds THIS step. (run-g1-churn.sh also runs the
# rebuild PROOF as a supplementary main-thread MECHANISM check — but the proof is synchronous
# and never exercises the writer thread, so it is NOT what catches a dead writer.) It rebuilds
# mockserver-netty + upstream (same self-contained prep as run-scaling.sh) and uploads its own
# artifact when buildkite-agent is present. This is the control that would have caught G1 going
# stale — see docs/plans/performance-programme.md.
CHURN_RAW="mockserver/mockserver-benchmark/perf-churn.json"
JMH_ARGS_CHURN="${JMH_ARGS_CHURN:--f 1 -wi 3 -i 5 -r 1 -w 1 -t 1}"

echo "--- running G1 churn gate (run-g1-churn.sh ci)"
# shellcheck disable=SC2016
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i "$MAVEN_IMAGE" \
  -m "${MAVEN_MEMORY:-7g}" \
  --entrypoint bash \
  -w /build \
  -e "JMH_ARGS_CHURN=$JMH_ARGS_CHURN" \
  -- -c '
    set -euo pipefail
    cd /build/mockserver/mockserver-benchmark        # run-g1-churn.sh resolves the reactor root from here
    CHURN_RESULT_PATH="$(pwd)/perf-churn.json" ./run-g1-churn.sh ci
  '

if [ ! -f "$REPO_ROOT/$CHURN_RAW" ]; then
  echo "ERROR: G1 churn gate did not produce $CHURN_RAW" >&2
  exit 1
fi
cp "$REPO_ROOT/$CHURN_RAW" "$REPO_ROOT/perf-churn.json"

echo "--- perf-churn.json"
cat "$REPO_ROOT/perf-churn.json"

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "perf-churn.json" || true
fi
