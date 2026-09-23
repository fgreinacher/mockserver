#!/usr/bin/env bash
set -euo pipefail

# HTTP/2 multiplex benchmark step (perf queue) — answers the open question from
# issue #2669: what does giving every HTTP/2 stream its own child channel cost
# under concurrency? Two attempts to measure this on the dev laptop failed (memory
# pressure), so it belongs on a quiet CI agent, which is here.
#
# TWO axes, both from issue #2669's HTTP/2-multiplex migration, both emitted into
# perf-h2-multiplex.json and persisted into the S3 run history by perf-test-compare.sh:
#
#   1. THROUGHPUT / LATENCY (org.mockserver.benchmark.Http2StreamChannelBenchmark):
#      N concurrent streams over ONE h2c connection, sweeping N = 1, 10, 100 (the
#      server caps MAX_CONCURRENT_STREAMS at 100, so 100 is the single-connection
#      ceiling). Emits {h2_multiplex:{streams_<N>:{throughput_rps, p50_us, ...}}}.
#
#   2. PER-CONNECTION MEMORY (org.mockserver.benchmark.Http2ConnectionMemoryBenchmark,
#      programme item 11): a CONNECTIONS axis — N connections x M concurrent in-flight
#      streams, shapes 1x1, 10x10, 100x10 — recording the heap delta per established
#      connection, exactly what the 8.0.0 changelog warned the multiplex migration
#      changed. The event log is cleared before each heap sample so the delta measures
#      connection + stream child-channel state, not logged bodies. Emits
#      {h2_connection_memory:{conn_<C>x<S>:{bytes_per_connection, ...}}}. The two JSON
#      objects are jq-merged into the single perf-h2-multiplex.json artifact below.
#
# NOTIFY-ONLY, and there is deliberately NO build-FAILING threshold on either axis:
# run-to-run variance on real build agents is unknown, so a gating threshold now would
# be guessing. They are recorded so a gating threshold can be added later once variance
# is known. The bytes_per_connection budget key (h2_connection_memory.*.bytes_per_connection
# in perf-budgets.json) is committed NON-GATING but is now SURFACED per run: the daily
# compare's metrics jq reads .h2_connection_memory and annotates each shape's
# bytes_per_connection notify-only against that budget, so a move is visible every build
# but cannot red the pipeline (see the item-11 clause in perf-test-compare.sh).
#
# The dated per-connection numbers here are a TREND signal for the current build. The
# one-time pre-8.0.0-vs-8.0.0 comparison that actually answers the changelog is the
# optional cross-version arm at the end of this script (H2_MEM_COMPARE_IMAGES), which
# drives an identical external client against two server images and diffs container RSS.
#
# The harness self-validates and exits NON-ZERO (code 2) on any integrity breach
# (incomplete run, non-200/body mismatch, sub-µs latency, impossible throughput).
# That failure is HARNESS-VALIDATION and is intentionally distinct from "perf got
# slower" (which never fails — there is no threshold). A non-zero exit here means
# the measurement is untrustworthy and MUST surface as a red build, so this step
# has no soft_fail. Before the sweep we run the harness `selftest` (server-free)
# to prove every gate still fires.
#
# Heavy (builds mockserver-netty + boots a real server), so it runs only in the
# scheduled/manual perf pipeline on the dedicated box.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

MAVEN_IMAGE="${MAVEN_IMAGE:-mockserver/mockserver:maven}"

# Sweep + iteration budget (env-tunable). Defaults give N*requestsPerStream total
# requests per point (e.g. 100*500 = 50,000 at N=100) — enough to be meaningful on
# a quiet box while staying inside the step timeout.
H2_BENCH_STREAMS="${H2_BENCH_STREAMS:-1,10,100}"
H2_BENCH_REQUESTS_PER_STREAM="${H2_BENCH_REQUESTS_PER_STREAM:-500}"
H2_BENCH_WARMUP_REQUESTS="${H2_BENCH_WARMUP_REQUESTS:-200}"

# Per-connection memory axis (item 11). shapes = connections x streams; repeats gives
# the sample spread; delay holds the matched responses open so the streams stay in flight.
H2_MEM_SHAPES="${H2_MEM_SHAPES:-1x1,10x10,100x10}"
H2_MEM_REPEATS="${H2_MEM_REPEATS:-5}"
H2_MEM_DELAY_S="${H2_MEM_DELAY_S:-600}"

OUT_JSON="$REPO_ROOT/perf-h2-multiplex.json"

echo "--- building mockserver-netty, then running the HTTP/2 multiplex + per-connection-memory benchmarks"
# shellcheck disable=SC2016
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i "$MAVEN_IMAGE" \
  -m "${MAVEN_MEMORY:-7g}" \
  --entrypoint bash \
  -w /build \
  -e "H2_BENCH_STREAMS=$H2_BENCH_STREAMS" \
  -e "H2_BENCH_REQUESTS_PER_STREAM=$H2_BENCH_REQUESTS_PER_STREAM" \
  -e "H2_BENCH_WARMUP_REQUESTS=$H2_BENCH_WARMUP_REQUESTS" \
  -e "H2_MEM_SHAPES=$H2_MEM_SHAPES" \
  -e "H2_MEM_REPEATS=$H2_MEM_REPEATS" \
  -e "H2_MEM_DELAY_S=$H2_MEM_DELAY_S" \
  -- -c '
    set -euo pipefail
    cd /build/mockserver                       # the Maven reactor root (pom.xml lives here, not /build)
    mvn -q -pl mockserver-netty -am install -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true
    cd mockserver-benchmark
    mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Djacoco.skip=true
    CP="target/classes:$(cat target/classpath.txt)"
    # Prove every validation gate fires BEFORE trusting a measured run (server-free, fast).
    java -cp "$CP" org.mockserver.benchmark.Http2StreamChannelBenchmark selftest
    java -cp "$CP" org.mockserver.benchmark.Http2ConnectionMemoryBenchmark selftest
    # The measured sweeps. A gate breach in either exits non-zero and fails the step loudly.
    java -cp "$CP" org.mockserver.benchmark.Http2StreamChannelBenchmark /build/perf-h2-throughput.json
    java -cp "$CP" org.mockserver.benchmark.Http2ConnectionMemoryBenchmark /build/perf-h2-connection-memory.json
    # Merge the throughput ({h2_multiplex:...}) and memory ({h2_connection_memory:...}) objects into the
    # single artifact perf-test-compare.sh persists. Distinct top-level keys, so * is a clean union.
    jq -s ".[0] * .[1]" /build/perf-h2-throughput.json /build/perf-h2-connection-memory.json > /build/perf-h2-multiplex.json
  '

if [ ! -f "$OUT_JSON" ]; then
  echo "ERROR: the HTTP/2 benchmark step did not produce $OUT_JSON" >&2
  exit 1
fi

echo "--- perf-h2-multiplex.json"
cat "$OUT_JSON"

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "perf-h2-multiplex.json" || true
fi

# --- OPTIONAL one-time cross-version comparison (the changelog's whole point) ------------------------------
# Not part of the recurring measurement: it needs two published server images (a pre-8.0.0 and an 8.0.0+),
# which are present on a dev laptop but not on a clean CI agent. Set H2_MEM_COMPARE_IMAGES to two
# space/comma-separated image tags to run it. It drives the SAME external client (the benchmark's `hold`
# mode) against each image, holding the client side constant, and diffs the server container's RSS per
# established connection — so the difference is attributable to the server-side change. Skipped by default;
# never fails the step (it is an investigation, not a gate).
if [ -n "${H2_MEM_COMPARE_IMAGES:-}" ]; then
  echo "--- optional cross-version per-connection RSS comparison: $H2_MEM_COMPARE_IMAGES"
  H2_MEM_COMPARE_IMAGES="$H2_MEM_COMPARE_IMAGES" \
  H2_MEM_COMPARE_SHAPES="${H2_MEM_COMPARE_SHAPES:-1x1 10x10 100x10}" \
  H2_MEM_COMPARE_MEM="${H2_MEM_COMPARE_MEM:-512m}" \
    "$REPO_ROOT/mockserver/mockserver-benchmark/run-h2-connection-memory-compare.sh" \
      || echo "cross-version comparison arm failed (non-fatal)"
fi
