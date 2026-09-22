#!/usr/bin/env bash
# Schema validation for mockserver-performance-test/perf-budgets.json.
#
# WHY THIS EXISTS. Both perf gates already checked that the budget file exists,
# parses, and has a `budgets` object. None of them checked the one property the
# gates actually depend on: that each entry's numbers are NUMBERS. jq has a total
# ordering across types — null < false < true < numbers < strings — so a single
# pair of quotes in a hand-edited budget silently rewrites the gate:
#
#   perf-alloc-gate.sh   `$alloc <= $floor`  with floor "5000"  -> ALWAYS true.
#                        Every allocation passes. The blocking gate is off.
#   perf-test-compare.sh `[$med + 3*$sigma, $floor] | max`      -> the STRING wins
#                        (strings sort above numbers), so the threshold becomes a
#                        string and `$value > $threshold` is ALWAYS false. The
#                        regression can never fire.
#   perf-test-compare.sh `[$med - 3*$sigma, $floor] | min`      -> the NUMBER wins,
#                        so the floor is silently dropped and only the MAD band
#                        applies.
#
# Every one of those is a false ALL-CLEAR: the gate reports green while proving
# nothing, which is worse than having no gate, because nobody looks again. The
# type is therefore validated at the file boundary, and the consuming predicates
# guard their own operands as well — the boundary check alone is not enough,
# because a predicate can be reached by a path that did not come through here.
#
# `dir` is validated for the same reason: perf-test-compare.sh branches on
# `if $m.dir=="up" ... else <down> end`, so a missing or misspelled direction does
# not error — it silently takes the "down" branch and compares a bigger-is-worse
# metric as though smaller were worse, which for most metrics means it can never
# fire either.
#
# Usage:
#   source .buildkite/scripts/lib/perf-budgets-validate.sh
#   if ! reason="$(validate_perf_budgets "$BUDGETS_FILE")"; then ... fail ... fi
#
#   bash .buildkite/scripts/lib/perf-budgets-validate.sh --self-test
#     Proves the validator rejects each hostile shape it claims to reject, and
#     accepts valid ones. It deliberately takes NO file argument and never reads
#     the real budget file: "the validator works" and "this run's budget file is
#     valid" are separate questions, and a self-test that also judged the real file
#     would report a bad budget as a broken validator. The caller asks the second
#     question itself, with validate_perf_budgets.

# THE ENTRY CONTRACT. `dir` (\"up\"/\"down\"), `min_pct` (number >= 0) and `floor`
# (number, or null for \"no absolute floor\") are REQUIRED on every entry; `gating`
# and `provisional` are optional booleans. No other key is permitted -- an entry
# is rejected if it carries one, because every value check here is conditional on
# its key being present, so a misspelled key name would pass them all while
# reverting that setting to its default. `floor` must be spelled out as null
# rather than omitted for the same reason: absence and \"deliberately no floor\"
# must not look alike.
#
# Keys beginning with `_` are prose/comment entries (e.g. `_comment_streaming`,
# whose value is an array of strings), never budgets. They are skipped.
#
# Prints a human-readable reason for the FIRST problem found, and returns 1.
# Prints nothing and returns 0 when the file is well-formed.
validate_perf_budgets() {
  local file="$1"

  if [ ! -f "$file" ]; then
    echo "budget file not found: $file"
    return 1
  fi
  if ! jq empty "$file" >/dev/null 2>&1; then
    echo "budget file is not valid JSON: $file"
    return 1
  fi
  if [ "$(jq -r '(.budgets | type) // "null"' "$file" 2>/dev/null)" != "object" ]; then
    echo "budget file has no \`budgets\` object: $file"
    return 1
  fi

  local problems
  problems="$(jq -r '
    [ .budgets
      | to_entries[]
      | select(.key | startswith("_") | not)
      | .key as $k
      | .value as $v
      | if ($v | type) != "object" then
          "\($k): entry is \($v|type), expected an object"
        # Reject UNKNOWN keys before checking any value. Every other check is
        # conditional on the key being PRESENT, so a misspelled key name would
        # sail through all of them while silently reverting that setting to its
        # default -- and the default for `gating` is notify-only, i.e. a typo
        # turns a build-failing metric into one that only annotates. The value
        # checks cannot see that; only the key set can.
        elif (($v | keys) - ["dir","min_pct","floor","gating","provisional","hw"] | length) > 0 then
          "\($k): unrecognised key(s) \((($v | keys) - ["dir","min_pct","floor","gating","provisional","hw"]) | tojson) -- a misspelled key does not error, it silently reverts that setting to its default (a typo of `gating` makes a build-failing metric notify-only)"
        elif (($v | has("dir")) and ($v | has("min_pct")) and ($v | has("floor"))) | not then
          ((["dir","min_pct","floor"] - ($v | keys)) as $missing
           | "\($k): missing required key(s) \($missing | tojson) -- absence is not a usable default"
             + (if ($missing | index("floor")) then ", state `\"floor\": null` explicitly to mean no absolute floor" else "" end))
        elif ($v.dir != "up" and $v.dir != "down") then
          "\($k): dir is \($v.dir | tojson), expected \"up\" or \"down\" (an unrecognised dir silently takes the bigger-is-better branch)"
        elif (($v.min_pct | type) != "number") then
          "\($k): min_pct is \($v.min_pct | tojson) of type \($v.min_pct|type), expected a number"
        elif ($v.min_pct < 0) then
          "\($k): min_pct is \($v.min_pct), expected >= 0"
        elif (($v | has("floor")) and $v.floor != null and (($v.floor | type) != "number")) then
          "\($k): floor is \($v.floor | tojson) of type \($v.floor|type), expected a number or null -- a quoted floor silently disables this budget"
        elif (($v | has("gating")) and (($v.gating | type) != "boolean")) then
          "\($k): gating is \($v.gating | tojson), expected true or false"
        elif (($v | has("provisional")) and (($v.provisional | type) != "boolean")) then
          "\($k): provisional is \($v.provisional | tojson), expected true or false"
        elif (($v | has("hw")) and (($v.hw | type) != "boolean")) then
          "\($k): hw is \($v.hw | tojson), expected true or false -- jq treats ANY non-null, non-false value as true, so the string \"false\" would mark the metric hardware-sensitive and silently stop it comparing across machines"
        else empty end
    ] | .[]' "$file" 2>&1)"

  if [ -n "$problems" ]; then
    echo "$problems"
    return 1
  fi
  return 0
}

# --- self-test ----------------------------------------------------------------
# A validator nobody degrades is just more code that looks like a check. Each
# fixture below is a budget file the gates would MIS-read, and the self-test
# fails unless the validator rejects it. Valid shapes are asserted too, so a
# validator that rejects everything cannot masquerade as working either.
_perf_budgets_self_test() {
  local tmp failures=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT

  _expect_reject() {
    local name="$1" json="$2" reason
    printf '%s' "$json" > "$tmp/b.json"
    if reason="$(validate_perf_budgets "$tmp/b.json")"; then
      echo "  FAIL  $name -- validator ACCEPTED a file it must reject"
      failures=$((failures + 1))
    else
      echo "  ok    $name -- rejected: ${reason%%$'\n'*}"
    fi
  }

  echo "--- perf-budgets validator self-test"
  _expect_reject "quoted floor (disables alloc gate and up-direction compare)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":"5000"}}}'
  _expect_reject "quoted min_pct" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":"0.1","floor":5000}}}'
  # `hw` routes a metric to a same-instance-type baseline. jq treats ANY
  # non-null, non-false value as true, so a quoted "false" would mark the metric
  # hardware-sensitive and silently stop it comparing across machines -- the
  # metric would sit at `no-baseline` looking like a warm-up rather than a typo.
  _expect_reject "quoted hw (silently marks the metric hardware-sensitive)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":5000,"hw":"false"}}}'
  # Honest about what this one proves: `hwe` is caught by the generic unknown-key
  # branch whether or not `hw` exists, so it does NOT discriminate the hw feature.
  # It is kept as a regression check on the allow-list EDIT itself — that widening
  # the permitted set to include `hw` did not accidentally stop unknown keys being
  # rejected.
  _expect_reject "unknown key still rejected after widening the allow-list for hw" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":5000,"hwe":true}}}'
  _expect_reject "missing dir (silently takes the down branch)" \
    '{"budgets":{"a.b.c":{"min_pct":0.1,"floor":5000}}}'
  _expect_reject "misspelled dir" \
    '{"budgets":{"a.b.c":{"dir":"UP","min_pct":0.1,"floor":5000}}}'
  _expect_reject "string gating flag (\"false\" is truthy nowhere but reads as set)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":5000,"gating":"true"}}}'
  _expect_reject "negative min_pct" \
    '{"budgets":{"a.b.c":{"dir":"down","min_pct":-0.1,"floor":5000}}}'
  _expect_reject "TYPO'd gating key (silently makes a gating metric notify-only)" \
    '{"budgets":{"microbench.x.time_per_op":{"dir":"up","min_pct":0.05,"floor":null,"gatng":true}}}'
  _expect_reject "unknown sibling key" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":5000,"unexpected":123}}}'
  _expect_reject "missing floor (absence must be stated as null, not omitted)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1}}}'
  _expect_reject "entry is not an object" \
    '{"budgets":{"a.b.c":[1,2,3]}}'
  _expect_reject "no budgets object" \
    '{"nope":{}}'

  # Shapes that MUST be accepted, so the validator cannot pass by rejecting all.
  _expect_accept() {
    local name="$1" json="$2" reason
    printf '%s' "$json" > "$tmp/b.json"
    if reason="$(validate_perf_budgets "$tmp/b.json")"; then
      echo "  ok    $name -- accepted"
    else
      echo "  FAIL  $name -- validator REJECTED a valid file: $reason"
      failures=$((failures + 1))
    fi
  }
  _expect_accept "null floor (MAD band only)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":null,"gating":false}}}'
  _expect_accept "well-formed hw:true (the real shape this change adds)" \
    '{"budgets":{"a.b.c":{"dir":"up","min_pct":0.1,"floor":5000,"hw":true}}}'
  _expect_accept "comment entry holding an array" \
    '{"budgets":{"_comment_x":["prose","more prose"],"a.b.c":{"dir":"down","min_pct":0,"floor":1}}}'

  if [ "$failures" -ne 0 ]; then
    echo "--- perf-budgets validator self-test: $failures failure(s)" >&2
    return 1
  fi
  echo "--- perf-budgets validator self-test: all checks passed"
  return 0
}

# Run the self-test when invoked directly rather than sourced.
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  case "${1:-}" in
    --self-test)
      _perf_budgets_self_test
      exit $?
      ;;
    *)
      echo "usage: $0 --self-test" >&2
      echo "       (or source this file and call validate_perf_budgets <file>)" >&2
      exit 2
      ;;
  esac
fi
