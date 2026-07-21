#!/usr/bin/env bash
# Offline evaluation harness for AI components.
# See .opencode/rules/evaluation-harness.md and .opencode/evals/README.md
#
# Exit codes: 0 = OK (no regressions), 1 = a golden task regressed,
#             2 = the corpus is unusable — a malformed fixture, an orphaned
#                 .result, or fewer tasks than MIN_TASKS (a shrunken suite).
#             STRICT=1 makes PENDING count as failure.
#
# NOTE ON WHAT THIS CANNOT CATCH: both `expected_verdict` and `.result` are
# committed files, so editing them *together* to match a degraded agent passes
# silently. That is precisely the "update the golden file instead of fixing the
# code" move banned by .opencode/rules/control-integrity.md, and it is why the
# corpus is an enumerated control path requiring review-final, not a gate that
# can police itself.
set -uo pipefail

EVAL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TASK_DIR="$EVAL_DIR/tasks"
REQUIRED_KEYS="id category agent expected_verdict"
VALID_VERDICTS="PASS BLOCK FLAG"
STRICT="${STRICT:-0}"
# Ratchet: the corpus may grow but must never silently shrink. Deleting fixtures
# is the cheapest way to make this gate vacuous — an empty-suite check alone only
# catches deleting *all* of them. Raise this when fixtures are added; lowering it
# is a control change (see .opencode/rules/control-integrity.md).
MIN_TASKS="${MIN_TASKS:-5}"

# frontmatter <file> <key> -> value (reads the leading --- … --- block only)
frontmatter() {
  awk -v k="$2" '
    /^---[[:space:]]*$/ { n++; next }
    n==1 && $0 ~ "^"k":" { sub("^"k":[[:space:]]*", ""); sub(/[[:space:]]*$/, ""); print; exit }
  ' "$1"
}

[ -d "$TASK_DIR" ] || { echo "no tasks dir: $TASK_DIR"; exit 2; }

total=0; pass=0; fail=0; pending=0; malformed=0

shopt -s nullglob
for f in "$TASK_DIR"/*.md; do
  total=$((total + 1))
  base="$(basename "$f" .md)"
  id="$(frontmatter "$f" id)"

  miss=""
  for k in $REQUIRED_KEYS; do
    [ -z "$(frontmatter "$f" "$k")" ] && miss="$miss $k"
  done
  if [ -n "$miss" ]; then
    echo "MALFORMED $base: missing frontmatter:$miss"; malformed=$((malformed + 1)); continue
  fi
  if [ "$id" != "$base" ]; then
    echo "MALFORMED $base: id '$id' does not match filename stem"; malformed=$((malformed + 1)); continue
  fi

  agent="$(frontmatter "$f" agent)"
  exp="$(frontmatter "$f" expected_verdict)"
  case " $VALID_VERDICTS " in
    *" $exp "*) ;;
    *) echo "MALFORMED $id: expected_verdict '$exp' not in {$VALID_VERDICTS}"; malformed=$((malformed + 1)); continue ;;
  esac
  # review agents emit only PASS/BLOCK, never FLAG — catch the misconfiguration early
  case "$agent" in
    *review*) [ "$exp" = "FLAG" ] && { echo "MALFORMED $id: review agent '$agent' cannot emit FLAG (use BLOCK)"; malformed=$((malformed + 1)); continue; } ;;
  esac

  res="$TASK_DIR/$id.result"
  if [ -f "$res" ]; then
    act="$(tr -d '[:space:]' < "$res")"
    case " $VALID_VERDICTS " in
      *" $act "*) ;;
      *) echo "MALFORMED $id: .result contains '$act', expected one of {$VALID_VERDICTS}"; malformed=$((malformed + 1)); continue ;;
    esac
    if [ "$act" = "$exp" ]; then
      echo "PASS    $id (agent=$agent expected=$exp)"; pass=$((pass + 1))
    else
      echo "FAIL    $id (agent=$agent expected=$exp got=$act)"; fail=$((fail + 1))
    fi
  else
    echo "PENDING $id (agent=$agent expected=$exp) — run the agent on this fixture, then write the verdict to tasks/$id.result"
    pending=$((pending + 1))
  fi
done

# Orphan .result files mean a fixture was renamed or deleted — i.e. coverage was
# removed. That is a corpus regression, not a warning.
orphans=0
for r in "$TASK_DIR"/*.result; do
  [ -e "$r" ] || continue
  stem="$(basename "$r" .result)"
  [ -f "$TASK_DIR/$stem.md" ] || { echo "ORPHAN  $(basename "$r") has no matching fixture — was a task deleted or renamed?"; orphans=$((orphans + 1)); }
done

echo "----"
echo "tasks=$total pass=$pass fail=$fail pending=$pending malformed=$malformed orphans=$orphans strict=$STRICT"

# An empty — or quietly shrunken — suite must NOT vacuously pass the gate.
[ "$total" -gt 0 ] || { echo "NO TASKS FOUND — gate vacuously satisfied; add fixtures before rollout"; exit 2; }
[ "$total" -ge "$MIN_TASKS" ] || { echo "CORPUS SHRANK: $total task(s) < floor of $MIN_TASKS — restore the deleted fixture(s), or lower MIN_TASKS as a reviewed control change"; exit 2; }
# A floor left behind by a grown corpus silently re-opens the deletion gap it exists
# to close, so nudge rather than let it rot (advisory — growth must not redden CI).
[ "$total" -le "$MIN_TASKS" ] || echo "NOTE: corpus has grown to $total (floor is $MIN_TASKS) — raise MIN_TASKS to keep the ratchet tight"
[ "$orphans" -gt 0 ] && { echo "ORPHANED RESULTS: $orphans .result file(s) without a fixture"; exit 2; }
[ "$malformed" -gt 0 ] && { echo "FIXTURES MALFORMED"; exit 2; }
[ "$fail" -gt 0 ] && { echo "REGRESSION: golden task(s) failed"; exit 1; }
if [ "$STRICT" = "1" ] && [ "$pending" -gt 0 ]; then
  echo "STRICT: $pending pending task(s) have no recorded result"; exit 1
fi
echo "OK (no regressions in recorded results)"
exit 0
