#!/usr/bin/env bash
# Publish the MockServer CLI manifest to Scoop (the mock-server/scoop-mockserver
# bucket). Scoop installs the self-contained Windows bundle (bundles its own
# Java runtime) and shims bin/mockserver.bat onto PATH.
#
# Prerequisite (documented in packaging/scoop/release-component.md): the bucket
# repo github.com/mock-server/scoop-mockserver must exist and be writable by the
# mockserver-release/github-token secret. If it is not reachable, this component
# skips gracefully so the release is never blocked.
#
# Dry-run: resolve the bundle checksum, render + print the manifest, skip the
# bucket push.

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
require_cmd jq
require_release_inputs
skip_unless_release_type "scoop" full,post-maven

BUCKET_REPO="mock-server/scoop-mockserver"

log_step "Publish Scoop manifest $RELEASE_VERSION (dry-run=$DRY_RUN)"

if ! pm_repo_available "$BUCKET_REPO"; then
  log_info "Scoop bucket repo $BUCKET_REPO not reachable — skipping (see packaging/scoop/release-component.md)"
  exit 0
fi

WORK_DIR="$REPO_ROOT/.tmp/scoop-$RELEASE_VERSION"
rm -rf "$WORK_DIR"; mkdir -p "$WORK_DIR"
# Remove the work dir (which will hold a transient clone) on EVERY exit path —
# set -e failures, early `exit`, signals — so nothing is ever left on disk.
trap 'rm -rf "$WORK_DIR"' EXIT

WIN_ASSET="$(bundle_asset_name windows x86_64 zip)"
if WIN_HASH="$(fetch_release_sha256 "$WIN_ASSET")"; then
  log_info "Resolved checksum for $WIN_ASSET"
elif ! is_dry_run; then
  log_info "Release bundle $WIN_ASSET not found on GitHub Release — skipping Scoop (bundles not published yet?)"
  exit 0
else
  log_dry "checksum for $WIN_ASSET unresolved — rendering manifest with placeholder"
fi
log_info "  windows-x86_64: $WIN_HASH"

log_info "Render manifest"
sed -e "s/\${VERSION}/$RELEASE_VERSION/g" \
    -e "s/\${SHA256_WINDOWS_X86_64}/$WIN_HASH/g" \
    "$REPO_ROOT/packaging/scoop/mockserver.json" > "$WORK_DIR/mockserver.json"

log_info "Manifest content:"
jq . "$WORK_DIR/mockserver.json" | sed 's/^/    /'

if is_dry_run; then
  log_dry "skip: push manifest to $BUCKET_REPO"
else
  GITHUB_TOKEN=$(load_secret "mockserver-release/github-token" "token")
  # Inject auth per git-command via http.extraheader so the token is NEVER
  # written to disk (plain https remote URL, no token in .git/config). Mirrors
  # the F-BK-03 mitigation in _lib.sh configure_git_for_push. base64 without
  # newline wrap is portable across GNU/BSD base64.
  AUTH_HEADER="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$GITHUB_TOKEN" | base64 | tr -d '\n')"
  unset GITHUB_TOKEN
  BUCKET_DIR="$WORK_DIR/scoop-mockserver"
  git -c http.extraheader="$AUTH_HEADER" clone "https://github.com/${BUCKET_REPO}.git" "$BUCKET_DIR"
  cp "$WORK_DIR/mockserver.json" "$BUCKET_DIR/mockserver.json"
  (
    cd "$BUCKET_DIR"
    git config user.email "release@mock-server.com"
    git config user.name "MockServer Release"
    git add mockserver.json
    if git diff --cached --quiet; then
      log_info "Scoop manifest already up to date"
    else
      git commit -m "mockserver $RELEASE_VERSION"
      git -c http.extraheader="$AUTH_HEADER" push
    fi
  )
  unset AUTH_HEADER
fi

log_info "Scoop publish complete"
