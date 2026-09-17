#!/usr/bin/env bash
set -euo pipefail

# Persist + baseline-compare step (perf queue). Runs post-merge on master, not as
# a PR gate. PER-METRIC GATING: a flagged regression on a metric marked
# `gating:true` fails the build (non-zero exit); a flagged regression on a
# `gating:false` metric is reported exactly as loudly in the annotation but does
# NOT change the exit code (informational-pending-history). This is the
# regression notification: a failing pipeline IS the alert (no webhook / channel).
#
# Flow:
#   1. gather this run's result.json (+ perf-microbench.json) and merge them
#   2. persist to s3://<bucket>/runs/<branch>/<iso>__<sha>.json   (history)
#   3. pull the last N PRIOR runs; if < MIN_BASELINE, annotate "warming up"
#   4. per metric: rolling baseline = median + MAD; flag a regression when the
#      head value crosses max(median + 3·1.4826·MAD, percent-floor / abs-floor).
#      The per-metric dir / min_pct / abs-floor / gating flags are NOT hardcoded
#      here — they are read from the committed, reviewed
#      mockserver-performance-test/perf-budgets.json, so a floor can only be
#      loosened by a reviewed diff. The annotation names that file's last-changed
#      commit. FAIL CLOSED: a missing/corrupt budget file, an unknown budget
#      provenance commit, or a run metric with no budget entry goes RED (exit 1).
#   5. post a Buildkite annotation table; exit non-zero iff a GATING metric flagged
#
# Only metrics with a derived, trustworthy budget start gating (JMH micro-benchmark
# time/alloc per op, and forward.error_rate — a discriminating pass/fail guard).
# Every other metric (k6 latency percentiles, growth ratios, peak_achieved_rps,
# live_set_bytes) runs notify-only until it has >=10 clean runs of history and a
# budget derived from them — a gate that fires on noise gets switched off, which is
# the failure mode this design avoids. See docs/plans/performance-programme.md item 1.
#
# Robust stats (median/MAD, not mean/stddev) so a single noisy run doesn't move
# the baseline. Latency/CPU/heap/alloc: higher = worse. Throughput: lower = worse.
# Growth slope ratios also get an ABSOLUTE floor (healthy ≈ 1.0) so steady-state
# badness isn't normalised away. Micro-benchmark uses a tighter floor (low noise).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

BUCKET="${PERF_RESULTS_BUCKET:-mockserver-ci-perf-results}"
BASELINE_N="${PERF_BASELINE_N:-10}"
MIN_BASELINE="${PERF_MIN_BASELINE:-5}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-compare.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

annotate() { # style, body
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' "$2" | buildkite-agent annotate --style "$1" --context perf-regression || true
  fi
  printf '\n%s\n' "$2"
}

# --- 0. load committed budget floors ------------------------------------------
# The absolute floors used by the compare below live in a committed, reviewed
# JSON file (mockserver-performance-test/perf-budgets.json), NOT hardcoded in
# this jq, so a floor can only be loosened by a reviewed diff. FAIL CLOSED: a
# missing or unparseable budget file, or a run metric with no budget entry, must
# go RED — a silent default of "no floor" would be a false green. See
# docs/plans/performance-programme.md -> "Budgets that ratchet".
BUDGETS_FILE="${PERF_BUDGETS_FILE:-$REPO_ROOT/mockserver-performance-test/perf-budgets.json}"
if [ ! -f "$BUDGETS_FILE" ]; then
  annotate "error" ":no_entry: **Perf budget file MISSING — cannot compare** — expected \`${BUDGETS_FILE}\`.

The committed absolute floors could not be found, so the regression gate cannot run. This fails the build deliberately (fail-closed): a missing budget must never silently mean \"no floor\"."
  exit 1
fi
if ! jq empty "$BUDGETS_FILE" >/dev/null 2>&1; then
  annotate "error" ":no_entry: **Perf budget file UNPARSEABLE — cannot compare** — \`${BUDGETS_FILE}\` is not valid JSON.

This fails the build deliberately (fail-closed): a corrupt budget file must never silently mean \"no floor\"."
  exit 1
fi
if [ "$(jq -r '(.budgets | type) // "null"' "$BUDGETS_FILE" 2>/dev/null)" != "object" ]; then
  annotate "error" ":no_entry: **Perf budget file has no \`budgets\` object — cannot compare** — \`${BUDGETS_FILE}\`.

This fails the build deliberately (fail-closed): the floors could not be read."
  exit 1
fi

# Name the budget file's last-changed commit in the annotation so a silent
# loosening is visible in the build output, not only in git history. If git
# metadata is unavailable (not a checkout) or the file is uncommitted, we CANNOT
# prove the floors were reviewed, so FAIL LOUDLY rather than print a blank or a
# misleading provenance. Override via PERF_BUDGETS_COMMIT for local testing only.
BUDGETS_COMMIT="${PERF_BUDGETS_COMMIT:-}"
if [ -z "$BUDGETS_COMMIT" ]; then
  BUDGETS_COMMIT="$(git -C "$REPO_ROOT" log -1 --format=%H -- "$BUDGETS_FILE" 2>/dev/null || true)"
fi
if [ -z "$BUDGETS_COMMIT" ]; then
  annotate "error" ":no_entry: **Perf budget provenance UNKNOWN — cannot vouch for the floors** — \`git log\` reported no commit for \`${BUDGETS_FILE}\` (not a git checkout, or the file is uncommitted).

The annotation must name the budget file's last-changed commit so a silent loosening is visible. Without it the floors cannot be proven reviewed, so this fails the build deliberately (fail-closed). Set \`PERF_BUDGETS_COMMIT\` only for local testing."
  exit 1
fi

# --- 1. gather this run's result ----------------------------------------------
RESULT="$WORK/result.json"
if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact download perf-result.json "$WORK/" || { echo "ERROR: no perf-result.json artifact" >&2; exit 0; }
  cp "$WORK/perf-result.json" "$RESULT"
  buildkite-agent artifact download perf-microbench.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-microbench-extra.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-sweep.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-scaling.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-h2-multiplex.json "$WORK/" 2>/dev/null || true
else
  cp "${PERF_RESULT_FILE:-$REPO_ROOT/perf-result.json}" "$RESULT"
  [ -f "$REPO_ROOT/perf-microbench.json" ] && cp "$REPO_ROOT/perf-microbench.json" "$WORK/perf-microbench.json" || true
  [ -f "$REPO_ROOT/perf-microbench-extra.json" ] && cp "$REPO_ROOT/perf-microbench-extra.json" "$WORK/perf-microbench-extra.json" || true
  [ -f "$REPO_ROOT/perf-sweep.json" ] && cp "$REPO_ROOT/perf-sweep.json" "$WORK/perf-sweep.json" || true
  [ -f "$REPO_ROOT/perf-scaling.json" ] && cp "$REPO_ROOT/perf-scaling.json" "$WORK/perf-scaling.json" || true
  [ -f "$REPO_ROOT/perf-h2-multiplex.json" ] && cp "$REPO_ROOT/perf-h2-multiplex.json" "$WORK/perf-h2-multiplex.json" || true
fi
# Merge micro-benchmark results into the run object if present.
if [ -f "$WORK/perf-microbench.json" ]; then
  jq -s '.[0] * .[1]' "$RESULT" "$WORK/perf-microbench.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
fi
# Merge the promoted dark-benchmark results (item 15b) — same shape, under
# `microbench_extra`. Consumed NON-GATING below (notify-only: no baseline history
# yet), so a regression on one annotates but does NOT fail the build.
if [ -f "$WORK/perf-microbench-extra.json" ]; then
  jq -s '.[0] * .[1]' "$RESULT" "$WORK/perf-microbench-extra.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
fi
# Persist the throughput-vs-latency sweep + JMH scaling sweep into the stored run
# so the S3 history keeps them. NOTIFY-ONLY: these are recorded for the doc-site
# knee/scaling charts and ad-hoc inspection — there is no baseline comparison or
# pass/fail gate on them (the regression compare below is unchanged). perf-sweep
# is also already embedded under .sweep by perf-test-run.sh; re-merging the
# standalone artifact is harmless (same object) and robust if the embed is absent.
if [ -f "$WORK/perf-sweep.json" ]; then
  jq -s '.[0] + {sweep: .[1]}' "$RESULT" "$WORK/perf-sweep.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
fi
if [ -f "$WORK/perf-scaling.json" ]; then
  jq -s '.[0] * .[1]' "$RESULT" "$WORK/perf-scaling.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
fi
# HTTP/2 multiplex benchmark (issue #2669). Persisted into the S3 run history for
# trend visibility only — NOTIFY-ONLY, NO baseline comparison or pass/fail gate
# (its own harness self-validation already fails the run step loudly on a bad
# measurement; run-to-run variance on real agents is not yet known, so setting a
# regression threshold now would be guessing). The `metrics` jq below intentionally
# does not read `.h2_multiplex`, so it is recorded but never flagged.
if [ -f "$WORK/perf-h2-multiplex.json" ]; then
  jq -s '.[0] * .[1]' "$RESULT" "$WORK/perf-h2-multiplex.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
fi

BRANCH="$(jq -r '.branch // "unknown"' "$RESULT")"
COMMIT="$(jq -r '.commit // "unknown"' "$RESULT")"
TS="$(jq -r '.timestamp_utc // "unknown"' "$RESULT")"
KEY="runs/${BRANCH}/${TS//:/-}__${COMMIT:0:10}.json"

# --- 1b. validity gate (item: refuse to baseline a compromised measurement) ---
# The run's own `validity` block records whether the measurement RIG was sound
# (client had CPU headroom, k6 dropped no iterations, resource samples captured,
# latency metrics present). Baselining an invalid run would poison the rolling
# median with garbage, so REFUSE to persist or compare it. A run produced by
# perf-test-run.sh always carries the block; an ABSENT block (older producer or a
# truncated run) is treated as invalid on purpose.
#
# FAIL THE BUILD (exit 1), do not exit 0. Rationale (asked to decide + justify):
# the user's model is "a failing pipeline IS the notification, checked regularly",
# so a GREEN daily build asserts "measured, and OK". An invalid run measured
# nothing trustworthy — a green square there is a false green (the exact failure
# mode this programme keeps finding), and it is consistent with perf-test-run.sh
# already refusing (exit 1) to emit an unrecordable config and with item 0's
# principle of never emitting a result that misrepresents what it measured. The
# counter-argument — a transient rig blip (a noisy cloud neighbour dropping k6
# iterations) fires an unactionable red — is real but weaker here: this is the
# DAILY run, not a per-commit gate; agent-loss/Spot transients are already caught
# by the pipeline's exit -1/255 auto-retry (a DIFFERENT signal from rig-invalidity);
# and a validity failure that recurs IS actionable (the rig needs attention). The
# silent-staleness a green invalid run causes is worse than a loud, investigable red.
VALID="$(jq -r '.validity.valid // "absent"' "$RESULT")"
if [ "$VALID" != "true" ]; then
  FAILED_CHECKS="$(jq -r '
    (.validity.checks // []) | map(select(.ok != true))
    | if length == 0 then "- (no validity block present in this run)"
      else map("- **\(.name)**: \(.detail)") | join("\n") end' "$RESULT")"
  annotate "error" ":no_entry: **Perf run INVALID — build FAILED, not baselined** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

This run's \`validity\` block is absent or false, so it was **not persisted to the baseline history and not compared** — a compromised measurement must not poison the rolling median (the inject harness's discipline: exclude a bad point, don't report it). **This fails the build** so a run that could not measure anything is loud, not a green square that misrepresents it as OK.

Failing checks:
${FAILED_CHECKS}"
  exit 1
fi

# --- 2. persist this run to S3 (history) --------------------------------------
# FATAL on failure. A failed S3 write used to only WARN and continue, leaving the
# build GREEN having stored nothing: the baseline then silently stops refreshing
# while every later comparison reports OK against an ever-staler window — invisible
# rot, the worst kind of false green. A run that could not be recorded must go RED so
# the storage failure is investigated, not accumulate silently.
HAVE_AWS=false
if command -v aws >/dev/null 2>&1 && [ -z "${PERF_BASELINE_DIR:-}" ]; then
  HAVE_AWS=true
  if ! aws s3 cp "$RESULT" "s3://${BUCKET}/${KEY}" --only-show-errors; then
    annotate "error" ":no_entry: **Perf run NOT persisted — build FAILED** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

Writing this run to \`s3://${BUCKET}/${KEY}\` failed, so it was **not stored in the baseline history**. Left green, the baseline would stop refreshing while comparisons kept reporting OK against a stale window (silent rot). This fails the build deliberately so the storage failure is fixed, not accumulated. Check S3 permissions / bucket / connectivity from the perf agent."
    exit 1
  fi
fi

# --- 2b. laptop profile PRESENCE assertion (item 8) ---------------------------
# Two axes, exactly as 15b separated them: laptop VALUES stay notify-only, but
# laptop PRESENCE is loud. `laptop_attempted:true` means the producer tried to
# measure the profile; if `.laptop` is then empty or missing a docker sub-item, the
# measurement failed wholesale (renamed image, docker/create error, producer
# traceback) and would otherwise vanish as a green build — compare is head-driven,
# so zero laptop metrics trip nothing. Unlike microbench_extra (whose drift is caught
# upstream by perf-test-microbench.sh asserting an exact row count), the laptop
# producer only warns to stderr, so THIS is the equivalent catch. The docker
# sub-items have NO JDK dependency, so their absence is unambiguously a rig failure
# and safe to RED on. Runs AFTER the S3 persist above, so a laptop failure never
# costs the k6 result its place in the baseline history. A run with no
# `laptop_attempted` (profile disabled, or an older producer) is exempt.
LAPTOP_INJVM_NOTE=""
if [ "$(jq -r '.laptop_attempted // false' "$RESULT")" = "true" ]; then
  MISSING_DOCKER="$(jq -r '(["docker_ready","mem_256m","mem_512m","mem_1g","image"] - ((.laptop // {}) | keys)) | join(", ")' "$RESULT")"
  if [ -n "$MISSING_DOCKER" ]; then
    annotate "error" ":no_entry: **Laptop profile FAILED to produce — build FAILED** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

The run set \`laptop_attempted: true\` but its \`.laptop\` block is missing docker sub-item(s): **${MISSING_DOCKER}**. These have no JDK dependency, so their absence means the laptop measurement (\`bench_laptop.py all\`) failed wholesale — a renamed image, a \`docker create\`/\`docker run\` error, or a producer traceback. Compare is head-driven, so a missing block would otherwise emit zero laptop metrics and pass GREEN with no signal — the false green this gate exists to stop (the programme's thesis: a failing pipeline IS the alert). The run itself was still persisted to the baseline history above, so the k6 result is not lost. Notify-only laptop VALUES are unaffected — this gate is about PRESENCE, not regression."
    exit 1
  fi
  # in-JVM sub-items (injvm/init_*) additionally need a JDK + the image-extracted
  # jar, so they are best-effort: surface their absence VISIBLY in the annotation
  # (a stderr note nobody reads is not surfacing it), but do NOT fail the build.
  MISSING_INJVM="$(jq -r '(["injvm","init_0","init_1000","init_10000"] - ((.laptop // {}) | keys)) | join(", ")' "$RESULT")"
  if [ -n "$MISSING_INJVM" ]; then
    LAPTOP_INJVM_NOTE="

:information_source: **Laptop in-JVM sub-items skipped this run** — \`${MISSING_INJVM}\` absent. \`bench_laptop.py\` skips 8b/8c when a JDK or the image-extracted jar is unavailable on the perf agent; the docker sub-items were present, so this is a best-effort skip, not a failure — but the in-JVM start figure (what a MockServerExtension suite pays per test class) is missing this run."
    echo "$LAPTOP_INJVM_NOTE"
  fi
fi

# --- 3. pull the last N PRIOR runs --------------------------------------------
BASE_DIR="$WORK/baseline"; mkdir -p "$BASE_DIR"
if [ -n "${PERF_BASELINE_DIR:-}" ]; then
  cp "$PERF_BASELINE_DIR"/*.json "$BASE_DIR/" 2>/dev/null || true
elif $HAVE_AWS; then
  # List, drop the just-uploaded current key, take the most recent N by name.
  # grep -vxF: exact whole-line fixed-string match (the key has dots — a plain
  # regex grep would treat them as wildcards and over-exclude).
  mapfile -t KEYS < <(aws s3 ls "s3://${BUCKET}/runs/${BRANCH}/" --recursive 2>/dev/null | awk '{print $4}' | grep -vxF "$KEY" | sort | tail -n "$BASELINE_N")
  for k in "${KEYS[@]:-}"; do
    [ -n "$k" ] || continue
    aws s3 cp "s3://${BUCKET}/${k}" "$BASE_DIR/$(basename "$k")" --only-show-errors 2>/dev/null || true
  done
fi

BASE_COUNT="$(find "$BASE_DIR" -name '*.json' | wc -l | tr -d ' ')"
echo "--- baseline: $BASE_COUNT prior run(s) (min $MIN_BASELINE, window $BASELINE_N)"

if [ "$BASE_COUNT" -lt "$MIN_BASELINE" ]; then
  annotate "info" ":hourglass_flowing_sand: **Perf baseline warming up** — ${BASE_COUNT}/${MIN_BASELINE} runs collected. Persisted this run (\`${COMMIT:0:10}\`); regression comparison starts once ${MIN_BASELINE} runs exist.${LAPTOP_INJVM_NOTE}"
  exit 0
fi

# --- 4. compare (median + MAD) ------------------------------------------------
jq -s '.' "$BASE_DIR"/*.json > "$WORK/baseline.json"

# Honest history (item: handle the existing history honestly). Every run stored
# before the self-describing-result change has no `config` block (schema_version
# < 2) and an unusable instance_type, so we CANNOT confirm it was configured like
# this run (same JVM/GC/heap/log-level/hardware). Do NOT silently fold such runs
# into the rolling baseline as though comparable — count them so the annotation can
# flag that the comparison spans a configuration boundary. No backfill is attempted.
PRE_CONFIG_COUNT="$(jq '[ .[] | select((.config == null) or ((.schema_version // 1) < 2)) ] | length' "$WORK/baseline.json")"

# Compact provenance line from THIS run's config block — the version/JDK/GC/heap/
# log-level/hardware the figures were produced under (what the website provenance
# line and the hardware-invalidation rule both need a run to carry).
PROVENANCE="$(jq -r '
  (.config // null) as $c
  | if $c == null then "_No config block on this run (schema_version \(.schema_version // 1) — pre self-describing)._"
    else "**Run config** — MockServer \($c.mockserver_version // "?") · JDK \($c.jdk // "?") · GC \($c.gc // "?") · heap_max \((((($c.heap_max_bytes // 0)) / 1048576) | floor)) MiB · log_level \($c.log_level // "?") · disable_system_out \($c.disable_system_out // "?") · instance \(.agent.instance_type // "?") · image \($c.image_digest // "?")"
    end' "$RESULT" 2>/dev/null || echo "")"
PRECFG_NOTE=""
if [ "${PRE_CONFIG_COUNT:-0}" -gt 0 ]; then
  PRECFG_NOTE="

:warning: **${PRE_CONFIG_COUNT} of ${BASE_COUNT} baseline run(s) predate the self-describing result (no \`config\` block).** Those runs cannot be confirmed to share this run's JVM / GC / heap / log level / hardware, so the comparison above crosses a configuration boundary — weigh flagged metrics accordingly and re-derive the baseline once ${BASELINE_N} config-bearing runs exist."
fi

# jq program (single-quoted on purpose — $vars are jq vars, not shell).
# shellcheck disable=SC2016
COMPARE='
def fabs: if . < 0 then -. else . end;
def median: sort | length as $n | if $n==0 then null elif ($n%2==1) then .[($n/2|floor)] else (.[$n/2-1]+.[$n/2])/2 end;
def mad($m): map((. - $m)|fabs) | median;

# Flat list of comparable metrics for one run object. Each metric carries a
# `bkey` (budget key) resolved against $budgets (loaded from the committed
# perf-budgets.json). The tail below folds in that entry dir / min_pct /
# floor / gating; a run metric whose bkey is absent from $budgets is a fail-
# closed error (see the tail of this program and the MISSING_COUNT check). The
# wildcard bkeys `behaviours.*.<m>` / `microbench.*.<m>` cover every runtime
# behaviour / benchmark key of that shape. Rationale for WHY each metric gates or
# is notify-only, and how its floor was chosen, now lives in perf-budgets.json.
def metrics:
  ((.behaviours // {}) | to_entries[] | .key as $k | .value as $v |
    # k6 latency percentiles + per-behaviour error_rate. throughput_rps is
    # DELIBERATELY not budgeted (recorded with offered_rps + dropped_iterations +
    # delivery_ratio alongside): a shortfall below offered is AMBIGUOUS (server
    # slower vs client VU-starved). peak_achieved_rps (below) is the ceiling signal.
    ( {name:($k+".p95_ms"),     value:$v.p95_ms,     bkey:"behaviours.*.p95_ms"},
      {name:($k+".p99_ms"),     value:$v.p99_ms,     bkey:"behaviours.*.p99_ms"},
      {name:($k+".error_rate"), value:$v.error_rate, bkey:"behaviours.*.error_rate"} ) ),
  ((.growth // {}) |
    # cpu_ratio/heap_ratio detect a SLOPE; live_set_bytes detects a STEP a plateaued
    # leak (ratio ~1.0) would hide. p95_ratio is the noisier latency slope.
    ( {name:"growth.cpu_ratio",      value:(.cpu_pct.ratio),                   bkey:"growth.cpu_ratio"},
      {name:"growth.heap_ratio",     value:(.heap_used_bytes.ratio),           bkey:"growth.heap_ratio"},
      {name:"growth.live_set_bytes", value:(.heap_used_bytes.min_last_window), bkey:"growth.live_set_bytes"},
      {name:"growth.p95_ratio",      value:(.p95_ms.ratio),                    bkey:"growth.p95_ratio"} ) ),
  ((.microbench // {}) | to_entries[] | .key as $k | .value as $v |
    # JMH micro-benchmarks: deterministic and hardware-independent (forked JVM,
    # steady-state, low run-to-run noise), the strongest signal in the repo.
    ( {name:($k+".time_per_op"),        value:$v.time_per_op,        bkey:"microbench.*.time_per_op"},
      {name:($k+".alloc_bytes_per_op"), value:$v.alloc_bytes_per_op, bkey:"microbench.*.alloc_bytes_per_op"} ) ),
  ((.microbench_extra // {}) | to_entries[] | .key as $k | .value as $v |
    # Promoted dark benchmarks (item 15b): identical JMH shape to .microbench, but a
    # SEPARATE budget key so they can be NOTIFY-ONLY while .microbench gates. They
    # have no baseline history yet; their perf-budgets.json entries omit `gating`
    # (-> non-gating), so a flagged regression annotates just as loudly but does NOT
    # fail the build. NOTE: this compare only iterates HEAD metrics, so it CANNOT
    # detect a benchmark that drift silently drops (a baseline-has-key / head-lacks-it
    # gap) — a dropped row just disappears here unnoticed. Drift is caught upstream in
    # the producer (perf-test-microbench.sh), which asserts the EXACT expected row
    # count (EXTRA_EXPECTED) and fails the step on a partial vanish.
    ( {name:($k+".time_per_op"),        value:$v.time_per_op,        bkey:"microbench_extra.*.time_per_op"},
      {name:($k+".alloc_bytes_per_op"), value:$v.alloc_bytes_per_op, bkey:"microbench_extra.*.alloc_bytes_per_op"} ) ),
  ((.laptop // {}) | to_entries[] | .key as $k | .value as $v |
    # Laptop startup / footprint profile (item 8), keyed by variant: docker_ready,
    # injvm, mem_256m/512m/1g, init_0/1000/10000, image. Each variant carries only the
    # subset of these metrics it measures; the others are null and drop out via the
    # `select(.value != null)` in $headmetrics (e.g. docker_ready emits no rss_mb,
    # image emits only compressed_bytes). dir:"up" throughout — a slower start, larger
    # idle RSS, more threads, or a bigger image are all worse. NOTIFY-ONLY: the
    # laptop.*.<metric> budgets omit `gating`, so a flag annotates but NEVER fails the
    # build. Their 0.25 min_pct is a WIDE dead band on a laptop-startup baseline — a
    # backstop against a gross loss (AppCDS/warmup), not a fine regression signal. It
    # flags informationally once >=1 prior point exists (laptop.* takes the `else`
    # branch below, $minreq=1, subject to the global BASE_COUNT >= MIN_BASELINE warm-up)
    # and never fails the build. laptop.* rides that `else` (full baseline) branch like
    # growth/peak — not the fingerprint-filtered microbench/behaviours buckets — because these metrics have
    # no methodology fingerprint of their own and are deliberately NOT keyed on the image
    # digest (which changes every snapshot rebuild — the reset trap the k6 unit avoided).
    ( {name:($k+".ready_ms"),         value:$v.ready_ms,         bkey:"laptop.*.ready_ms"},
      {name:($k+".cold_ready_ms"),    value:$v.cold_ready_ms,    bkey:"laptop.*.cold_ready_ms"},
      {name:($k+".rss_mb"),           value:$v.rss_mb,           bkey:"laptop.*.rss_mb"},
      {name:($k+".threads"),          value:$v.threads,          bkey:"laptop.*.threads"},
      {name:($k+".compressed_bytes"), value:$v.compressed_bytes, bkey:"laptop.*.compressed_bytes"} ) ),
  # peak_achieved_rps: max achieved throughput across sweep rungs where the k6
  # CLIENT was sound. CONTINUOUS, so a relative floor is meaningful. saturation_rps
  # (the knee) is ladder-QUANTISED, so it is recorded but NOT budgeted here.
  ( {name:"peak_achieved_rps", value:(.peak_achieved_rps), bkey:"peak_achieved_rps"} ),
  # forward.error_rate: the forward connection-pool guard (forward.js) — a
  # discriminating pass/fail guard (pool works vs it does not).
  ( {name:"forward.error_rate", value:((.forward_guard // {}).error_rate), bkey:"forward.error_rate"} );

# Build a {name: [values]} baseline map from a set of runs. `add // []` guards the
# EMPTY-set case (e.g. no baseline run matches the head JMH config): [] | add is null,
# and iterating null throws — an empty run set must yield an empty map, not an error.
def bmapof($runs): (($runs | map([metrics]) | add) // [] | map(select(.value != null))
  | group_by(.name) | map({key:.[0].name, value:[.[].value]}) | from_entries);

# JMH methodology fingerprint of the HEAD run (item 15c baseline-discontinuity guard).
# microbench / microbench_extra metrics are only comparable against baseline runs
# measured under the SAME .config.jmh — a methodology change (e.g. -f1 6s warmup ->
# -f2 4s warmup) can shift absolute timings, which against a differently-measured
# baseline would fire a SPURIOUS gating regression. So the microbench baseline is the
# subset of runs whose fingerprint matches head; a methodology change self-invalidates
# its own baseline (those metrics take the no-baseline branch until history repopulates
# under the new config), and NO false red fires. Historical runs (no .config.jmh, null)
# never match a new fingerprinted head, so they drop out cleanly. All OTHER metrics
# (k6/growth/rps) keep using the full baseline — this filter is microbench-only.
(.config.jmh // null) as $headjmh
# k6 methodology fingerprint (the k6-side analogue of the JMH .config.jmh guard
# above). The k6 behaviour arms are only comparable against baseline runs measured
# under the SAME methodology, and for k6 that signature is the SET OF CONCURRENTLY-
# RUNNING ARMS (the sorted .behaviours keys) — and nothing else. Two reasons this
# is the right and ONLY key:
#   1. It captures the change that actually broke comparability: five arms
#      (template_mustache/javascript + large_1mb/10mb/file) were added that contend
#      on the core-limited SUT and shift the latencies of the historical arms
#      (match/forward/template/large). Comparing the new 9-arm mix against a
#      baseline measured under the old 4-arm mix is a silently-incomparable baseline
#      (the false-green class the JMH fingerprint fixes; the asymmetry the review
#      flagged).
#   2. It ALREADY encodes the image variant: the JavaScript and file arms exist iff
#      the -graaljs / file-body capability is enabled, so stock-vs-graaljs is
#      implicit in the arm set. It must NOT also key on .config.image_digest — that
#      is the RepoDigest of the MUTABLE mockserver-snapshot-graaljs tag, which
#      changes on every snapshot rebuild (≈ every master merge). The daily/dispatched
#      perf job always pulls the freshest snapshot, so the head digest would match
#      ZERO prior runs, permanently emptying the k6 baseline and turning every arm into a
#      perpetual :new: that never gates — resetting PRECISELY when app code changes,
#      the event these arms exist to catch. (Pre-fix runs also stored a bare image ID,
#      not a RepoDigest, so they could never match either.) The arm set is stable
#      run-to-run: it performs the intended ONE-TIME reset across the 4->9-arm
#      transition, re-arms after MIN_BASELINE matching runs, and then compares
#      straight across snapshot rebuilds (the whole point of a baseline).
# This filter is behaviours-only. growth/sweep/forward are single-arm scenarios not
# affected by the regression arm mix, so they keep the FULL baseline and DO compare
# across the stock->graaljs image change unfiltered — acceptable because the extra
# GraalJS jars are inert for non-JS paths (loaded lazily only when a JS template
# renders): growth.* and peak_achieved_rps are non-gating, and forward.error_rate
# (gating) is a connection-pool guard the template engine cannot influence.
| ((.behaviours // {}) | keys | sort) as $headarms
| $baseline as $ballruns
| [ $ballruns[] | select((.config.jmh // null) == $headjmh) ] as $bmicroruns
| [ $ballruns[] | select(((.behaviours // {}) | keys | sort) == $headarms) ] as $bk6runs
| bmapof($ballruns) as $bmapAll
| bmapof($bmicroruns) as $bmapMicro
| bmapof($bk6runs) as $bmapK6
| ([ [ . | metrics ][] | select(.value != null) ]) as $headmetrics
# FAIL CLOSED: any run metric with a non-null value whose budget key is absent
# from the committed perf-budgets.json is reported as `missing` (the bash caller
# turns a non-empty `missing` into a red build). A missing budget must never be
# silently skipped — that would default the metric to "no floor".
| ([ $headmetrics[] | select(($budgets[.bkey]) == null) | {name:.name, bkey:.bkey} ]) as $missing
| { baseline_total: ($ballruns|length),
    baseline_microbench_comparable: ($bmicroruns|length),
    baseline_k6_comparable: ($bk6runs|length),
    head_jmh_present: ($headjmh != null),
    head_k6fp_present: (($headarms | length) > 0) } as $meta
| if ($missing | length) > 0
  then ($meta + { missing:$missing, rows:[], count:0, gating_count:0, nongating_count:0 })
  else
    [ $headmetrics[]
      | . as $m0
      | ($budgets[$m0.bkey]) as $b
      # gating is OPTIONAL in a budget entry: an omitted `gating` means notify-only
      # (a flag annotates but does not fail the build), so default it to false.
      | ($m0 + {dir:$b.dir, min_pct:$b.min_pct, floor:$b.floor, gating:($b.gating // false)}) as $m
      # microbench(_extra) compare only against JMH-config-matching baselines;
      # behaviours (k6 arms) compare only against k6-fingerprint-matching baselines
      # (same image + arm set). growth/sweep/forward keep the full baseline.
      | (if ($m.bkey|startswith("microbench")) then ($bmapMicro[$m.name] // [])
         elif ($m.bkey|startswith("behaviours")) then ($bmapK6[$m.name] // [])
         else ($bmapAll[$m.name] // []) end) as $bv
      # Minimum comparable runs before a THRESHOLD is computed. A config/fingerprint
      # reset leaves the filtered baseline with 1..MIN_BASELINE-1 points, where MAD~0
      # collapses the threshold to median*(1+min_pct) and a single >min_pct excursion
      # would fire a SPURIOUS flag (a gating red for microbench; a misleading
      # notify-only flag for the non-gating behaviours). So BOTH the config-filtered
      # families — microbench and behaviours — require the FULL MIN_BASELINE of
      # fingerprint-matching runs before comparison resumes, matching the global
      # BASE_COUNT warm-up (which keys off the UNFILTERED baseline and so does NOT
      # cover these filtered subsets). The remaining metrics (growth/sweep/forward)
      # are never fingerprint-filtered and keep the existing >=1 behaviour.
      | (if ($m.bkey|startswith("microbench")) then $minbaseline
         elif ($m.bkey|startswith("behaviours")) then $minbaseline
         else 1 end) as $minreq
      | if ($bv|length) < $minreq then {name:$m.name, head:$m.value, gating:$m.gating, status:"no-baseline"}
        else
          ($bv|median) as $med
          | (($bv|mad($med)) * 1.4826) as $sigma
          | (if $m.dir=="up"
              then (if $m.floor!=null then ([$med + 3*$sigma, $m.floor]|max)
                    else ([$med + 3*$sigma, $med*(1+$m.min_pct)]|max) end) as $th
                   | {name:$m.name, head:$m.value, baseline:$med, threshold:$th,
                      gating:$m.gating, regression: ($m.value > $th)}
              else (if $m.floor!=null then ([$med - 3*$sigma, $m.floor]|min)
                    else ([$med - 3*$sigma, $med*(1-$m.min_pct)]|min) end) as $th
                   | {name:$m.name, head:$m.value, baseline:$med, threshold:$th,
                      gating:$m.gating, regression: ($m.value < $th)} end)
        end ] as $rows
    | ($meta + { missing: [],
        count: ([$rows[]|select(.regression==true)]|length),
        gating_count: ([$rows[]|select(.regression==true and .gating==true)]|length),
        nongating_count: ([$rows[]|select(.regression==true and .gating!=true)]|length),
        rows: $rows })
  end
'
RESULT_CMP="$(jq -n \
  --slurpfile baselineFile "$WORK/baseline.json" \
  --slurpfile runFile "$RESULT" \
  --slurpfile budgetsFile "$BUDGETS_FILE" \
  --argjson minbaseline "$MIN_BASELINE" \
  '($baselineFile[0]) as $baseline | ($budgetsFile[0].budgets) as $budgets | ($runFile[0]) | '"$COMPARE")"

# FAIL CLOSED: a run metric with no budget entry must go RED, not be skipped.
MISSING_COUNT="$(printf '%s' "$RESULT_CMP" | jq -r '.missing | length')"
if [ "$MISSING_COUNT" -gt 0 ]; then
  MISSING_LIST="$(printf '%s' "$RESULT_CMP" | jq -r '.missing[] | "- **\(.name)** (budget key `\(.bkey)`)"')"
  annotate "error" ":no_entry: **Perf budget INCOMPLETE — cannot compare** — ${MISSING_COUNT} metric(s) in this run have no entry in \`$(basename "$BUDGETS_FILE")\` (commit \`${BUDGETS_COMMIT:0:10}\`):

${MISSING_LIST}

This fails the build deliberately (fail-closed): a metric with no committed budget must never be silently skipped. Add a budget entry (a reviewed diff) or remove the metric from the run producer."
  exit 1
fi

COUNT="$(printf '%s' "$RESULT_CMP" | jq -r '.count')"
GATING_COUNT="$(printf '%s' "$RESULT_CMP" | jq -r '.gating_count')"
NONGATING_COUNT="$(printf '%s' "$RESULT_CMP" | jq -r '.nongating_count')"

# microbench baseline discontinuity (item 15c): when the head run's JMH methodology
# fingerprint (.config.jmh) differs from some/all baseline runs, only the matching
# subset is comparable for microbench(_extra) metrics. Surface that VISIBLY — a
# skipped comparison that looks like a clean pass is the false green we avoid.
MB_COMPARABLE="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_microbench_comparable // 0')"
BASE_TOTAL_CMP="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_total // 0')"
HEAD_JMH_PRESENT="$(printf '%s' "$RESULT_CMP" | jq -r '.head_jmh_present // false')"
MB_NOTE=""
if [ "$HEAD_JMH_PRESENT" = "true" ] && [ "$MB_COMPARABLE" -lt "$BASE_TOTAL_CMP" ]; then
  MB_NOTE="

:information_source: **microbench baseline reset — JMH methodology changed.** Only ${MB_COMPARABLE}/${BASE_TOTAL_CMP} baseline run(s) were measured under this run's JMH config (\`.config.jmh\`), so \`microbench.*\` / \`microbench_extra.*\` metrics compare ONLY against those (other metrics use the full baseline). Those metrics stay \`:new: new\` (no comparable baseline, NOT flagged) until at least ${MIN_BASELINE} config-matching runs exist — the same warm-up threshold the global baseline uses — so a methodology change self-invalidates its own baseline and no spurious gating regression fires against a differently-measured baseline (or against a 1..$((MIN_BASELINE-1))-point window where MAD~0 would collapse the threshold to the bare floor). Gating resumes once ${MIN_BASELINE} comparable runs have accrued."
fi

# k6 behaviour baseline discontinuity (MAJOR): when the head run's k6 methodology
# fingerprint (the SET OF BEHAVIOUR ARMS) differs from some/all baseline runs, only
# the matching subset is comparable for the k6 behaviour metrics. Surface that
# VISIBLY for the same reason — a skipped comparison that reads as a clean pass is
# the false green we avoid. NOTE the accuracy caveat: the behaviour metrics are
# NOTIFY-ONLY (they never fail the build), so what the reset prevents is a MISLEADING
# informational flag against an incomparable baseline, not a gating red.
K6_COMPARABLE="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_k6_comparable // 0')"
HEAD_K6FP_PRESENT="$(printf '%s' "$RESULT_CMP" | jq -r '.head_k6fp_present // false')"
K6_NOTE=""
if [ "$HEAD_K6FP_PRESENT" = "true" ] && [ "$K6_COMPARABLE" -lt "$BASE_TOTAL_CMP" ]; then
  K6_NOTE="

:information_source: **k6 behaviour baseline reset — arm set changed.** Only ${K6_COMPARABLE}/${BASE_TOTAL_CMP} baseline run(s) share this run's set of behaviour arms, so the per-behaviour \`*.p95_ms\` / \`*.p99_ms\` / \`*.error_rate\` metrics compare ONLY against those (growth / sweep / forward keep the full baseline). They stay \`:new: new\` (no comparable baseline, NOT flagged) until at least ${MIN_BASELINE} arm-set-matching runs exist — the same warm-up threshold the global baseline uses — so adding or removing an arm (or switching the -graaljs/file capability, which changes which arms run) self-invalidates the k6 baseline window instead of comparing a different arm mix straight across the discontinuity and producing a misleading (notify-only) flag. The fingerprint is the arm set ALONE, which is stable across snapshot rebuilds, so once ${MIN_BASELINE} matching runs have accrued the metrics re-arm and then compare normally across rebuilds. These metrics are notify-only, so this never blocks the build."
fi

# --- 5. render annotation -----------------------------------------------------
# The Status column distinguishes the two kinds of flagged regression:
#   :red_circle: REGRESSION (fails build)  -> a GATING metric crossed its budget
#   :warning: flagged (informational)      -> a non-gating metric crossed its budget
# and the Gate column states, for every row, whether it can fail the build — so a
# reader sees at a glance which flags are build-failing and which are pending-history.
TABLE="$(printf '%s' "$RESULT_CMP" | jq -r '
  "| Metric | Head | Baseline | Threshold | Gate | Status |\n|---|---:|---:|---:|:--|:--|",
  (.rows[] |
    "| \(.name) | \(.head // "n/a") | \(.baseline // "n/a" | if type=="number" then (.*1000|round)/1000 else . end) | \(.threshold // "n/a" | if type=="number" then (.*1000|round)/1000 else . end) | \(if .gating==true then "gating" else "notify-only" end) | \(if .status=="no-baseline" then ":new: new" elif .regression then (if .gating==true then ":red_circle: REGRESSION (fails build)" else ":warning: flagged (informational)" end) else ":white_check_mark: ok" end) |")')"

# Context appended to the table: which sweep rungs were excluded (and why) so an
# excluded top rung cannot masquerade as a server ceiling, and the per-behaviour
# delivery ratio so a throughput shortfall with dropped_iterations>0 reads as a
# CLIENT limit rather than a server regression.
EXTRA="$(jq -r '
  ([ (.saturation.excluded // [])[]
     | "- offered \(.offered_rps) rps: EXCLUDED — \(.reason)" ]) as $ex
  | ([ ((.behaviours // {}) | to_entries[])
       | select(.value.delivery_ratio != null)
       | "- \(.key): \(.value.throughput_rps)/\(.value.offered_rps) rps (ratio \(.value.delivery_ratio), dropped \(.value.dropped_iterations // 0))" ]) as $dr
  | ((.forward_guard // {}) as $fg
     | if $fg.status == "infra_error"
       then "\n\n:warning: **Forward-pool guard did NOT run this build** (k6 exit \($fg.k6_exit), no error_rate — upstream/container infra error, not a pool breach). The forward.error_rate row is therefore ABSENT, so the pool-exhaustion regression was NOT checked this run — investigate before trusting it."
       else "" end) as $fginfra
  | ((if ($ex|length) > 0 then "\n**Sweep rungs excluded (k6 client not sound — not a server ceiling):**\n" + ($ex|join("\n")) else "" end)
    + (if ($dr|length) > 0 then "\n\n**Delivery ratio** (throughput/offered; a shortfall with dropped>0 is a CLIENT/VU limit, not a server regression):\n" + ($dr|join("\n")) else "" end)
    + $fginfra)
' "$RESULT" 2>/dev/null || echo "")"

# Fold the provenance line, the pre-config-baseline warning, and the microbench +
# k6 baseline-reset notes into the body so every annotation (regression or clean)
# carries them.
EXTRA="${EXTRA}

${PROVENANCE}${PRECFG_NOTE}${MB_NOTE}${K6_NOTE}${LAPTOP_INJVM_NOTE}"

HEADER="Perf regression — \`${COMMIT:0:10}\` on \`${BRANCH}\` (baseline: ${BASE_COUNT} runs, median+MAD; budgets @ \`${BUDGETS_COMMIT:0:10}\`)"
# Legend folded into every flagged annotation so a reader knows why the build did
# (or did not) go red, and how a notify-only metric graduates to gating.
LEGEND="_Gating metrics_ (JMH \`*.time_per_op\` / \`*.alloc_bytes_per_op\`, \`forward.error_rate\`) **fail the build** when flagged. _Notify-only_ metrics are reported just as loudly but do NOT fail the build — they graduate to gating once they have >=10 clean runs of history and a budget derived from them (see docs/plans/performance-programme.md item 1)."

# Exit non-zero ONLY when at least one GATING metric is flagged — that non-zero
# exit is the regression notification (the pipeline goes red). A non-gating flag
# leaves the exit code at 0. Every other exit path above (invalid run, missing
# artifact, warming up) is unchanged and still exits 0.
EXIT_CODE=0
if [ "$GATING_COUNT" -gt 0 ]; then
  EXIT_CODE=1
  NONGATING_NOTE=""
  if [ "$NONGATING_COUNT" -gt 0 ]; then
    NONGATING_NOTE=" (plus ${NONGATING_COUNT} notify-only metric(s) flagged — informational, see table)"
  fi
  annotate "error" ":red_circle: **${GATING_COUNT} build-failing performance regression(s)** — ${HEADER}${NONGATING_NOTE}

${TABLE}
${EXTRA}

**This build FAILS**: a gating metric crossed its budget. Investigate the \`:red_circle:\`-marked metric(s) against recent commits.
${LEGEND}"
elif [ "$COUNT" -gt 0 ]; then
  annotate "warning" ":chart_with_downwards_trend: **${COUNT} performance regression(s) flagged — all notify-only, build NOT failed** — ${HEADER}

${TABLE}
${EXTRA}

_No gating metric was flagged, so this does not fail the build. Investigate the flagged metric(s) against recent commits._
${LEGEND}"
else
  annotate "success" ":white_check_mark: **No performance regressions** — ${HEADER}

${TABLE}
${EXTRA}"
fi

exit "$EXIT_CODE"
