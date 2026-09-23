#!/usr/bin/env bash
#
# Delete agent branches that no longer have a worktree and are already merged.
#
# WHY THIS EXISTS AS A SCRIPT. Doing it by hand is where it goes wrong. The obvious
# inline version builds a list of "branches that still have a worktree" and then
# pattern-matches against it — and a newline-separated list matched with a
# space-separated pattern silently protects nothing. That exact mistake was made:
# every live branch fell through to the delete. Nothing was lost, but only because
# `git branch -d` independently refuses a branch that is checked out somewhere. The
# guard contributed nothing; the backstop did all the work, and a clean result made
# it look like the guard had worked.
#
# So the protected set is compared with `grep -Fxq` — fixed string, whole line — and
# there is a self-test below that asserts a checked-out branch is actually SKIPPED
# rather than merely refused later.
#
# SAFETY
#   * `-d`, never `-D`. An unmerged branch must survive and be reported, because it
#     is someone's unpushed work. (Note `.opencode/rules/worktree-workflow.md` Step 8
#     uses `-D` for the single branch a session just merged — that is a different,
#     narrower case, and still worth knowing about.)
#   * Dry run unless `--delete` is passed. Listing what would go is the default.
#   * Only `worktree-agent-*` branches are ever considered.
#   * Containment is checked against origin/master before deleting.
#
# Usage:
#   .opencode/scripts/sweep-agent-branches.sh            # show what would be deleted
#   .opencode/scripts/sweep-agent-branches.sh --delete   # actually delete
#   .opencode/scripts/sweep-agent-branches.sh --self-test

set -euo pipefail

BRANCH_GLOB='worktree-agent-*'
BASE_REF="${SWEEP_BASE_REF:-origin/master}"

# Branches currently checked out in ANY worktree. These must never be touched, and
# the point of the script is that this list is applied correctly.
protected_branches() {
    git worktree list --porcelain 2>/dev/null \
        | sed -n 's|^branch refs/heads/||p'
}

is_protected() {
    # -F fixed string, -x whole line: "agent-a1" must not match "agent-a10".
    printf '%s\n' "$2" | grep -Fxq -- "$1"
}

sweep() {
    local do_delete="$1"
    local protected deleted=0 kept=0 skipped=0
    protected="$(protected_branches)"

    local branch tip ahead
    while IFS= read -r branch; do
        [ -n "$branch" ] || continue
        tip="$(git rev-parse --short "$branch")"

        if is_protected "$branch" "$protected"; then
            echo "  skip     $branch ($tip) — checked out in a worktree"
            skipped=$((skipped + 1))
            continue
        fi

        if ! git merge-base --is-ancestor "$branch" "$BASE_REF" 2>/dev/null; then
            ahead="$(git rev-list --count "$BASE_REF..$branch" 2>/dev/null || echo '?')"
            echo "  KEEP     $branch ($tip) — $ahead commit(s) not in $BASE_REF"
            kept=$((kept + 1))
            continue
        fi

        if [ "$do_delete" = true ]; then
            if git branch -d "$branch" >/dev/null 2>&1; then
                echo "  deleted  $branch ($tip)"
                deleted=$((deleted + 1))
            else
                # -d refused despite the containment check: report, never escalate to -D.
                echo "  KEEP     $branch ($tip) — git refused to delete it"
                kept=$((kept + 1))
            fi
        else
            echo "  would delete  $branch ($tip)"
            deleted=$((deleted + 1))
        fi
    done < <(git branch --list "$BRANCH_GLOB" --format='%(refname:short)')

    echo
    if [ "$do_delete" = true ]; then
        echo "deleted=$deleted kept=$kept skipped=$skipped"
    else
        echo "would delete=$deleted, keep=$kept, skip=$skipped   (re-run with --delete)"
    fi
}

# --- self-test ---------------------------------------------------------------
# The bug this script exists to prevent is a protected-list comparison that matches
# nothing. A self-test that only checked "merged branches get deleted" would have
# passed with that bug present, so these assert the SKIP path directly.
_self_test() {
    local failures=0

    _expect() {
        local name="$1" expected="$2" actual="$3"
        if [ "$expected" = "$actual" ]; then
            echo "  ok    $name"
        else
            echo "  FAIL  $name — expected '$expected', got '$actual'"
            failures=$((failures + 1))
        fi
    }

    echo "--- sweep-agent-branches self-test"

    local list
    list="$(printf 'worktree-agent-a1\nmaster\nperf/item12\n')"

    _expect "an exactly-matching branch is protected" \
        "yes" "$(is_protected 'worktree-agent-a1' "$list" && echo yes || echo no)"
    _expect "a branch not in the list is not protected" \
        "no"  "$(is_protected 'worktree-agent-zz' "$list" && echo yes || echo no)"
    # The real bug: a newline-separated list matched loosely protects nothing.
    _expect "a multi-line list still protects its LAST entry" \
        "yes" "$(is_protected 'perf/item12' "$list" && echo yes || echo no)"
    _expect "a multi-line list still protects its FIRST entry" \
        "yes" "$(is_protected 'worktree-agent-a1' "$list" && echo yes || echo no)"
    # Prefix collisions must not protect by accident.
    _expect "a prefix of a protected branch is NOT protected" \
        "no"  "$(is_protected 'worktree-agent-a' "$list" && echo yes || echo no)"
    _expect "a branch whose name EXTENDS a protected one is NOT protected" \
        "no"  "$(is_protected 'worktree-agent-a10' "$list" && echo yes || echo no)"
    _expect "an empty protected list protects nothing" \
        "no"  "$(is_protected 'worktree-agent-a1' '' && echo yes || echo no)"

    echo
    if [ "$failures" -ne 0 ]; then
        echo "--- self-test: $failures failure(s)" >&2
        return 1
    fi
    echo "--- self-test: all checks passed"
}

case "${1:-}" in
    --self-test) _self_test ;;
    --delete)    echo "=== sweeping $BRANCH_GLOB (deleting) ==="; sweep true ;;
    ""|--dry-run) echo "=== sweeping $BRANCH_GLOB (dry run) ==="; sweep false ;;
    *) echo "usage: $0 [--dry-run|--delete|--self-test]" >&2; exit 2 ;;
esac
