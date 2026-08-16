#!/usr/bin/env bash
#
# github-stars.sh — print the EXACT GitHub star count for a repository.
#
# The GitHub web UI rounds anything over 1,000 ("4.9k"), so this reports the
# precise number instead. With no arguments it reports the MockServer monorepo.
#
# Usage:
#   scripts/github-stars.sh [options] [owner/repo ...]
#
# Options:
#   -a, --all          report every non-fork repo owned by each --owner, sorted
#                      by stars, with a total (requires the gh CLI)
#   -o, --owner NAME   owner (user or org) to enumerate; repeatable, implies
#                      --all (default owners: mock-server, jamesdbloom)
#   -n, --number       print just the number(s), unformatted, for scripting;
#                      with --all this is the grand total, not one line per repo
#   -h, --help         show this help
#
# Examples:
#   scripts/github-stars.sh
#   scripts/github-stars.sh mock-server/mockserver-node mock-server/mockserver-ui
#   scripts/github-stars.sh --number
#   scripts/github-stars.sh --all
#   scripts/github-stars.sh --owner mock-server
#
# Uses the locally-authenticated `gh` CLI when available, otherwise falls back to
# the unauthenticated public REST API (rate limited to 60 requests/hour, and
# private repos are invisible). --all always requires gh.
#
# Exit codes: 0 ok, 1 lookup failed, 4 missing prerequisite.
#
set -euo pipefail

DEFAULT_REPO="mock-server/mockserver-monorepo"
DEFAULT_OWNERS=(mock-server jamesdbloom)

ALL=0
NUMBER=0
OWNERS=()
REPOS=()

usage() { sed -n '2,31p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

# Fail with a usable message rather than a `set -u` "unbound variable" when an
# option that takes a value is given without one.
needs_value() { [ "$2" -ge 2 ] || { echo "option $1 requires an argument" >&2; usage 1; }; }

while [ $# -gt 0 ]; do
  case "$1" in
    -a|--all)    ALL=1; shift;;
    -o|--owner)  needs_value "$1" "$#"; OWNERS+=("$2"); ALL=1; shift 2;;
    -n|--number) NUMBER=1; shift;;
    -h|--help)   usage 0;;
    --) shift; while [ $# -gt 0 ]; do REPOS+=("$1"); shift; done;;
    -*) echo "unknown option: $1" >&2; usage 1;;
    *) REPOS+=("$1"); shift;;
  esac
done

have() { command -v "$1" >/dev/null 2>&1; }

# Insert thousands separators: 4941 -> 4,941
commas() { printf '%s' "$1" | sed -e :a -e 's/\(.*[0-9]\)\([0-9]\{3\}\)/\1,\2/;ta'; }

# stars_for owner/repo -> the raw star count on stdout, non-zero if not found.
stars_for() {
  local repo="$1" out=""
  if have gh; then
    out="$(gh api "repos/${repo}" --jq '.stargazers_count' 2>/dev/null || true)"
  fi
  if [ -z "$out" ] && have curl && have python3; then
    out="$(curl -fsSL -H 'Accept: application/vnd.github+json' \
             "https://api.github.com/repos/${repo}" 2>/dev/null \
           | python3 -c 'import sys,json; print(json.load(sys.stdin).get("stargazers_count",""))' \
             2>/dev/null || true)"
  fi
  case "$out" in
    ''|*[!0-9]*) return 1;;
  esac
  printf '%s\n' "$out"
}

if [ "$ALL" -eq 1 ]; then
  have gh || { echo "FAIL: --all needs the gh CLI (brew install gh; gh auth login)" >&2; exit 4; }
  [ "${#OWNERS[@]}" -gt 0 ] || OWNERS=("${DEFAULT_OWNERS[@]}")

  # Fail closed per owner: inside a `... | sort` pipeline a failing gh would be
  # masked and silently yield a partial total.
  rows=""
  for owner in "${OWNERS[@]}"; do
    if ! owner_rows="$(gh repo list "$owner" --source --limit 1000 \
                         --json nameWithOwner,stargazerCount \
                         --jq '.[] | [.stargazerCount, .nameWithOwner] | @tsv')"; then
      echo "FAIL: could not list repos for owner: ${owner}" >&2
      exit 1
    fi
    [ -z "$owner_rows" ] || rows="${rows}${owner_rows}"$'\n'
  done
  [ -n "$rows" ] || { echo "FAIL: no repos found for: ${OWNERS[*]}" >&2; exit 1; }
  rows="$(printf '%s' "$rows" | sort -k1,1nr)"

  if [ "$NUMBER" -eq 1 ]; then
    printf '%s\n' "$rows" | awk -F'\t' '{ total += $1 } END { print total+0 }'
    exit 0
  fi

  printf '%s\n' "$rows" | awk -F'\t' '
    function commify(n,   s, out) {
      s = sprintf("%d", n); out = ""
      while (length(s) > 3) { out = "," substr(s, length(s) - 2) out; s = substr(s, 1, length(s) - 3) }
      return s out
    }
    { n++; star[n] = $1; name[n] = $2; total += $1; if (length($2) > w) w = length($2) }
    END {
      for (i = 1; i <= n; i++) printf "%-*s  %8s\n", w, name[i], commify(star[i])
      rule = ""; while (length(rule) < w + 10) rule = rule "-"
      print rule
      printf "%-*s  %8s\n", w, sprintf("TOTAL (%d repos)", n), commify(total)
    }'
  exit 0
fi

[ "${#REPOS[@]}" -gt 0 ] || REPOS=("$DEFAULT_REPO")

# Widest repo name, so several repos line up in a column.
width=0
for repo in "${REPOS[@]}"; do
  [ "${#repo}" -le "$width" ] || width="${#repo}"
done

status=0
for repo in "${REPOS[@]}"; do
  case "$repo" in
    */*) ;;
    *) echo "FAIL: expected owner/repo, got: ${repo}" >&2; status=1; continue;;
  esac
  if ! count="$(stars_for "$repo")"; then
    echo "FAIL: could not read stars for ${repo} (missing or private repo, no network, or rate limited)" >&2
    status=1
    continue
  fi
  if [ "$NUMBER" -eq 1 ]; then
    printf '%s\n' "$count"
  else
    printf '%-*s  %8s\n' "$width" "$repo" "$(commas "$count")"
  fi
done
exit "$status"
