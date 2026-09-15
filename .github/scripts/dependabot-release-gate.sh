#!/usr/bin/env bash
# Release-side hook for the Dependabot auto-merge RELEASE-IN-FLIGHT gate.
#
# The auto-merge workflow (.github/workflows/dependabot-auto-merge.yml) stands
# down while the repository variable RELEASE_IN_PROGRESS holds a recent epoch
# timestamp, so routine dependency bumps do not auto-merge into master while a
# release is being cut. This script is the ONLY writer of that variable: the
# release pipeline calls it to `set` the flag at the start (prepare) and `clear`
# it at the end (finalize) AND from a failure trap.
#
# WHY THIS LIVES HERE, not under scripts/release/: it is a GitHub-side control
# (it writes a GitHub Actions repository variable read by a GitHub Actions
# workflow), so it belongs beside the workflow it serves. The release scripts
# merely invoke it. Wiring points (on the release side):
#   * prepare stage / start of a real (non-dry-run) release:  set
#   * finalize stage:                                         clear
#   * a trap on the release runner so an ABORTED release also clears:  clear
# The workflow's own max-age backstop (default 12h) is the last-resort safety
# net for the case where even the failure-trap clear does not run.
#
# TOKEN: writing a repository variable is a GitHub Actions resource and needs a
# token in GH_TOKEN that can write Actions variables — a classic PAT needs the
# `repo` scope, a fine-grained PAT the "Variables: read and write" repository
# permission. The release loads a GitHub PAT from the
# `mockserver-release/github-token` secret (see scripts/release/_lib.sh).
#
# MEASURED REALITY — the gate is INERT today. That PAT is fine-grained and does
# NOT currently carry the Variables permission: it returns HTTP 403 on the
# /actions/variables API (GET/POST/PATCH/DELETE alike). So every `set`/`clear`
# here fails the write, and this script shouts LOUDLY — a delimited INERT block
# on BOTH stdout and stderr, and (under Buildkite) a red `dependabot-release-gate`
# annotation — while STILL exiting 0 so the release proceeds. The gate stays
# inert until someone adds "Variables: read and write" to that fine-grained PAT.
# The auto-merge workflow does NOT need this token: it READS the value from the
# `vars` context at render time, which requires no token and no permission.
#
# Reads/writes are idempotent and NEVER fail the release: a gate hiccup must not
# abort a release, and a stranded flag self-expires via the workflow backstop.
#
# GH RUNNER — how the `gh` call is executed, resolved once at startup in order:
#   1. $GATE_GH_CMD  — an explicit runner override (word-split into a command
#      prefix). The release wiring in scripts/release/_lib.sh sets this to the
#      containerised form `in_docker "$GH_IMAGE" -e GH_TOKEN --`, because the
#      release-queue AMI installs NO host `gh` (its bootstrap installs no
#      packages), so a bare host `gh` would leave the gate permanently inert.
#      The token travels via the GH_TOKEN *environment* variable (docker `-e
#      GH_TOKEN` passthrough) and so never appears on any argv.
#   2. host `gh` on PATH — the standalone / local path (run it directly).
#   3. neither — NO runner. This is itself a write failure: it takes the SAME
#      loud INERT path with a REASON that names the missing runner, so it can be
#      neither mistaken for a clean run nor confused with a 403.
# The HOST always decides and annotates (buildkite-agent is a host binary);
# only the `gh` call itself is delegated to the runner.
#
# Usage:
#   GH_TOKEN=... dependabot-release-gate.sh set     # at release start
#   GH_TOKEN=... dependabot-release-gate.sh clear    # at finalize + failure trap
#   GH_TOKEN=... dependabot-release-gate.sh status   # print current value
#   GATE_GH_CMD='in_docker <image> -e GH_TOKEN --' … # containerised runner
#
# REPO defaults to this repository; override via the REPO env var.
set -uo pipefail

VAR="RELEASE_IN_PROGRESS"
REPO="${REPO:-mock-server/mockserver-monorepo}"
cmd="${1:-status}"

# ── Resolve the gh runner ONCE (see "GH RUNNER" in the header). ─────────────
declare -a GH_RUNNER=()
if [ -n "${GATE_GH_CMD:-}" ]; then
  # Word-split the override into a command prefix (its tokens — image, -e,
  # GH_TOKEN, -- — contain no spaces, so simple IFS splitting is correct).
  read -ra GH_RUNNER <<<"$GATE_GH_CMD"
  GATE_RUNNER_KIND="override"
  GATE_RUNNER_DIAG="runner: \$GATE_GH_CMD override = '$GATE_GH_CMD'"
elif command -v gh >/dev/null 2>&1; then
  GH_RUNNER=(gh)
  GATE_RUNNER_KIND="host"
  GATE_RUNNER_DIAG="runner: host 'gh' at $(command -v gh)"
else
  GATE_RUNNER_KIND="none"
  GATE_RUNNER_DIAG="no gh runner: \$GATE_GH_CMD is unset AND no host 'gh' is on PATH"
fi

# Run the resolved runner with the given gh arguments. Only ever called when a
# runner was resolved (callers guard on GATE_RUNNER_KIND != none first), so the
# array is non-empty — safe under `set -u` on older bash.
gh_run() { "${GH_RUNNER[@]}" "$@"; }

# Extract an "HTTP NNN" status code from captured gh output ("" if none). gh
# prints its API errors as e.g. "gh: Not Found (HTTP 404)" to stderr, so the
# code is recoverable from the combined stream we capture on every call.
http_code_from() {
  printf '%s' "$1" | grep -oE 'HTTP [0-9]{3}' | grep -oE '[0-9]{3}' | tail -1
}

# Print the INERT block to stdout. Reads globals VAR/REPO and takes the reason
# and pre-indented evidence as args. Kept as a plain heredoc in a function body
# (NOT wrapped in a command substitution) so it parses on older bash too.
#   $1 = one-line reason           $2 = pre-indented API evidence
gate_inert_block() {
  cat <<EOF
================================================================================
  DEPENDABOT RELEASE GATE: INERT — auto-merge is NOT being held for this release
================================================================================
  Could not write the GitHub Actions repository variable '$VAR' on '$REPO'.

  CONSEQUENCE: Dependabot auto-merge is NOT paused. Dependency bumps CAN
  auto-merge into master while this release is being cut. (The auto-merge
  workflow's max-age backstop still bounds any stranded flag, but right now
  NO hold is in effect.)

  REASON: $1

  API EVIDENCE:
$2

  REMEDY: the release PAT at 'mockserver-release/github-token' cannot write
  Actions variables. Add the "Variables: read and write" repository permission
  to that fine-grained PAT (a classic PAT needs the 'repo' scope). Until then
  this gate stays inert and this notice repeats on every release.
================================================================================
EOF
}

# Emit an un-missable INERT notice to stdout AND stderr, and — under Buildkite —
# a red build annotation. NEVER fatal: a gate hiccup must not abort a release.
#   $1 = one-line reason           $2 = captured API evidence (may be empty)
gate_inert() {
  local reason evidence indented_evidence
  reason="$1"
  evidence="${2:-}"
  [ -n "$evidence" ] || evidence="(no API output captured)"
  # Indent each evidence line so it sits under the "API EVIDENCE:" heading.
  indented_evidence="$(printf '%s' "$evidence" | sed 's/^/    /')"
  # Both streams — impossible to miss whichever a reader is tailing.
  gate_inert_block "$reason" "$indented_evidence"
  gate_inert_block "$reason" "$indented_evidence" >&2
  # Buildkite error annotation, guarded so it is a no-op off Buildkite or when
  # the agent binary is not on PATH.
  if [ -n "${BUILDKITE:-}" ] && command -v buildkite-agent >/dev/null 2>&1; then
    gate_inert_block "$reason" "$indented_evidence" | buildkite-agent annotate \
      --style error --context dependabot-release-gate >/dev/null 2>&1 || true
  fi
}

case "$cmd" in
  set)
    if [ "$GATE_RUNNER_KIND" = "none" ]; then
      # No runner resolved — a write failure, surfaced loudly and distinctly
      # (no HTTP code, so it can never be mistaken for a 403 or a clean run).
      gate_inert "cannot write $VAR — no gh runner resolved to reach the API" "$GATE_RUNNER_DIAG"
    else
      now="$(date -u +%s)"
      # Create-or-update: PATCH if it already exists, else POST to create it. The
      # probe's output is used only to choose the verb; the WRITE below captures
      # and surfaces its own error, so nothing load-bearing is discarded.
      if gh_run api "repos/$REPO/actions/variables/$VAR" >/dev/null 2>&1; then
        write_out="$(gh_run api --method PATCH "repos/$REPO/actions/variables/$VAR" \
          -f "name=$VAR" -f "value=$now" 2>&1)"; write_rc=$?
      else
        write_out="$(gh_run api --method POST "repos/$REPO/actions/variables" \
          -f "name=$VAR" -f "value=$now" 2>&1)"; write_rc=$?
      fi
      if [ "$write_rc" -ne 0 ]; then
        code="$(http_code_from "$write_out")"
        gate_inert "gh write of $VAR failed (exit $write_rc${code:+, HTTP $code})" "$write_out"
      else
        # VERIFY: trust the read-back, not the call. Read the value via the SAME
        # runner and confirm it equals what we just wrote; a mismatch — or an
        # unreadable read-back — is treated exactly like a failed write.
        #
        # Capture stdout (the value) and stderr (diagnostics) SEPARATELY. The
        # containerised runner (run-in-docker.sh) unconditionally prints a
        # multi-line "Docker Command" banner to stderr, so folding stderr into
        # the value with 2>&1 would make the read-back NEVER equal $now — the
        # gate would falsely announce itself inert on every *successful* write.
        # stderr is kept only as evidence for the failure branch.
        read_err="$(mktemp "${TMPDIR:-/tmp}/gate-readback.XXXXXX")"
        readback="$(gh_run api "repos/$REPO/actions/variables/$VAR" --jq '.value' 2>"$read_err")"; read_rc=$?
        read_err_out="$(cat "$read_err" 2>/dev/null)"; rm -f "$read_err"
        if [ "$read_rc" -ne 0 ]; then
          code="$(http_code_from "$read_err_out")"
          gate_inert "wrote $VAR but read-back failed (exit $read_rc${code:+, HTTP $code}) — cannot confirm the gate engaged" "$read_err_out"
        elif [ "$readback" != "$now" ]; then
          gate_inert "read-back mismatch for $VAR: wrote '$now' but read '$readback'" "$readback"
        else
          echo "release gate SET: $VAR=$now (verified by read-back)"
        fi
      fi
    fi
    ;;
  clear)
    # Three distinct outcomes: deleted / already absent (both fine) / DENIED or
    # errored (loud, same annotation treatment). Still exits 0 either way; a
    # stranded flag is bounded by the workflow's max-age backstop.
    if [ "$GATE_RUNNER_KIND" = "none" ]; then
      gate_inert "cannot clear $VAR — no gh runner resolved to reach the API — a stranded flag (if any) will expire only via the workflow's max-age backstop (default 12h)" "$GATE_RUNNER_DIAG"
    else
      del_out="$(gh_run api --method DELETE "repos/$REPO/actions/variables/$VAR" 2>&1)"; del_rc=$?
      if [ "$del_rc" -eq 0 ]; then
        echo "release gate CLEARED: $VAR deleted"
      else
        code="$(http_code_from "$del_out")"
        if [ "$code" = "404" ]; then
          echo "release gate CLEAR: $VAR already absent (nothing to delete) — OK"
        else
          gate_inert "gh delete of $VAR failed (exit $del_rc${code:+, HTTP $code}) — a stranded flag will keep auto-merge paused until the workflow's max-age backstop (default 12h) expires it" "$del_out"
        fi
      fi
    fi
    ;;
  status)
    # Distinct outcomes — set (with value) / unset (HTTP 404) / unreadable (403
    # or other, with the code) / no runner. An error must NEVER render as "unset".
    if [ "$GATE_RUNNER_KIND" = "none" ]; then
      echo "$VAR is UNREADABLE (no gh runner resolved) — NOT the same as unset" >&2
      echo "$VAR is UNREADABLE (no gh runner)"
    else
      # stdout = value, stderr = diagnostics (kept separate: the containerised
      # runner prints a banner to stderr — see the read-back note under `set`).
      st_err="$(mktemp "${TMPDIR:-/tmp}/gate-status.XXXXXX")"
      val_out="$(gh_run api "repos/$REPO/actions/variables/$VAR" --jq '.value' 2>"$st_err")"; st_rc=$?
      st_err_out="$(cat "$st_err" 2>/dev/null)"; rm -f "$st_err"
      if [ "$st_rc" -eq 0 ]; then
        echo "$VAR=$val_out (set)"
      else
        code="$(http_code_from "$st_err_out")"
        if [ "$code" = "404" ]; then
          echo "$VAR is unset (HTTP 404 — variable does not exist)"
        else
          echo "$VAR is UNREADABLE (gh exit $st_rc${code:+, HTTP $code}) — NOT the same as unset; the token likely lacks Actions Variables read access" >&2
          echo "$VAR is UNREADABLE${code:+ (HTTP $code)}"
        fi
      fi
    fi
    ;;
  *)
    echo "usage: $0 {set|clear|status}" >&2
    exit 2
    ;;
esac

# Never propagate a non-zero status to the release for set/clear/status: the
# gate is best-effort and must not abort a release.
exit 0
