#!/usr/bin/env bash
set -euo pipefail

# Baseline-freshness assertion for the daily performance-regression control.
#
# WHY THIS EXISTS
# ---------------
# The perf regression control (perf-test-compare.sh) compares every run against a
# rolling baseline in S3, refreshed by the DAILY perf pipeline. If that pipeline
# stops firing, or fires but its run breaks, the baseline silently goes stale and
# every subsequent comparison measures an ancient number while still reporting
# green. That is the exact decay this programme exists to eliminate (a JMH backstop
# in this repo went dark for four days unnoticed — see
# docs/plans/performance-programme.md, "Keeping the system itself alive").
#
# WHAT IT KEYS OFF (and what it deliberately does NOT)
# ----------------------------------------------------
# It asserts PRODUCER LIVENESS via the Buildkite API — that the daily
# `mockserver-performance-test` pipeline is still firing on its schedule and its
# most recent SCHEDULED build completed successfully. It does NOT gate on the raw
# age of the newest S3 object, because that conflates two different things:
#
#   * The producer is COMMIT-GATED: perf-test-guard.sh deliberately writes no new
#     object when master has not moved since the last run. So a genuinely quiet
#     stretch ages the newest object with a perfectly healthy producer — an
#     object-age gate cries wolf on quiet master and, sitting in a different
#     pipeline, dumps that false red on whoever next touches an infra path.
#
#   * A "ran, then skipped because master had not moved" scheduled build is the
#     producer working CORRECTLY. It shows up as a `passed` scheduled build, so
#     liveness treats it as healthy; object age cannot tell it apart from death.
#
# WHAT LIVENESS DETECTS — AND THE RESIDUAL GAP IT DOES NOT
# --------------------------------------------------------
# DETECTED: the schedule stopped firing (STALLED / NO_SCHEDULE), and the most
# recent COMPLETED scheduled build did not pass (NOT_PASSED — the run broke, or a
# gating regression fired).
#
# NOT DETECTED: a scheduled build that goes GREEN while writing no fresh baseline.
# perf-test-compare.sh currently has two such paths — an invalid run annotates an
# error and then `exit 0` WITHOUT persisting (perf-test-compare.sh:160-172), and
# the persist itself is NON-FATAL (`aws s3 cp ... || echo "WARNING: failed to
# persist ..."`, perf-test-compare.sh:179), so an S3 write failure also leaves the
# build `passed`. A green-but-didn't-write build is therefore NOT caught here; it
# is the PRODUCER's responsibility to make those paths fatal (that producer fix is
# being made separately). This check cannot cover the gap itself, because confirming
# a write would need to READ the object, and that needs perf-bucket S3 access the
# `trigger` queue does not have — terraform/buildkite-agents (main.tf / build-secrets
# .tf) grant the trigger queue ONLY the Buildkite API tokens; the `perf` queue role
# holds the perf-results grant. A fail-closed S3 read on this queue would be a
# PERMANENT false red (the "control removed because it turned the pipeline red"
# failure mode the plan warns about), so the earlier object-content check was
# dropped. Once the producer makes the two paths above fatal, a failed-to-write run
# surfaces here as NOT_PASSED and the gap closes to whatever compare still allows;
# until then it is an explicit, known seam — recorded here rather than hidden behind
# a false reassurance. Producer liveness needs only the API token the trigger queue
# already has.
#
# WHERE IT RUNS
# -------------
# In `mockserver-infra` (see pipeline-infra.yml) — a DIFFERENT pipeline from the
# producer, on the cheap `trigger` queue, and on its OWN daily Buildkite schedule
# (terraform/buildkite-pipelines/pipelines.tf) offset from the producer's 04:00
# slot. A check that lives inside the system it monitors dies with it; this one
# does not, and its own schedule gives it a guaranteed cadence rather than relying
# on someone happening to touch an infra path.
#
# FAIL-CLOSED CONTRACT
# --------------------
# Every uncertain outcome FAILS (non-zero exit). It never passes by defaulting.
# The classes are kept distinct in the message so the operator knows which one they
# have WITHOUT opening the code:
#   - NO_SCHEDULE : the producer pipeline has no scheduled builds at all (the daily
#                   schedule was deleted/disabled, or the pipeline was renamed).
#   - STALLED     : scheduled builds exist but the newest is older than the window
#                   (the cron stopped firing).
#   - NOT_PASSED  : the most recent COMPLETED scheduled build did not pass (the
#                   producer ran but broke, or flagged a gating regression — either
#                   way a human must look before the baseline is trusted).
#   - DENIED      : the Buildkite API token is missing/expired or unauthorised.
#   - TRANSPORT   : the Buildkite API was unreachable or returned an unusable body.

PRODUCER_PIPELINE="${PERF_PRODUCER_PIPELINE_SLUG:-mockserver-performance-test}"
PRODUCER_BRANCH="${PERF_PRODUCER_BRANCH:-master}"
# The producer schedule is UNCONDITIONAL daily (perf-test-guard.sh runs every day
# even when it skips the heavy work), so a healthy producer emits a scheduled build
# every ~24h. 30h flags a fully-missed day while tolerating minor cron jitter.
# Unlike the old object-age threshold there is NO legitimate multi-day gap to
# absorb here, which is exactly why keying off build liveness lets the window be
# tight instead of a week.
PRODUCER_MAX_AGE_HOURS="${PERF_PRODUCER_MAX_AGE_HOURS:-30}"
ORG="${BUILDKITE_ORGANIZATION_SLUG:-mockserver}"
SECRET_ID="${BUILDKITE_API_TOKEN_SECRET_ID:-mockserver-build/buildkite-api-token-readonly}"
REGION="${AWS_REGION:-eu-west-2}"
# Indirection so tests can inject fakes; defaults to the real tools in CI.
AWS_BIN="${PERF_FRESHNESS_AWS_BIN:-aws}"
CURL_BIN="${PERF_FRESHNESS_CURL_BIN:-curl}"

MAX_AGE_SECS=$(( PRODUCER_MAX_AGE_HOURS * 3600 ))
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-freshness.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# annotate(style, body): post a Buildkite annotation when on an agent, and always
# echo so the message is visible in a plain local/log run.
annotate() {
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' "$2" | buildkite-agent annotate --style "$1" --context perf-baseline-freshness || true
  fi
  printf '\n%s\n' "$2"
}

# fail(class, human message): emit an error annotation and exit non-zero.
fail() {
  annotate "error" ":no_entry: **Perf baseline freshness: FAIL — $1**

$2

_Producer:_ Buildkite pipeline \`${PRODUCER_PIPELINE}\`, branch \`${PRODUCER_BRANCH}\`, scheduled builds · _liveness window:_ ${PRODUCER_MAX_AGE_HOURS}h.
_This step lives in \`mockserver-infra\` (a different pipeline from the daily perf producer) and runs on its own schedule so it survives the producer dying. It asserts the daily perf pipeline is still running and passing; investigate that pipeline first._"
  exit 1
}

# to_epoch(iso8601): print UTC epoch seconds for a Buildkite timestamp (e.g.
# 2026-09-16T04:00:05.123Z). Handle GNU date (CI/Linux) and BSD date (local).
to_epoch() {
  local iso="$1" trimmed
  if date -u -d "$iso" +%s 2>/dev/null; then
    return 0
  fi
  trimmed="${iso%+00:00}"
  trimmed="${trimmed%Z}"
  trimmed="${trimmed%.*}"
  if date -u -j -f "%Y-%m-%dT%H:%M:%S" "$trimmed" +%s 2>/dev/null; then
    return 0
  fi
  return 1
}

echo "--- :stopwatch: perf baseline freshness — producer liveness of ${PRODUCER_PIPELINE} (window ${PRODUCER_MAX_AGE_HOURS}h)"

# --- 1. Buildkite API token (fail-closed: no token => DENIED) ------------------
{ set +x; } 2>/dev/null  # never let xtrace echo the secret
TOKEN="$("$AWS_BIN" secretsmanager get-secret-value --secret-id "$SECRET_ID" --region "$REGION" --query SecretString --output text 2>"$WORK/tok.err" || true)"
if [ -z "$TOKEN" ] || [ "$TOKEN" = "None" ]; then
  fail "DENIED (no Buildkite API token)" \
    "Could not read the Buildkite API token from Secrets Manager (\`${SECRET_ID}\`). Without it, producer liveness cannot be confirmed, so this fails closed rather than assuming the producer is healthy.

Detail:
\`\`\`
$(cat "$WORK/tok.err" 2>/dev/null || true)
\`\`\`"
fi

# --- 2. fetch the producer's recent builds ------------------------------------
# Pass the token via a curl --config file, NOT `-H "Authorization: Bearer ..."` on
# the command line: argv is world-readable at /proc/<pid>/cmdline, and the trigger
# queue packs multiple same-UID builds per host (agents_per_instance), so a
# co-tenant could read an argv token during the call. The config file lives in the
# 0700 mktemp dir under the existing EXIT trap. `set +x` above the token fetch
# already keeps it out of xtrace.
API="https://api.buildkite.com/v2/organizations/${ORG}/pipelines/${PRODUCER_PIPELINE}/builds"
CURL_CFG="$WORK/curl.cfg"
( umask 077; printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" > "$CURL_CFG" )
set +e
BODY="$("$CURL_BIN" -sS --max-time 15 --connect-timeout 5 \
  --config "$CURL_CFG" \
  --get "$API" \
  --data-urlencode "branch=${PRODUCER_BRANCH}" \
  --data-urlencode "per_page=30" 2>"$WORK/curl.err")"
CURL_RC=$?
set -e

if [ "$CURL_RC" -ne 0 ]; then
  fail "TRANSPORT (Buildkite API unreachable)" \
    "The Buildkite API request failed at the transport level (curl exit ${CURL_RC}: DNS, network, endpoint, or timeout). Producer liveness cannot be confirmed, so this fails closed.

Detail:
\`\`\`
$(cat "$WORK/curl.err" 2>/dev/null || true)
\`\`\`"
fi

# A successful HTTP call returns a JSON ARRAY of builds. A 401/403 returns a JSON
# OBJECT {\"message\": ...} — classify that as DENIED, distinct from transport.
if ! printf '%s' "$BODY" | jq -e 'type == "array"' >/dev/null 2>&1; then
  MSG="$(printf '%s' "$BODY" | jq -r '.message // empty' 2>/dev/null || true)"
  if printf '%s' "$MSG" | grep -qiE 'authenticate|authoriz|authoris|token|forbidden|access denied|invalid'; then
    fail "DENIED (Buildkite API rejected the token)" \
      "The Buildkite API rejected the request (\"${MSG}\"). The token is invalid, expired, or lacks read access to \`${PRODUCER_PIPELINE}\`. Fails closed."
  fi
  fail "TRANSPORT (unusable Buildkite API response)" \
    "The Buildkite API returned a body that is not a builds array and carries no recognisable auth error. Fails closed because producer liveness cannot be read.

First 300 bytes:
\`\`\`
$(printf '%s' "$BODY" | head -c 300)
\`\`\`"
fi

# --- 3. isolate the producer's SCHEDULED builds -------------------------------
# Only source==schedule builds prove the CRON is alive; a manual UI build does
# not. Builds come newest-first from the API.
SCHED="$(printf '%s' "$BODY" | jq -c '[ .[] | select(.source == "schedule") ]')"
SCHED_COUNT="$(printf '%s' "$SCHED" | jq 'length')"
if [ "${SCHED_COUNT:-0}" -eq 0 ]; then
  fail "NO_SCHEDULE (producer has no scheduled builds)" \
    "The producer pipeline \`${PRODUCER_PIPELINE}\` has NO \`source==schedule\` builds in its recent history on \`${PRODUCER_BRANCH}\`. The daily schedule has been deleted or disabled (or the pipeline was renamed). The perf baseline is no longer being refreshed on any cadence."
fi

# Newest scheduled build (any state) — its age proves the cron is still FIRING.
NEWEST_CREATED="$(printf '%s' "$SCHED" | jq -r '.[0].created_at')"
NEWEST_NUMBER="$(printf '%s' "$SCHED" | jq -r '.[0].number')"
NEWEST_STATE="$(printf '%s' "$SCHED" | jq -r '.[0].state')"

CREATED_EPOCH="$(to_epoch "$NEWEST_CREATED" || true)"
if ! printf '%s' "$CREATED_EPOCH" | grep -qE '^[0-9]+$'; then
  fail "TRANSPORT (unparseable build timestamp)" \
    "The newest scheduled build (#${NEWEST_NUMBER}) has a created_at that could not be parsed: \`${NEWEST_CREATED}\`. Fails closed because its age cannot be determined."
fi

NOW_EPOCH="$(date -u +%s)"
AGE_SECS=$(( NOW_EPOCH - CREATED_EPOCH ))
AGE_HOURS=$(( AGE_SECS / 3600 ))

if [ "$AGE_SECS" -gt "$MAX_AGE_SECS" ]; then
  fail "STALLED (no scheduled build within ${PRODUCER_MAX_AGE_HOURS}h)" \
    "The newest scheduled build of \`${PRODUCER_PIPELINE}\` (#${NEWEST_NUMBER}) is **${AGE_HOURS}h old** — older than the ${PRODUCER_MAX_AGE_HOURS}h window. The daily schedule has stopped firing. Every perf comparison since then has measured a stale baseline while reporting green.

- newest scheduled build: #${NEWEST_NUMBER} (${NEWEST_STATE}), created \`${NEWEST_CREATED}\` (${AGE_HOURS}h ago)

Check the \`${PRODUCER_PIPELINE}\` pipeline's schedule in Buildkite / terraform/buildkite-pipelines."
fi

# --- 4. most recent COMPLETED scheduled build must have PASSED ----------------
# Skip non-terminal states (running/scheduled/waiting) so a build in flight when
# this check runs does not read as a failure; judge the most recent build that
# actually reached a verdict. A guard-skip (master unchanged) also lands here as
# `passed`, which is the producer working correctly.
TERMINAL="$(printf '%s' "$SCHED" | jq -c '[ .[] | select(.state | . == "passed" or . == "failed" or . == "canceled" or . == "skipped" or . == "not_run" or . == "blocked") ][0] // empty')"
if [ -z "$TERMINAL" ] || [ "$TERMINAL" = "null" ]; then
  fail "STALLED (no completed scheduled build)" \
    "Scheduled builds exist for \`${PRODUCER_PIPELINE}\` but none has reached a terminal state (all running/scheduled). If this persists the producer is wedged, not producing baselines. Fails closed."
fi
TERM_STATE="$(printf '%s' "$TERMINAL" | jq -r '.state')"
TERM_NUMBER="$(printf '%s' "$TERMINAL" | jq -r '.number')"
TERM_URL="$(printf '%s' "$TERMINAL" | jq -r '.web_url // ""')"
TERM_CREATED="$(printf '%s' "$TERMINAL" | jq -r '.created_at')"

if [ "$TERM_STATE" != "passed" ]; then
  fail "NOT_PASSED (last completed scheduled run was '${TERM_STATE}')" \
    "The most recent COMPLETED scheduled build of \`${PRODUCER_PIPELINE}\` (#${TERM_NUMBER}, created \`${TERM_CREATED}\`) is **${TERM_STATE}**, not passed. The producer ran but did not succeed — it either broke or flagged a gating regression. Either way the freshest baseline cannot be trusted until a human looks.

- build: ${TERM_URL:-#${TERM_NUMBER}}

Investigate that build before relying on the perf regression comparison."
fi

# --- 5. PASS ------------------------------------------------------------------
annotate "success" ":white_check_mark: **Perf baseline producer is live** — the daily \`${PRODUCER_PIPELINE}\` schedule is firing and its last completed scheduled build passed.

- newest scheduled build: #${NEWEST_NUMBER} (${NEWEST_STATE}), ${AGE_HOURS}h ago (window ${PRODUCER_MAX_AGE_HOURS}h)
- last completed scheduled build: #${TERM_NUMBER} (passed)
- scheduled builds inspected: ${SCHED_COUNT}

A passed scheduled build means the producer either ran and passed, or was correctly skipped by the commit guard because master had not moved — both are healthy."
echo "OK: producer live (newest scheduled #${NEWEST_NUMBER} ${AGE_HOURS}h ago, last completed #${TERM_NUMBER} passed)"
exit 0
