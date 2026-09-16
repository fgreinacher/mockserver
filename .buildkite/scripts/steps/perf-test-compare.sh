#!/usr/bin/env bash
set -euo pipefail

# Persist + baseline-compare step (perf queue). NOTIFY-ONLY: it annotates and
# (optionally) notifies on a regression but NEVER fails the build (these run
# post-merge on master, not as a PR gate).
#
# Flow:
#   1. gather this run's result.json (+ perf-microbench.json) and merge them
#   2. persist to s3://<bucket>/runs/<branch>/<iso>__<sha>.json   (history)
#   3. pull the last N PRIOR runs; if < MIN_BASELINE, annotate "warming up"
#   4. per metric: rolling baseline = median + MAD; flag a regression when the
#      head value crosses max(median + 3·1.4826·MAD, percent-floor / abs-floor)
#   5. post a Buildkite annotation table; exit 0 regardless
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

# --- 1. gather this run's result ----------------------------------------------
RESULT="$WORK/result.json"
if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact download perf-result.json "$WORK/" || { echo "ERROR: no perf-result.json artifact" >&2; exit 0; }
  cp "$WORK/perf-result.json" "$RESULT"
  buildkite-agent artifact download perf-microbench.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-sweep.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-scaling.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-h2-multiplex.json "$WORK/" 2>/dev/null || true
else
  cp "${PERF_RESULT_FILE:-$REPO_ROOT/perf-result.json}" "$RESULT"
  [ -f "$REPO_ROOT/perf-microbench.json" ] && cp "$REPO_ROOT/perf-microbench.json" "$WORK/perf-microbench.json" || true
  [ -f "$REPO_ROOT/perf-sweep.json" ] && cp "$REPO_ROOT/perf-sweep.json" "$WORK/perf-sweep.json" || true
  [ -f "$REPO_ROOT/perf-scaling.json" ] && cp "$REPO_ROOT/perf-scaling.json" "$WORK/perf-scaling.json" || true
  [ -f "$REPO_ROOT/perf-h2-multiplex.json" ] && cp "$REPO_ROOT/perf-h2-multiplex.json" "$WORK/perf-h2-multiplex.json" || true
fi
# Merge micro-benchmark results into the run object if present.
if [ -f "$WORK/perf-microbench.json" ]; then
  jq -s '.[0] * .[1]' "$RESULT" "$WORK/perf-microbench.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
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
# median with garbage, so REFUSE: annotate an error, and do NOT persist to the
# history and do NOT compare. Notify-only overall (exit 0), but loud. A run
# produced by perf-test-run.sh always carries the block; an ABSENT block (older
# producer or a truncated run) is treated as invalid on purpose.
VALID="$(jq -r '.validity.valid // "absent"' "$RESULT")"
if [ "$VALID" != "true" ]; then
  FAILED_CHECKS="$(jq -r '
    (.validity.checks // []) | map(select(.ok != true))
    | if length == 0 then "- (no validity block present in this run)"
      else map("- **\(.name)**: \(.detail)") | join("\n") end' "$RESULT")"
  annotate "error" ":no_entry: **Perf run INVALID — not baselined** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

This run's \`validity\` block is absent or false, so it was **not persisted to the baseline history and not compared** — a compromised measurement must not poison the rolling median (the inject harness's discipline: exclude a bad point, don't report it).

Failing checks:
${FAILED_CHECKS}"
  exit 0
fi

# --- 2. persist this run to S3 (history) --------------------------------------
HAVE_AWS=false
if command -v aws >/dev/null 2>&1 && [ -z "${PERF_BASELINE_DIR:-}" ]; then
  HAVE_AWS=true
  aws s3 cp "$RESULT" "s3://${BUCKET}/${KEY}" --only-show-errors || echo "WARNING: failed to persist run to S3" >&2
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
  annotate "info" ":hourglass_flowing_sand: **Perf baseline warming up** — ${BASE_COUNT}/${MIN_BASELINE} runs collected. Persisted this run (\`${COMMIT:0:10}\`); regression comparison starts once ${MIN_BASELINE} runs exist."
  exit 0
fi

# --- 4. compare (median + MAD) ------------------------------------------------
jq -s '.' "$BASE_DIR"/*.json > "$WORK/baseline.json"

# jq program (single-quoted on purpose — $vars are jq vars, not shell).
# shellcheck disable=SC2016
COMPARE='
def fabs: if . < 0 then -. else . end;
def median: sort | length as $n | if $n==0 then null elif ($n%2==1) then .[($n/2|floor)] else (.[$n/2-1]+.[$n/2])/2 end;
def mad($m): map((. - $m)|fabs) | median;

# Flat list of comparable metrics for one run object.
def metrics:
  ((.behaviours // {}) | to_entries[] | .key as $k | .value as $v |
    ( {name:($k+".p95_ms"),        value:$v.p95_ms,        dir:"up",   min_pct:0.10, floor:null},
      {name:($k+".p99_ms"),        value:$v.p99_ms,        dir:"up",   min_pct:0.10, floor:null},
      # throughput_rps is DELIBERATELY not budgeted (but still recorded, with
      # offered_rps + dropped_iterations + delivery_ratio alongside it). It is
      # count(COMPLETED)/duration, and k6 drops iterations when its VU pool cannot
      # keep up — so a shortfall below offered is AMBIGUOUS (server slower vs client
      # VU-starved) and must not carry a budget until the 4-11% shortfall seen in
      # real runs is understood. peak_achieved_rps (below) is the budgeted ceiling
      # signal; the delivery ratio is surfaced in the annotation instead.
      {name:($k+".error_rate"),    value:$v.error_rate,    dir:"up",   min_pct:0,    floor:0.005} ) ),
  ((.growth // {}) |
    ( # CPU/heap at constant load should hold ~1.0 → tight absolute floor.
      # Latency is noisier (GC/warmup) so its floor has headroom; the rolling
      # median+MAD remains the sensitive gate for an INTRODUCED regression. Both
      # are far below a #2329-class signal (CPU saturation / latency ~hundreds×).
      {name:"growth.cpu_ratio",  value:(.cpu_pct.ratio),         dir:"up", min_pct:0.10, floor:1.30},
      {name:"growth.heap_ratio", value:(.heap_used_bytes.ratio), dir:"up", min_pct:0.10, floor:1.30},
      # A ratio detects a SLOPE; an absolute detects a STEP. A leak that plateaus
      # at the ring cap gives ratio ~1.0 while the live set is permanently doubled,
      # and an elevated first-window floor (warm-up garbage) inflates the ratio
      # denominator and hides a real leak. So budget the absolute live-set floor
      # (min heap over the last 60 s) too, on the rolling median+MAD.
      {name:"growth.live_set_bytes", value:(.heap_used_bytes.min_last_window), dir:"up", min_pct:0.10, floor:null},
      {name:"growth.p95_ratio",  value:(.p95_ms.ratio),          dir:"up", min_pct:0.10, floor:2.0} ) ),
  ((.microbench // {}) | to_entries[] | .key as $k | .value as $v |
    ( {name:($k+".time_per_op"),       value:$v.time_per_op,       dir:"up", min_pct:0.05, floor:null},
      {name:($k+".alloc_bytes_per_op"),value:$v.alloc_bytes_per_op,dir:"up", min_pct:0.05, floor:null} ) ),
  # peak_achieved_rps: max achieved throughput across sweep rungs where the k6
  # CLIENT was sound (CPU headroom, no dropped iterations, low errors) — derived in
  # perf-test-run.sh. CONTINUOUS (moves proportionally with the real ceiling, e.g.
  # 36,324 at 48,000 offered), so a 15% relative floor is meaningful. The down
  # branch honours min_pct. saturation_rps (the knee) is ladder-QUANTISED — its
  # smallest move is a factor of two — so it is recorded but NOT budgeted here.
  ( {name:"peak_achieved_rps", value:(.peak_achieved_rps), dir:"down", min_pct:0.15, floor:null} ),
  # forward.error_rate: the forward connection-pool guard (forward.js). ~0 with
  # pooling on (the default); spikes if pooling regresses and the SUT exhausts
  # ephemeral ports. Absolute floor 0.01 mirrors the k6 threshold in forward.js.
  ( {name:"forward.error_rate", value:((.forward_guard // {}).error_rate), dir:"up", min_pct:0, floor:0.01} );

($baseline | map([metrics]) | add | map(select(.value != null)) | group_by(.name)
  | map({key:.[0].name, value:[.[].value]}) | from_entries) as $bmap
| [ ([ . | metrics ][] | select(.value != null)) as $m
    | ($bmap[$m.name] // []) as $bv
    | if ($bv|length) < 1 then {name:$m.name, head:$m.value, status:"no-baseline"}
      else
        ($bv|median) as $med
        | (($bv|mad($med)) * 1.4826) as $sigma
        | (if $m.dir=="up"
            then (if $m.floor!=null then ([$med + 3*$sigma, $m.floor]|max)
                  else ([$med + 3*$sigma, $med*(1+$m.min_pct)]|max) end) as $th
                 | {name:$m.name, head:$m.value, baseline:$med, threshold:$th,
                    regression: ($m.value > $th)}
            else (if $m.floor!=null then ([$med - 3*$sigma, $m.floor]|min)
                  else ([$med - 3*$sigma, $med*(1-$m.min_pct)]|min) end) as $th
                 | {name:$m.name, head:$m.value, baseline:$med, threshold:$th,
                    regression: ($m.value < $th)} end)
      end ] as $rows
| { count: ([$rows[]|select(.regression==true)]|length),
    rows: $rows }
'
RESULT_CMP="$(jq -n \
  --slurpfile baselineFile "$WORK/baseline.json" \
  --slurpfile runFile "$RESULT" \
  '($baselineFile[0]) as $baseline | ($runFile[0]) | '"$COMPARE")"

COUNT="$(printf '%s' "$RESULT_CMP" | jq -r '.count')"

# --- 5. render annotation -----------------------------------------------------
TABLE="$(printf '%s' "$RESULT_CMP" | jq -r '
  "| Metric | Head | Baseline | Threshold | Status |\n|---|---:|---:|---:|:--|",
  (.rows[] |
    "| \(.name) | \(.head // "n/a") | \(.baseline // "n/a" | if type=="number" then (.*1000|round)/1000 else . end) | \(.threshold // "n/a" | if type=="number" then (.*1000|round)/1000 else . end) | \(if .status=="no-baseline" then ":new: new" elif .regression then ":warning: REGRESSION" else ":white_check_mark: ok" end) |")')"

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

HEADER="Perf regression — \`${COMMIT:0:10}\` on \`${BRANCH}\` (baseline: ${BASE_COUNT} runs, median+MAD)"
if [ "$COUNT" -gt 0 ]; then
  annotate "warning" ":chart_with_downwards_trend: **${COUNT} performance regression(s) detected** — ${HEADER}

${TABLE}
${EXTRA}

_Notify-only: this does not fail the build. Investigate the flagged metric(s) against recent commits._"
  # Optional notification hook (Slack/email webhook) — no-op if PERF_NOTIFY_WEBHOOK unset.
  if [ -n "${PERF_NOTIFY_WEBHOOK:-}" ]; then
    curl -sS -X POST "$PERF_NOTIFY_WEBHOOK" -H 'Content-Type: application/json' \
      -d "$(jq -n --arg t "$COUNT perf regression(s) on $BRANCH ($COMMIT)" --arg u "${BUILDKITE_BUILD_URL:-}" '{text: ($t + " " + $u)}')" >/dev/null 2>&1 || true
  fi
else
  annotate "success" ":white_check_mark: **No performance regressions** — ${HEADER}

${TABLE}
${EXTRA}"
fi

exit 0
