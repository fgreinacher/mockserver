#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────────────────
# Diff-Coverage Gate  (+ measurable-changed-line floor + durable ledger)
# ────────────────────────────────────────────────────────────────────────────
# Computes line coverage of CHANGED Java lines (git diff vs merge-base with
# master) from JaCoCo XML reports uploaded as Buildkite artifacts.
#
# Threshold: 70% of changed lines must be covered.  When no Java lines
# changed (docs-only, config-only, etc.) the gate passes unconditionally.
#
# FLOOR (measurable changed lines): the 70% RATIO is only enforced once a diff
# touches at least DIFF_COVERAGE_FLOOR (default 25) *measurable* lines. Below
# the floor the ratio is still computed and PRINTED, but it does NOT fail the
# build -- a single uncovered helper in a 6-line diff should not fail a 70%
# gate. A floor fixes brittleness, not invisibility, so it is paired with:
#
# LEDGER (.buildkite/diff-coverage-ledger.json): the gate is diff-scoped, so an
# uncovered line it lets pass once (below the floor) is never re-examined and
# becomes invisible. The ledger keeps sub-floor misses observable:
#   - COMPLETENESS: below the floor, every measurable-uncovered changed line
#     must be covered OR recorded in the ledger; an unrecorded one fails the
#     build (record it or add a test) so nothing is silently dropped.
#   - STALENESS: a ledger entry whose line is NOW COVERED is stale and fails
#     the build until removed (mirrors known-gaps.json's ratchet), so the
#     backlog can only shrink.
#
# The script is intentionally lenient about coverage DATA:
#   - Lines in files not present in any jacoco.xml are IGNORED (new modules
#     without coverage, test-only changes, non-instrumented code).
#   - Deleted lines are ignored (only additions count).
#   - Wired as soft_fail in pipeline-java.yml so the build is not
#     hard-blocked during the initial rollout period.
# ────────────────────────────────────────────────────────────────────────────
set -euo pipefail

THRESHOLD="${DIFF_COVERAGE_THRESHOLD:-70}"
FLOOR="${DIFF_COVERAGE_FLOOR:-25}"
DEFAULT_BRANCH="${BUILDKITE_PULL_REQUEST_BASE_BRANCH:-master}"

# Ledger lives at the repo root so it is committed alongside the source it
# tracks. Fall back to a path relative to this script if we are not in a git
# work tree (defensive; CI always runs from the checkout root).
if REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null); then
  LEDGER="${DIFF_COVERAGE_LEDGER:-$REPO_ROOT/.buildkite/diff-coverage-ledger.json}"
else
  LEDGER="${DIFF_COVERAGE_LEDGER:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/diff-coverage-ledger.json}"
fi

# ── Temp directory for artifacts and intermediate files ────────────────────
TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

# ── Determine the merge-base ───────────────────────────────────────────────
if [ -n "${MERGE_BASE:-}" ]; then
  BASE="$MERGE_BASE"
elif git rev-parse --verify "origin/${DEFAULT_BRANCH}" >/dev/null 2>&1; then
  BASE=$(git merge-base HEAD "origin/${DEFAULT_BRANCH}" 2>/dev/null || echo "HEAD~1")
else
  BASE="HEAD~1"
fi
echo "--- :git: Diff-coverage base: ${BASE:0:10}"

# ── Collect changed Java lines from the diff ───────────────────────────────
# Output: "relative/path/to/File.java:42" per added line
git diff "$BASE"..HEAD -U0 --diff-filter=AM -- '*.java' | \
  python3 -c '
import sys, re

path = None
for line in sys.stdin:
    m = re.match(r"^\+\+\+ b/(.*)", line)
    if m:
        path = m.group(1)
        continue
    m = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
    if m and path:
        start = int(m.group(1))
        count = int(m.group(2)) if m.group(2) is not None else 1
        for i in range(start, start + count):
            print(f"{path}:{i}")
' > "$TMPDIR/changed_lines.txt" 2>/dev/null || true

# NB: even with no changed Java lines we still run the ledger STALENESS check
# below, so a line that got covered by an unrelated change is caught. Seed an
# empty file so the analysis has a consistent input.
[ -f "$TMPDIR/changed_lines.txt" ] || : > "$TMPDIR/changed_lines.txt"

TOTAL_CHANGED=$(wc -l < "$TMPDIR/changed_lines.txt" | tr -d ' ')
echo "Changed Java lines: $TOTAL_CHANGED"

# ── Download JaCoCo XML artifacts ──────────────────────────────────────────
if [ -n "${BUILDKITE:-}" ]; then
  buildkite-agent artifact download "**/target/site/jacoco/jacoco.xml" "$TMPDIR" 2>/dev/null || true
else
  # Local mode: copy jacoco.xml files preserving directory structure
  while IFS= read -r f; do
    dest="$TMPDIR/$f"
    mkdir -p "$(dirname "$dest")"
    cp "$f" "$dest"
  done < <(find . -path "*/target/site/jacoco/jacoco.xml" 2>/dev/null || true)
fi

JACOCO_COUNT=$(find "$TMPDIR" -name "jacoco.xml" 2>/dev/null | wc -l | tr -d ' ')
if [ "$JACOCO_COUNT" -eq 0 ]; then
  echo "No JaCoCo reports found — diff-coverage gate passes (no coverage data)."
  echo "  (ledger staleness cannot be checked without coverage data — skipping.)"
  exit 0
fi

# ── Compute diff coverage + ledger analysis ────────────────────────────────
# One pass builds the coverage map once and evaluates: (1) the changed-line
# ratio, (2) ledger staleness, (3) ledger completeness for the current diff.
# stderr (human detail) is captured to a file so it survives to the report.
RESULT=$(python3 - "$TMPDIR/changed_lines.txt" "$TMPDIR" "$THRESHOLD" "$LEDGER" \
  2> "$TMPDIR/detail.txt" <<'PYEOF'
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

changed_lines_file = sys.argv[1]
tmpdir = sys.argv[2]
threshold = int(sys.argv[3])
ledger_file = sys.argv[4]

SRC_ROOTS = ("src/main/java/", "src/test/java/")


def coverage_key(filepath, line_nr):
    """Map a repo-relative Java path + line to the JaCoCo package/File.java:line
    key, or None if the path is not under a recognised source root."""
    for src_root in SRC_ROOTS:
        idx = filepath.find(src_root)
        if idx >= 0:
            return f"{filepath[idx + len(src_root):]}:{line_nr}"
    return None


# Read all changed lines: "path/to/File.java:42"
changed = set()
with open(changed_lines_file) as f:
    for raw_line in f:
        raw_line = raw_line.strip()
        if raw_line:
            changed.add(raw_line)

# Build coverage map: { "package/File.java:line" -> covered(bool) }
# JaCoCo XML structure:
#   <report><package name="org/mockserver/foo">
#     <sourcefile name="Bar.java"><line nr="42" mi="0" ci="3" .../>
coverage_map = {}
for jf in glob.glob(f"{tmpdir}/**/jacoco.xml", recursive=True):
    try:
        tree = ET.parse(jf)
    except ET.ParseError:
        continue
    root = tree.getroot()
    for pkg in root.findall(".//package"):
        pkg_name = pkg.get("name", "")
        for sf in pkg.findall("sourcefile"):
            key_prefix = f"{pkg_name}/{sf.get('name', '')}"
            for line_el in sf.findall("line"):
                nr = line_el.get("nr")
                ci = int(line_el.get("ci", "0"))
                # A line recorded covered by one report stays covered (unit +
                # integration reports may both mention it).
                key = f"{key_prefix}:{nr}"
                coverage_map[key] = coverage_map.get(key, False) or (ci > 0)

# ── Changed-line ratio ─────────────────────────────────────────────────────
covered_count = 0
measurable_count = 0
uncovered_changed = []  # repo-relative "path:line" that are measurable+uncovered

for cl in sorted(changed):
    parts = cl.rsplit(":", 1)
    if len(parts) != 2:
        continue
    filepath, line_nr = parts
    key = coverage_key(filepath, line_nr)
    if key is None:
        continue
    if key in coverage_map:
        measurable_count += 1
        if coverage_map[key]:
            covered_count += 1
        else:
            uncovered_changed.append(cl)

pct = 100.0 if measurable_count == 0 else (covered_count / measurable_count) * 100.0
ratio_status = "PASS" if pct >= threshold else "FAIL"

# ── Ledger analysis ────────────────────────────────────────────────────────
# entries: [{file, line, symbol, recorded, reason}]
ledger_entries = []
ledger_broken = False
if os.path.exists(ledger_file):
    try:
        with open(ledger_file) as f:
            ledger_entries = json.load(f).get("entries", [])
    except (ValueError, OSError) as e:
        print(f"Ledger read error for {ledger_file}: {e}", file=sys.stderr)
        ledger_broken = True

stale = []        # entries whose line is now covered -> must be removed
unverifiable = [] # entries whose line is absent from this build's coverage
ledger_pairs = set()  # {"file:line"} recorded in the ledger

if not ledger_broken:
    for e in ledger_entries:
        f_path = e.get("file", "")
        line_nr = str(e.get("line", ""))
        ledger_pairs.add(f"{f_path}:{line_nr}")
        key = coverage_key(f_path, line_nr)
        label = f"{f_path}:{line_nr} ({e.get('symbol', '?')})"
        if key is None:
            unverifiable.append(f"{label} [path not under a source root]")
        elif key not in coverage_map:
            # Line not in any report this build: module not built, or the line
            # moved/was deleted. Do NOT fail (coverage data may be partial) —
            # report so a drifted entry is still visible.
            unverifiable.append(f"{label} [not in this build's coverage data]")
        elif coverage_map[key]:
            stale.append(label)

# ── Completeness: uncovered changed lines not covered and not in the ledger ─
unrecorded = [cl for cl in uncovered_changed if cl not in ledger_pairs]

# ── Machine-readable summary (stdout) ──────────────────────────────────────
# RATIO|covered|measurable|total_changed|pct
print(f"RATIO|{covered_count}|{measurable_count}|{len(changed)}|{pct:.1f}")
# LEDGER|broken(0/1)|num_stale|num_unrecorded_uncovered|num_unverifiable
print(f"LEDGER|{1 if ledger_broken else 0}|{len(stale)}|{len(unrecorded)}|{len(unverifiable)}")

# ── Human detail (stderr) ──────────────────────────────────────────────────
if uncovered_changed:
    print("Uncovered changed lines:", file=sys.stderr)
    for ul in uncovered_changed[:30]:
        marker = "" if ul in ledger_pairs else "  (NOT in ledger)"
        print(f"  {ul}{marker}", file=sys.stderr)
    if len(uncovered_changed) > 30:
        print(f"  ... and {len(uncovered_changed) - 30} more", file=sys.stderr)
if stale:
    print("STALE ledger entries (now covered — DELETE these):", file=sys.stderr)
    for s in stale:
        print(f"  {s}", file=sys.stderr)
if unverifiable:
    print("Ledger entries not verifiable this build (drift check):", file=sys.stderr)
    for u in unverifiable:
        print(f"  {u}", file=sys.stderr)
PYEOF
)

# ── Parse the two summary lines ────────────────────────────────────────────
RATIO_LINE=$(printf '%s\n' "$RESULT" | grep '^RATIO|' || true)
LEDGER_LINE=$(printf '%s\n' "$RESULT" | grep '^LEDGER|' || true)

IFS='|' read -r _ COVERED MEASURABLE TOTAL PCT <<< "$RATIO_LINE"
IFS='|' read -r _ LEDGER_BROKEN NUM_STALE NUM_UNRECORDED NUM_UNVERIFIABLE <<< "$LEDGER_LINE"

# Defensive defaults if a field failed to parse.
: "${COVERED:=0}" "${MEASURABLE:=0}" "${TOTAL:=0}" "${PCT:=100.0}"
: "${LEDGER_BROKEN:=0}" "${NUM_STALE:=0}" "${NUM_UNRECORDED:=0}" "${NUM_UNVERIFIABLE:=0}"

# ── Decide the ratio dimension, applying the floor ─────────────────────────
BELOW_FLOOR=false
if [ "$MEASURABLE" -lt "$FLOOR" ]; then
  BELOW_FLOOR=true
fi

RATIO_FAIL=false
if [ "$MEASURABLE" -ge "$FLOOR" ] && awk "BEGIN{exit !($PCT < $THRESHOLD)}"; then
  RATIO_FAIL=true
fi

# ── Decide the ledger dimensions ───────────────────────────────────────────
# Staleness always applies. Completeness applies only below the floor (that is
# the blind spot the floor introduces; above the floor the ratio gate governs).
LEDGER_FAIL=false
if [ "$LEDGER_BROKEN" != "0" ]; then
  LEDGER_FAIL=true
fi
if [ "$NUM_STALE" -gt 0 ]; then
  LEDGER_FAIL=true
fi
if [ "$BELOW_FLOOR" = true ] && [ "$NUM_UNRECORDED" -gt 0 ]; then
  LEDGER_FAIL=true
fi

# ── Report ─────────────────────────────────────────────────────────────────
echo "--- :bar_chart: Diff-coverage results"
echo "Changed Java lines:  $TOTAL"
echo "Measurable lines:    $MEASURABLE (present in JaCoCo reports)"
echo "Covered lines:       $COVERED"
echo "Diff-coverage:       ${PCT}%"
echo "Threshold:           ${THRESHOLD}%"
echo "Floor:               ${FLOOR} measurable lines (ratio enforced at/above the floor)"
if [ "$BELOW_FLOOR" = true ]; then
  echo "Ratio enforcement:   OFF (below floor — reported only, not failing)"
else
  echo "Ratio enforcement:   ON (at/above floor)"
fi
echo "Ledger:              stale=$NUM_STALE  unrecorded-uncovered=$NUM_UNRECORDED  unverifiable=$NUM_UNVERIFIABLE"

# Echo the python human detail captured on stderr.
if [ -s "$TMPDIR/detail.txt" ]; then
  echo ""
  cat "$TMPDIR/detail.txt"
fi

STATUS="PASS"
if [ "$RATIO_FAIL" = true ] || [ "$LEDGER_FAIL" = true ]; then
  STATUS="FAIL"
fi
echo "Status:              $STATUS"

# ── Buildkite annotation ───────────────────────────────────────────────────
if [ -n "${BUILDKITE:-}" ]; then
  FLOOR_NOTE="ratio enforced (>= floor)"
  if [ "$BELOW_FLOOR" = true ]; then
    FLOOR_NOTE="below floor — ratio reported only"
  fi
  ANNOTATION="### Diff-Coverage: ${PCT}% (threshold: ${THRESHOLD}%, floor: ${FLOOR} lines)

| Metric | Value |
|---|---:|
| Changed Java lines | $TOTAL |
| Measurable (in JaCoCo) | $MEASURABLE |
| Covered | $COVERED |
| **Coverage** | **${PCT}%** |
| Floor state | ${FLOOR_NOTE} |
| Ledger: stale / unrecorded | ${NUM_STALE} / ${NUM_UNRECORDED} |"

  if [ "$STATUS" = "FAIL" ]; then
    echo "$ANNOTATION" | buildkite-agent annotate --style warning --context "diff-coverage"
  else
    echo "$ANNOTATION" | buildkite-agent annotate --style success --context "diff-coverage"
  fi
fi

# ── Fail with an actionable message ────────────────────────────────────────
if [ "$STATUS" = "FAIL" ]; then
  echo ""
  if [ "$RATIO_FAIL" = true ]; then
    echo "FAILED (ratio): diff-coverage ${PCT}% is below the ${THRESHOLD}% threshold"
    echo "  over ${MEASURABLE} measurable changed lines (>= ${FLOOR} floor)."
    echo "  Add tests for the changed lines, or raise the threshold if justified."
  fi
  if [ "$LEDGER_BROKEN" != "0" ]; then
    echo "FAILED (ledger): .buildkite/diff-coverage-ledger.json could not be parsed."
  fi
  if [ "$NUM_STALE" -gt 0 ]; then
    echo "FAILED (ledger): ${NUM_STALE} ledger entr(y/ies) are now COVERED and stale."
    echo "  Delete them from .buildkite/diff-coverage-ledger.json (the backlog only shrinks)."
  fi
  if [ "$BELOW_FLOOR" = true ] && [ "$NUM_UNRECORDED" -gt 0 ]; then
    echo "FAILED (ledger): ${NUM_UNRECORDED} uncovered changed line(s) below the floor are"
    echo "  neither covered nor recorded. Add a test, or record them in"
    echo "  .buildkite/diff-coverage-ledger.json so they stay observable."
  fi
  exit 1
fi

echo ""
echo "PASSED: diff-coverage ${PCT}% (threshold ${THRESHOLD}%, floor ${FLOOR}); ledger clean."
