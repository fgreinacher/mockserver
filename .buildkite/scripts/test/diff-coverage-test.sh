#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────────────────
# Self-test for diff-coverage.sh (floor + durable ledger)
# ────────────────────────────────────────────────────────────────────────────
# Builds throwaway git repos with synthetic JaCoCo XML + a synthetic diff and
# drives diff-coverage.sh through each dimension, asserting exit status AND that
# the ratio is still printed below the floor. Proves DISCRIMINATION: the gate
# fails when it should and passes when it should — a floor that never fails is
# as useless as one that always does.
#
# Run:  .buildkite/scripts/test/diff-coverage-test.sh
# No CI wiring, no Docker, no Maven — pure git + python + bash.
# ────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$SCRIPT_DIR/../steps/diff-coverage.sh"

PASS=0
FAIL=0

# emit_jacoco <xml-path> <pkg> <file> <covered-lines-csv> <uncovered-lines-csv>
emit_jacoco() {
  local out="$1" pkg="$2" file="$3" cov="$4" unc="$5"
  mkdir -p "$(dirname "$out")"
  {
    echo "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
    echo "<report name=\"t\"><package name=\"$pkg\"><sourcefile name=\"$file\">"
    IFS=',' read -ra C <<< "$cov"
    for n in ${C[@]+"${C[@]}"}; do [ -n "$n" ] && echo "<line nr=\"$n\" mi=\"0\" ci=\"3\"/>"; done
    IFS=',' read -ra U <<< "$unc"
    for n in ${U[@]+"${U[@]}"}; do [ -n "$n" ] && echo "<line nr=\"$n\" mi=\"3\" ci=\"0\"/>"; done
    echo "</sourcefile></package></report>"
  } > "$out"
}

# new_repo <dir> <n-lines>  → prints BASE sha on stdout; leaves a repo whose
# HEAD ADDS mod/src/main/java/org/example/Foo.java with <n-lines> lines.
new_repo() {
  local dir="$1" n="$2"
  git -C "$dir" init -q
  git -C "$dir" config user.email t@t; git -C "$dir" config user.name t
  echo seed > "$dir/seed.txt"
  git -C "$dir" add seed.txt; git -C "$dir" commit -qm base
  local base; base=$(git -C "$dir" rev-parse HEAD)
  local src="$dir/mod/src/main/java/org/example/Foo.java"
  mkdir -p "$(dirname "$src")"
  { for i in $(seq 1 "$n"); do echo "int x$i = $i;"; done; } > "$src"
  git -C "$dir" add "$src"; git -C "$dir" commit -qm add
  echo "$base"
}

csv_range() { seq -s, "$1" "$2"; }

# run_case <name> <expected-exit> <expect-substr> — repo already prepared in $WS
run_case() {
  local name="$1" want_exit="$2" want_sub="$3"
  local out ec
  set +e
  out=$(cd "$WS" && DIFF_COVERAGE_LEDGER="$WS/ledger.json" MERGE_BASE="$BASE" \
        bash "$GATE" 2>&1); ec=$?
  set -e
  local ok=1
  [ "$ec" = "$want_exit" ] || ok=0
  printf '%s\n' "$out" | grep -q -- "$want_sub" || ok=0
  if [ "$ok" = 1 ]; then
    echo "  PASS: $name (exit=$ec, matched: $want_sub)"; PASS=$((PASS+1))
  else
    echo "  FAIL: $name (exit=$ec want=$want_exit; wanted substr: $want_sub)"
    echo "  ---- output ----"; printf '%s\n' "$out" | sed 's/^/    /'
    FAIL=$((FAIL+1))
  fi
}

ledger_empty() { printf '{ "entries": [] }\n' > "$WS/ledger.json"; }
# ledger_lines <line...> — record Foo.java:<line> entries
ledger_lines() {
  local entries=""
  for n in "$@"; do
    entries="$entries{\"file\":\"mod/src/main/java/org/example/Foo.java\",\"line\":$n,\"symbol\":\"x$n\"},"
  done
  entries="${entries%,}"
  printf '{ "entries": [ %s ] }\n' "$entries" > "$WS/ledger.json"
}

echo "=== (a) n>=25 measurable, <70% covered  → expect FAIL (ratio) ==="
WS=$(mktemp -d); BASE=$(new_repo "$WS" 30)
# 30 measurable: 15 covered, 15 uncovered = 50% < 70%
emit_jacoco "$WS/mod/target/site/jacoco/jacoco.xml" org/example Foo.java \
  "$(csv_range 1 15)" "$(csv_range 16 30)"
ledger_empty
run_case "a: large under-covered diff fails on ratio" 1 "FAILED (ratio)"
rm -rf "$WS"

echo "=== (b) n<25 measurable, <70% covered, all recorded → expect PASS + ratio printed ==="
WS=$(mktemp -d); BASE=$(new_repo "$WS" 6)
# 6 measurable, 0 covered = 0% but below the 25-line floor
emit_jacoco "$WS/mod/target/site/jacoco/jacoco.xml" org/example Foo.java \
  "" "$(csv_range 1 6)"
ledger_lines 1 2 3 4 5 6
run_case "b: sub-floor miss passes (ratio not enforced)" 0 "PASSED"
run_case "b: ratio still printed below floor" 0 "Diff-coverage:       0.0%"
run_case "b: floor state reported" 0 "Ratio enforcement:   OFF"
rm -rf "$WS"

echo "=== (b') n<25, <70%, NOT recorded → expect FAIL (ledger completeness gives the ledger teeth) ==="
WS=$(mktemp -d); BASE=$(new_repo "$WS" 6)
emit_jacoco "$WS/mod/target/site/jacoco/jacoco.xml" org/example Foo.java \
  "" "$(csv_range 1 6)"
ledger_empty
run_case "b': unrecorded sub-floor miss is not silently dropped" 1 "FAILED (ledger)"
rm -rf "$WS"

echo "=== (c) ledger entry now COVERED, empty diff → expect FAIL (stale) ==="
WS=$(mktemp -d); BASE=$(new_repo "$WS" 3)
# reset HEAD back to base so the diff is EMPTY — staleness must run anyway
git -C "$WS" reset -q --hard "$BASE"
# Foo.java is gone after reset; reproduce a jacoco report that marks line 2 COVERED
emit_jacoco "$WS/mod/target/site/jacoco/jacoco.xml" org/example Foo.java "2" ""
ledger_lines 2
run_case "c: stale (now-covered) ledger entry fails independent of the diff" 1 "stale"
rm -rf "$WS"

echo "=== (d) n>=25 measurable, >=70% covered → expect PASS (discrimination: it CAN pass above floor) ==="
WS=$(mktemp -d); BASE=$(new_repo "$WS" 30)
# 30 measurable: 24 covered, 6 uncovered = 80% >= 70%
emit_jacoco "$WS/mod/target/site/jacoco/jacoco.xml" org/example Foo.java \
  "$(csv_range 1 24)" "$(csv_range 25 30)"
ledger_empty
run_case "d: well-covered large diff passes" 0 "PASSED"
rm -rf "$WS"

echo ""
echo "=== summary: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" -eq 0 ]
