#!/usr/bin/env bash
# Keep the asdf/mise plugin repo (github.com/mock-server/asdf-mockserver) in sync
# and confirm the new version is discoverable.
#
# asdf/mise plugins resolve versions and download artifacts dynamically from
# GitHub Releases at `asdf install` time (via the bin/ scripts under
# packaging/asdf/), so there is no per-release "publish" step — only a plugin-repo
# sync plus a discoverability check.
#
# Prerequisite (documented in packaging/asdf/release-component.md): the plugin
# repo github.com/mock-server/asdf-mockserver must exist and be writable by the
# mockserver-release/github-token secret. If it is not reachable, this component
# skips gracefully so the release is never blocked.
#
# Dry-run: report what would be synced/verified without cloning or pushing.

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
require_cmd diff
require_release_inputs
skip_unless_release_type "asdf" full,post-maven

PLUGIN_REPO="mock-server/asdf-mockserver"

log_step "Sync asdf/mise plugin for $RELEASE_VERSION (dry-run=$DRY_RUN)"

if ! pm_repo_available "$PLUGIN_REPO"; then
  log_info "asdf plugin repo $PLUGIN_REPO not reachable — skipping (see packaging/asdf/release-component.md)"
  exit 0
fi

WORK_DIR="$REPO_ROOT/.tmp/asdf-$RELEASE_VERSION"
rm -rf "$WORK_DIR"; mkdir -p "$WORK_DIR"
# Remove the work dir (which will hold a transient clone) on EVERY exit path —
# set -e failures, early `exit` (incl. the "release not found" exit 1 below),
# signals — so nothing is ever left on disk.
trap 'rm -rf "$WORK_DIR"' EXIT

# 1. Sync plugin repo bin/ scripts with the source of truth in packaging/asdf/bin/.
if is_dry_run; then
  log_dry "skip: clone $PLUGIN_REPO and sync bin/ scripts"
else
  GITHUB_TOKEN=$(load_secret "mockserver-release/github-token" "token")
  # Inject auth per git-command via http.extraheader so the token is NEVER
  # written to disk (plain https remote URL, no token in .git/config). Mirrors
  # the F-BK-03 mitigation in _lib.sh configure_git_for_push. base64 without
  # newline wrap is portable across GNU/BSD base64.
  AUTH_HEADER="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$GITHUB_TOKEN" | base64 | tr -d '\n')"
  unset GITHUB_TOKEN
  git -c http.extraheader="$AUTH_HEADER" clone --depth 1 \
    "https://github.com/${PLUGIN_REPO}.git" \
    "$WORK_DIR/asdf-mockserver"

  DRIFT=false
  for script in list-all download install latest-stable; do
    if ! diff -q "$REPO_ROOT/packaging/asdf/bin/$script" \
                 "$WORK_DIR/asdf-mockserver/bin/$script" >/dev/null 2>&1; then
      log_info "  DRIFT: bin/$script differs from plugin repo"
      DRIFT=true
    fi
  done

  if [[ "$DRIFT" == "true" ]]; then
    log_info "Syncing plugin scripts to $PLUGIN_REPO"
    cp "$REPO_ROOT/packaging/asdf/bin/"* "$WORK_DIR/asdf-mockserver/bin/"
    (
      cd "$WORK_DIR/asdf-mockserver"
      git config user.email "release@mock-server.com"
      git config user.name "MockServer Release"
      git add bin/
      git commit -m "sync plugin scripts from mockserver-monorepo ($RELEASE_VERSION)"
      git -c http.extraheader="$AUTH_HEADER" push
    )
  else
    log_info "  Plugin scripts are in sync"
  fi
  unset AUTH_HEADER
fi

# 2. Verify the new version is discoverable via the GitHub Releases API.
log_info "Verify version $RELEASE_VERSION is discoverable"
FOUND=$(curl -fsSL "https://api.github.com/repos/mock-server/mockserver-monorepo/releases/tags/mockserver-$RELEASE_VERSION" 2>/dev/null \
  | grep -c '"tag_name"' || true)
if [[ "$FOUND" -gt 0 ]]; then
  log_info "  Release mockserver-$RELEASE_VERSION found on GitHub"
elif is_dry_run; then
  log_dry "Release mockserver-$RELEASE_VERSION not found yet (expected in dry-run)"
else
  log_error "  Release mockserver-$RELEASE_VERSION NOT found on GitHub — asdf users won't see it"
  exit 1
fi

log_info "asdf/mise verification complete"
