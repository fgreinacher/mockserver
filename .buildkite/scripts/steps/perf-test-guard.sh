#!/usr/bin/env bash
set -euo pipefail

# Commit guard + dynamic dispatch for the daily performance-regression run.
#
# The pipeline is scheduled DAILY, but the heavy run should only happen when
# master actually moved since the last successful perf build (requirement: "once
# per day IF there has been a commit since the last run"). Buildkite `if:`
# expressions can't read runtime state, so this guard decides at runtime and
# dynamically uploads the run/micro-benchmark/compare steps ONLY when there is a
# new commit — the same dynamic-pipeline pattern as generate-pipeline.sh.
#
# Runs on the cheap `trigger` queue (just an API query + git diff).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=.buildkite/scripts/lib/last-successful-commit.sh
source "$SCRIPT_DIR/../lib/last-successful-commit.sh"

HEAD_SHA="$(git rev-parse HEAD 2>/dev/null || echo '')"

# --- OPT-IN: load-injection ceiling + scaling sweep ---------------------------
# The injection step (perf-test-inject.sh) is heavy (multi-instance compose +
# Envoy + a per-N scale sweep, ~60m) and answers a DIFFERENT question from the
# daily regression run — "how much load can MockServer GENERATE", not "how fast
# does it SERVE". So it is NOT part of the standard daily dispatch. It runs only
# when explicitly requested: the `PERF_INJECT` build env is truthy, or the build
# message carries the `[perf-inject]` marker. This keeps the daily commit-gated
# build lean while making the injection sweep one toggle away — and because it is
# independent of the new-commit guard, it can be re-run on the SAME commit.
maybe_dispatch_inject() {
  local want=false
  if [ "${PERF_INJECT:-}" = "true" ] || [ "${PERF_INJECT:-}" = "1" ] \
     || [[ "${BUILDKITE_MESSAGE:-}" == *"[perf-inject]"* ]]; then
    want=true
  fi
  if [ "$want" != true ]; then
    echo "--- :zzz: load-injection sweep not requested (set PERF_INJECT=true or [perf-inject] to enable)"
    return 0
  fi
  if ! command -v buildkite-agent >/dev/null 2>&1; then
    echo "(local run) buildkite-agent unavailable — would dispatch inject sweep"
    return 0
  fi
  echo "--- :rocket: load-injection requested (PERF_INJECT / [perf-inject]) — dispatching inject sweep"
  buildkite-agent pipeline upload <<'YAML'
steps:
  - label: ":envoy: load injection — ceiling + scaling sweep"
    command: ".buildkite/scripts/steps/perf-test-inject.sh"
    timeout_in_minutes: 60
    agents:
      queue: "perf"
YAML
}

# Forced/manual run: a UI ("New Build") build, or an API build whose message
# carries the explicit `[perf-run]` marker, ALWAYS dispatches — even on the same
# commit as the last run. This is the deliberate manual escape hatch. Scheduled
# runs keep the "only when master moved" guard below so the daily job stays
# commit-gated.
FORCE_RUN=false
if [ "${BUILDKITE_SOURCE:-}" = "ui" ] || [[ "${BUILDKITE_MESSAGE:-}" == *"[perf-run]"* ]]; then
  FORCE_RUN=true
  echo "--- :rocket: forced run (source=${BUILDKITE_SOURCE:-?}, [perf-run] marker) — skipping new-commit guard"
fi

echo "--- :buildkite: resolving the commit the perf regression last RAN against"
LAST="$(last_perf_run_commit || true)"

NEW_COMMIT=true
if [ "$FORCE_RUN" = false ] && [ -n "$LAST" ]; then
  echo "    last perf run: ${LAST:0:10}  (HEAD: ${HEAD_SHA:0:10})"
  # Equality on the recorded run commit — any new commit on the branch dispatches.
  if [ "$LAST" = "$HEAD_SHA" ]; then
    NEW_COMMIT=false
  fi
elif [ "$FORCE_RUN" = false ]; then
  echo "    no prior perf run recorded — running (first run / conservative)"
fi

if [ "$NEW_COMMIT" = false ]; then
  echo "--- :zzz: no commit since last perf run — skipping"
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' ":zzz: **Perf regression skipped** — no commit on \`${BUILDKITE_BRANCH:-master}\` since the last successful run (\`${LAST:0:10}\`)." \
      | buildkite-agent annotate --style info --context perf-regression || true
  fi
  # The injection sweep is independent of the new-commit guard — honour an
  # explicit opt-in even when the daily regression run is skipped.
  maybe_dispatch_inject
  exit 0
fi

echo "--- :rocket: new commit detected — dispatching perf regression run"
if ! command -v buildkite-agent >/dev/null 2>&1; then
  echo "(local run) buildkite-agent unavailable — would dispatch run + microbench + compare"
  exit 0
fi

buildkite-agent pipeline upload <<'YAML'
steps:
  - label: ":k6: perf regression — run + sample"
    command: ".buildkite/scripts/steps/perf-test-run.sh"
    # Bumped 45 -> 60 for the INFO-log-level publication arm (plan open question 5):
    # a SECOND SUT at the shipped-default log level re-runs the two published figure
    # families (regression.js http+https + sweep.js), adding an estimated ~10 min of
    # wall-clock. The perf box is serialised and the chain's occupancy is not yet
    # measured (open question 6), so this adds headroom rather than risking the cap;
    # trim it back once a few runs show the real duration, or set PERF_INFO_ARM=false
    # to drop the arm entirely.
    timeout_in_minutes: 60
    agents:
      queue: "perf"
  - label: ":microscope: perf regression — micro-benchmark + scaling sweep"
    command: ".buildkite/scripts/steps/perf-test-microbench.sh"
    # Bumped 30 -> 50: the step now also runs run-scaling.sh, which forks a JVM per
    # param combo across MatchingBenchmark (8 combos) + CandidateIndexBenchmark (10
    # combos) after a SECOND core build. With the bounded JMH_ARGS_SCALING this adds
    # ~12-18 min, which can exceed the old 30m budget under cloud-CI noise.
    # Bumped 50 -> 70 (items 15b/15c): the step now ALSO runs the promoted dark
    # benchmarks (~20 param combos, one extra JMH invocation on the microbench
    # classpath — NO third module build) and every JMH run went -f 1 -> -f 2. The
    # added fork is offset by trimmed iterations (-wi 3->2, -i 5->3), so each combo
    # is ~+33% wall-clock, not +100%. Laptop JMH compute goes ~6 -> ~16 min
    # (existing 21 combos +2 min from the fork/iter change; ~20 new dark combos
    # +8 min). Cloud agents run JMH ~2-3x slower, so that ~10 min laptop delta is
    # ~20-25 min on the box; 70m keeps the two module builds + all three JMH runs
    # inside budget under cloud noise.
    timeout_in_minutes: 70
    agents:
      queue: "perf"
  - label: ":racing_car: perf regression — HTTP/2 multiplex (issue #2669)"
    command: ".buildkite/scripts/steps/perf-test-h2multiplex.sh"
    # Builds mockserver-netty, boots a real server, and sweeps N=1,10,100 concurrent
    # streams over one h2c connection. NOTIFY-ONLY, no threshold (recorded only); a
    # NON-zero exit means the harness self-validation failed (bad measurement), not a
    # slowdown, so it surfaces as a red build.
    timeout_in_minutes: 30
    agents:
      queue: "perf"
  - wait: ~
  # No soft_fail: compare.sh exits non-zero when a GATING metric regresses (that
  # red build IS the regression notification) OR when the tooling itself broke
  # (e.g. missing artifact, jq error). A flagged NOTIFY-ONLY metric annotates but
  # exits 0. soft_fail here would swallow the gating signal, so it must stay off.
  - label: ":bar_chart: perf regression — persist + compare baseline"
    command: ".buildkite/scripts/steps/perf-test-compare.sh"
    timeout_in_minutes: 10
    agents:
      queue: "perf"
  # Item 19 — close the loop from S3 back to the website. NON-GATING tail step:
  # regenerates the published figures from the newest VALID run and opens a PR
  # (never a direct commit) when the committed figures are >30 days old or a
  # headline metric moved >10%. Runs AFTER compare (the plain `wait` above means a
  # gating regression, which reds compare, skips this — a regressed run must not
  # refresh the public page). `soft_fail: true` because the script deliberately
  # `exit 1`s on every refuse-to-publish path (unreachable S3, an invalid/pre-fix
  # run, a healthy-but-quiet master with nothing new) and such a refusal must NOT
  # red the daily build. On the `perf` queue, which holds the S3 perf-results grant
  # AND the git/gh credentials the PR needs (the `trigger` queue has neither).
  - wait: ~
  - label: ":globe_with_meridians: perf regression — publish figures to website (PR)"
    command: ".buildkite/scripts/steps/perf-website-publish.sh"
    timeout_in_minutes: 15
    soft_fail: true
    agents:
      queue: "perf"
YAML

# Honour the load-injection opt-in alongside the standard regression dispatch.
maybe_dispatch_inject
