#!/usr/bin/env bash
# agent-status.sh — render a table of active agent worktrees and their state.
#
# Reads each `.worktrees/agent-*` directory under the repo root and prints:
#   - agent ID (suffix of the worktree dir name)
#   - branch
#   - age (minutes since worktree creation)
#   - activity (first line of `.tmp/agent-activity` inside the worktree, if present)
#   - commit count ahead of origin/master
#   - lock status (* if this worktree appears to hold .git/agent-rebase.lock)
#
# Companion to `/worktree` and `/worktree-merge`. See
# `.opencode/rules/worktree-workflow.md` for the broader workflow.
#
# Side effect: after rendering the table, appends one concurrency-over-time
# sample to `.tmp/parallelism-samples.csv` (timestamp,active_worktree_count,
# lock_held) for the §18.7 P1 parallelism telemetry. The append is best-effort
# and never fails the script.
#
# Usage:
#   .opencode/scripts/agent-status.sh
#
# No arguments. Exits 0 even if no worktrees are active (prints a short notice).

set -euo pipefail

# Resolve the shared .git common dir so paths are correct whether this runs from
# the main checkout or a linked worktree (in a worktree, `.git` is a file and
# `--show-toplevel` points at the worktree, not the main checkout).
COMMON_DIR="$(git rev-parse --git-common-dir 2>/dev/null)"
if [ -z "${COMMON_DIR}" ]; then
    echo "Not inside a git repository" >&2
    exit 1
fi
case "${COMMON_DIR}" in
    /*) ;;
    *) COMMON_DIR="$(pwd)/${COMMON_DIR}" ;;
esac
COMMON_DIR="$(cd "${COMMON_DIR}" && pwd)"
# The main checkout root is the parent of the shared .git common dir.
REPO_ROOT="$(dirname "${COMMON_DIR}")"

WORKTREE_BASE="${REPO_ROOT}/.worktrees"
LOCK_FILE="${COMMON_DIR}/agent-rebase.lock"

if [ ! -d "${WORKTREE_BASE}" ]; then
    echo "No agent worktrees active (${WORKTREE_BASE} does not exist)."
    echo "Start one with /worktree, or just keep working in the main checkout."
    exit 0
fi

# Count worktree dirs (excluding nothing — be cheap)
shopt -s nullglob
WORKTREE_DIRS=("${WORKTREE_BASE}"/agent-*)
shopt -u nullglob

if [ "${#WORKTREE_DIRS[@]}" -eq 0 ]; then
    echo "No agent worktrees active."
    exit 0
fi

# Identify the lock holder PID (if any). Best-effort: lsof may not be installed
# everywhere, and on macOS the file-existence check is racey, so treat absence
# as "not held".
LOCK_HOLDER_PID=""
if [ -e "${LOCK_FILE}" ] && command -v lsof >/dev/null 2>&1; then
    # Tolerate a non-zero lsof exit (no process holds the file) under
    # `set -o pipefail`/`errexit`; absence of a holder is not an error.
    LOCK_HOLDER_PID="$(lsof "${LOCK_FILE}" 2>/dev/null | awk 'NR>1 {print $2; exit}' || true)"
fi

now_epoch="$(date +%s)"

printf "%-26s  %-44s  %-8s  %-32s  %-7s  %s\n" \
    "ID" "BRANCH" "AGE" "ACTIVITY" "COMMITS" "LOCK"
printf -- '-%.0s' {1..130}
echo

for wt in "${WORKTREE_DIRS[@]}"; do
    [ -d "${wt}" ] || continue
    agent_id="$(basename "${wt}" | sed 's/^agent-//')"
    branch="$(git -C "${wt}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'unknown')"

    # Worktree creation time — try macOS stat first, then GNU stat
    created_epoch="$(stat -f %B "${wt}" 2>/dev/null || stat -c %W "${wt}" 2>/dev/null || echo 0)"
    if [ "${created_epoch}" -gt 0 ]; then
        age_min=$(( (now_epoch - created_epoch) / 60 ))
        age="${age_min}m"
    else
        age="?"
    fi

    activity="(idle)"
    if [ -f "${wt}/.tmp/agent-activity" ]; then
        # Truncate to 32 chars to keep the table aligned
        activity="$(head -1 "${wt}/.tmp/agent-activity" | cut -c1-32)"
    fi

    # Commits ahead of master
    commits="$(git -C "${wt}" rev-list --count origin/master..HEAD 2>/dev/null || echo '?')"

    # Lock indicator: per-agent lock-holder detection is unreliable across
    # subshells; just mark "*" if the lock file exists and the agent has any
    # background process. Treat "*" as "this worktree might be rebasing".
    lock="-"
    if [ -n "${LOCK_HOLDER_PID}" ]; then
        # If the lock holder's CWD is this worktree, mark it
        lock_cwd="$(lsof -p "${LOCK_HOLDER_PID}" 2>/dev/null | awk '$4 == "cwd" {print $NF; exit}')"
        if [ "${lock_cwd}" = "${wt}" ] || [ "${lock_cwd}" = "${wt}/" ]; then
            lock="*"
        fi
    fi

    printf "%-26s  %-44s  %-8s  %-32s  %-7s  %s\n" \
        "${agent_id}" "${branch}" "${age}" "${activity}" "${commits}" "${lock}"
done

if [ -n "${LOCK_HOLDER_PID}" ]; then
    echo
    echo "Rebase lock currently held by PID ${LOCK_HOLDER_PID}."
fi

# §18.7 P1 parallelism telemetry: append one concurrency-over-time sample
# (timestamp,active_worktree_count,lock_held). Reuses the active count and
# lock state already computed above. Best-effort — guarded so it can never
# fail the script under `set -euo pipefail`.
{
    # lock_held mirrors the table's lock signal: a live holder PID means the
    # rebase lock is actually held (a stale lock file with no holder is not).
    lock_held=0
    [ -n "${LOCK_HOLDER_PID}" ] && lock_held=1
    samples_file="${REPO_ROOT}/.tmp/parallelism-samples.csv"
    mkdir -p "${REPO_ROOT}/.tmp" \
        && printf '%s,%s,%s\n' "${now_epoch}" "${#WORKTREE_DIRS[@]}" "${lock_held}" \
            >> "${samples_file}"
} || true
