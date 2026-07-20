#!/usr/bin/env bash
#
# Guards the invariant that every client library pins the SAME MockServer binary version.
#
# Each client decides for itself which server binary its launcher downloads, via seven different
# mechanisms (hardcoded constant, embedded VERSION file, Cargo package version, assembly metadata,
# package.json). Three of them had no release-time bump hook at all and silently drifted: Python and
# PHP sat at 7.1.0 and Rust at 7.3.0 while the repo released 7.4.0 — so those clients fetched a
# three-minor-old server, and the shared binary cache the docs promise was never actually shared.
#
# The expected value is the last RELEASED version, derived from the Maven version with any
# -SNAPSHOT suffix stripped and the patch decremented when it is a snapshot (7.4.1-SNAPSHOT means
# 7.4.0 is the newest thing a user can download).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

maven_version="$(grep -m1 -oE '<version>[^<]+</version>' "$REPO_ROOT/mockserver/pom.xml" \
  | sed -E 's:</?version>::g')"

if [[ "$maven_version" != *-SNAPSHOT ]]; then
  # on a release commit the Maven version IS the release
  expected="$maven_version"
else
  # Mid-development: the newest thing a user can download is the last RELEASED version, read from the
  # topmost released changelog heading ("## [X.Y.Z] - date"; [Unreleased] has no version and is
  # skipped by the digit-anchored pattern).
  #
  # Read from changelog.md rather than `git tag` because CI checkouts are frequently shallow and/or
  # tagless, and a tag-derived expectation would silently degrade to "skip" on exactly those builds —
  # a guard that quietly stops guarding. changelog.md is a committed file, always present.
  #
  # NOT derived by decrementing the Maven patch: that is wrong for every minor or major bump
  # (7.5.0-SNAPSHOT does not follow 7.5.-1) and would hard-fail this step on the release path.
  expected="$(grep -m1 -oE '^## \[[0-9]+\.[0-9]+\.[0-9]+\]' "$REPO_ROOT/changelog.md" \
    | tr -d '#[] ')"

  if [[ -z "$expected" ]]; then
    echo "ERROR: no released version heading (## [X.Y.Z]) found in changelog.md." >&2
    echo "       Cannot establish the expected client pin version." >&2
    exit 1
  fi
fi

echo "Maven version: $maven_version -> expecting client pins at $expected"

# label | file | regex capturing the version in group 1
CHECKS=(
  "python launcher|mockserver-client-python/mockserver/launcher.py|_CLIENT_VERSION = \"([^\"]+)\""
  "php launcher|mockserver-client-php/src/BinaryLauncher.php|private const DEFAULT_VERSION = '([^']+)'"
  # the .NET launcher prefers the assembly version and only falls back to the constant, so BOTH must
  # be checked — checking the fallback alone would have missed the value that actually ships
  "dotnet assembly version|mockserver-client-dotnet/src/MockServer.Client/MockServer.Client.csproj|<Version>([^<]+)</Version>"
  "dotnet launcher fallback|mockserver-client-dotnet/src/MockServer.Client/MockServerBinaryLauncher.cs|private const string FallbackVersion = \"([^\"]+)\""
  "go VERSION|mockserver-client-go/VERSION|^([0-9][^[:space:]]*)$"
  "rust Cargo.toml|mockserver-client-rust/Cargo.toml|^version = \"([^\"]+)\""
  "ruby version.rb|mockserver-client-ruby/lib/mockserver/version.rb|VERSION = '([^']+)'"
  "node package.json|mockserver-node/package.json|\"version\"[[:space:]]*:[[:space:]]*\"([^\"]+)\""
  "client-node package.json|mockserver-client-node/package.json|\"version\"[[:space:]]*:[[:space:]]*\"([^\"]+)\""
)

failed=0
for entry in "${CHECKS[@]}"; do
  IFS='|' read -r label rel_path regex <<< "$entry"
  path="$REPO_ROOT/$rel_path"

  if [[ ! -f "$path" ]]; then
    echo "FAIL  $label: $rel_path does not exist" >&2
    failed=1
    continue
  fi

  actual="$(grep -m1 -oE "$regex" "$path" | sed -E "s|$regex|\1|" || true)"

  if [[ -z "$actual" ]]; then
    echo "FAIL  $label: could not extract a version from $rel_path (has the file's format changed?)" >&2
    failed=1
  elif [[ "$actual" != "$expected" ]]; then
    echo "FAIL  $label: $rel_path pins $actual, expected $expected" >&2
    failed=1
  else
    echo "ok    $label: $actual"
  fi
done

if (( failed )); then
  cat >&2 <<EOF

Client binary-launcher versions are out of sync.

Every client must pin the same MockServer version so they all download the same server binary and
share one on-disk cache. If you are bumping the release version, scripts/release/prepare.sh updates
all of these — check that any newly added client is listed there AND here.
EOF
  exit 1
fi

echo "All client version pins agree on $expected"
