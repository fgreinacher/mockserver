#!/usr/bin/env bash
# Buildkite step wrapper that turns the CI-agnostic release credential liveness
# probe (scripts/release/check-release-credentials.sh) into a preflight GATE.
#
# WHY A WRAPPER
#   The probe is deliberately CI-agnostic: it takes only --required-only /
#   --self-test and REJECTS any other argument with exit 64. The Buildkite
#   release adapter (release-runner.sh) hands every release STAGE a
#   --execute/--dry-run flag, which the probe would reject — so the probe must
#   NOT be dispatched as an ordinary stage. This wrapper runs it with no such
#   flag, and adds the one CI-specific concern the probe leaves out: turning a
#   non-zero outcome into an actionable red build (a Buildkite annotation naming
#   the credential and outcome). CI glue like this belongs here, not in the
#   CI-agnostic probe (see the scripts/release/_lib.sh header).
#
# EXIT-CODE CONTRACT — inherited verbatim from the probe; this wrapper never
# rewrites a pass into a fail or a fail into a pass, it only annotates and
# propagates:
#   0   every required credential VALID / VALID(SHAPE)      -> gate PASS
#   1   a required credential REJECTED / MALFORMED / ABSENT -> gate FAIL
#   2   a required credential INDETERMINATE (unprovable)    -> gate FAIL (fail-closed)
#   3   no usable AWS session                               -> gate FAIL (see note)
#   64  bad probe arguments                                 -> gate FAIL (wiring bug)
#
# EXIT 3 ON THE RELEASE QUEUE is a hard FAIL, not a pass. This step runs on the
# `release` queue, whose agents carry an instance role, so
# `aws sts get-caller-identity` should always succeed. Exit 3 there means the
# agent's own AWS identity is broken (IMDS / instance-role failure) and NOTHING
# could be probed — treating "couldn't even authenticate to AWS" as green would
# reinstate exactly the false confidence this gate exists to remove. Because the
# step is not soft_fail, propagating the non-zero code is all that is needed.
#
# NOT soft_fail: a credential gate that cannot fail the build is decoration.
set -uo pipefail

STEP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$STEP_DIR/../../.." && pwd)"
PROBE="$REPO_ROOT/scripts/release/check-release-credentials.sh"

if [[ ! -x "$PROBE" ]]; then
  echo "--- :x: release credential gate: probe not found or not executable: $PROBE" >&2
  exit 1
fi

out_file="$(mktemp "${TMPDIR:-/tmp}/credgate.XXXXXX")"
ann_file="$out_file.ann"
trap 'rm -f "$out_file" "$ann_file"' EXIT

# Run the probe read-only. Deliberately NO --execute/--dry-run (see header) and
# NO --required-only: the full catalogue is probed so advisory-channel results
# also reach the annotation; only REQUIRED credentials change the exit code, so
# running the optional ones can never turn the gate red. Combined stdout+stderr
# is tee'd so the full table still streams to the build log AND is captured for
# the annotation. PIPESTATUS[0] is the probe's own exit code, not tee's.
echo "--- :closed_lock_with_key: Probing release publishing credentials for liveness (read-only)"
"$PROBE" 2>&1 | tee "$out_file"
rc="${PIPESTATUS[0]}"

if [[ "$rc" -eq 0 ]]; then
  echo "--- :white_check_mark: Release credential gate PASSED — every required credential authenticated"
  exit 0
fi

case "$rc" in
  1)  headline="a REQUIRED credential was REJECTED / MALFORMED / ABSENT — do NOT start the release" ;;
  2)  headline="a REQUIRED credential is INDETERMINATE (could not be proven) — failing closed; resolve the probe environment on the release queue and re-run" ;;
  3)  headline="no usable AWS session on the release queue — the agent instance role / IMDS is not providing credentials, so NO credential could be probed" ;;
  64) headline="the probe rejected its arguments (exit 64) — this is a pipeline WIRING bug (the probe must run without --execute/--dry-run), not a credential problem" ;;
  *)  headline="the probe exited with unexpected status $rc" ;;
esac

# Pull the non-passing rows (and their following note lines) plus the probe's
# own verdict out of the captured output, so the red build names WHICH
# credential and WHICH outcome rather than just "script failed". Matching the
# uppercase outcome tokens is robust to the table's unicode status markers; -A1
# grabs each row's note line; the Legend line and grep's -- separators drop out.
detail="$(grep -E -A1 '(REJECTED|MALFORMED|ABSENT|INDETERMINATE|DENIED)' "$out_file" \
          | grep -vE '^(Legend:|--$)' || true)"

{
  echo '### :rotating_light: Release credential preflight gate FAILED'
  echo
  echo "**Outcome:** ${headline}."
  echo
  echo "Probe scripts/release/check-release-credentials.sh exited ${rc}."
  if [[ -n "$detail" ]]; then
    echo
    echo 'Credential(s) that did not pass:'
    echo
    echo '```'
    printf '%s\n' "$detail"
    echo '```'
  fi
  echo
  echo 'This gate is intentionally NOT soft_fail. Rotate or fix the credential above and re-run the release preflight before starting a release. The full probe table is in this step log.'
} > "$ann_file"

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent annotate --style error --context "release-credential-gate" < "$ann_file" || true
else
  # Running outside Buildkite (e.g. local verification): no annotator, so echo
  # the annotation body to the log rather than silently dropping it.
  echo "--- (buildkite-agent not present — annotation body follows)" >&2
  cat "$ann_file" >&2
fi

echo "--- :x: Release credential gate FAILED (exit ${rc})" >&2
exit "$rc"
