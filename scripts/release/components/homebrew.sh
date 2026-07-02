#!/usr/bin/env bash
# Publish the MockServer CLI formula to the Homebrew tap
# (github.com/mock-server/homebrew-tap → Formula/mockserver.rb). The formula
# installs the self-contained per-platform bundle (each carries its own trimmed
# Java runtime — no separate JDK required).
#
# This is DISTINCT from the JAR-based `mockserver` formula in
# Homebrew/homebrew-core (bumped by BrewTestBot from Maven Central); that one is
# untouched by this pipeline. See docs/operations/release-process.md.
#
# Prerequisite (documented in packaging/homebrew/release-component.md): the tap
# repo github.com/mock-server/homebrew-tap must exist and be writable by the
# mockserver-release/github-token secret. If it is not reachable, this component
# skips gracefully so the release is never blocked.
#
# Dry-run: resolve the four bundle checksums, render + print the formula, skip
# the tap push.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_cmd curl
require_release_inputs
skip_unless_release_type "homebrew" full,post-maven

TAP_REPO="mock-server/homebrew-tap"

log_step "Publish Homebrew formula $RELEASE_VERSION (dry-run=$DRY_RUN)"

if ! pm_repo_available "$TAP_REPO"; then
  log_info "Homebrew tap repo $TAP_REPO not reachable — skipping (see packaging/homebrew/release-component.md)"
  exit 0
fi

WORK_DIR="$REPO_ROOT/.tmp/homebrew-$RELEASE_VERSION"
rm -rf "$WORK_DIR"; mkdir -p "$WORK_DIR"
# Remove the work dir (which will hold a transient clone) on EVERY exit path —
# set -e failures, early `exit`, signals — so nothing is ever left on disk.
trap 'rm -rf "$WORK_DIR"' EXIT

# placeholder token -> os arch  (tar.gz bundles; Homebrew has no windows target)
bundle_for() {
  case "$1" in
    DARWIN_AARCH64) bundle_asset_name darwin aarch64 tar.gz ;;
    DARWIN_X86_64)  bundle_asset_name darwin x86_64  tar.gz ;;
    LINUX_AARCH64)  bundle_asset_name linux  aarch64 tar.gz ;;
    LINUX_X86_64)   bundle_asset_name linux  x86_64  tar.gz ;;
  esac
}

SED_ARGS=(-e "s/\${VERSION}/$RELEASE_VERSION/g")
MISSING=false
for ph in DARWIN_AARCH64 DARWIN_X86_64 LINUX_AARCH64 LINUX_X86_64; do
  asset="$(bundle_for "$ph")"
  if hash_val="$(fetch_release_sha256 "$asset")"; then
    log_info "  ${asset}: $hash_val"
  else
    MISSING=true
    log_info "  ${asset}: (unresolved)"
  fi
  SED_ARGS+=(-e "s/\${SHA256_$ph}/$hash_val/g")
done

if $MISSING && ! is_dry_run; then
  log_info "One or more release bundles not found on GitHub Release — skipping Homebrew (bundles not published yet?)"
  exit 0
fi

log_info "Render formula"
FORMULA="$WORK_DIR/mockserver.rb"
sed "${SED_ARGS[@]}" "$REPO_ROOT/packaging/homebrew/mockserver.rb" > "$FORMULA"

log_info "Formula content:"
sed 's/^/    /' "$FORMULA"

if is_dry_run; then
  log_dry "skip: push formula to $TAP_REPO (Formula/mockserver.rb)"
else
  GITHUB_TOKEN=$(load_secret "mockserver-release/github-token" "token")
  # Inject auth per git-command via http.extraheader so the token is NEVER
  # written to disk (plain https remote URL, no token in .git/config). Mirrors
  # the F-BK-03 mitigation in _lib.sh configure_git_for_push. base64 without
  # newline wrap is portable across GNU/BSD base64.
  AUTH_HEADER="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$GITHUB_TOKEN" | base64 | tr -d '\n')"
  unset GITHUB_TOKEN
  TAP_DIR="$WORK_DIR/homebrew-tap"
  git -c http.extraheader="$AUTH_HEADER" clone "https://github.com/${TAP_REPO}.git" "$TAP_DIR"
  mkdir -p "$TAP_DIR/Formula"
  cp "$FORMULA" "$TAP_DIR/Formula/mockserver.rb"
  (
    cd "$TAP_DIR"
    git config user.email "release@mock-server.com"
    git config user.name "MockServer Release"
    git add Formula/mockserver.rb
    if git diff --cached --quiet; then
      log_info "Homebrew formula already up to date"
    else
      git commit -m "mockserver $RELEASE_VERSION"
      git -c http.extraheader="$AUTH_HEADER" push
    fi
  )
  unset AUTH_HEADER
fi

log_info "Homebrew publish complete"
