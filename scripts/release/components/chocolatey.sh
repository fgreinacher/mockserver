#!/usr/bin/env bash
# Publish the MockServer CLI package to Chocolatey (community.chocolatey.org).
# The package downloads and unpacks the self-contained Windows zip bundle
# (bundles its own Java runtime) and shims bin/mockserver.bat onto PATH.
#
# Prerequisites (documented in packaging/chocolatey/release-component.md):
#   - mockserver-release/chocolatey-api-key: the push API key (field `key`).
#   - `choco` on PATH (Windows / mono). Packing + push run on a Windows agent;
#     on other agents the nuspec + install script are rendered for review.
# If either is absent the component renders the package and skips the push, so
# the release is never blocked.
#
# Dry-run: resolve the checksum, render the nuspec + install script, skip pack
# and push.

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
skip_unless_release_type "chocolatey" full,post-maven

SECRET="mockserver-release/chocolatey-api-key"

log_step "Publish Chocolatey package $RELEASE_VERSION (dry-run=$DRY_RUN)"

WORK_DIR="$REPO_ROOT/.tmp/chocolatey-$RELEASE_VERSION"
rm -rf "$WORK_DIR"; mkdir -p "$WORK_DIR/tools"

WIN_ASSET="$(bundle_asset_name windows x86_64 zip)"
if WIN_HASH="$(fetch_release_sha256 "$WIN_ASSET")"; then
  log_info "Resolved checksum for $WIN_ASSET"
elif ! is_dry_run; then
  log_info "Release bundle $WIN_ASSET not found on GitHub Release — skipping Chocolatey (bundles not published yet?)"
  exit 0
else
  log_dry "checksum for $WIN_ASSET unresolved — rendering package with placeholder"
fi
log_info "  windows-x86_64: $WIN_HASH"

log_info "Render nuspec and install script"
sed -e "s/\${VERSION}/$RELEASE_VERSION/g" \
    "$REPO_ROOT/packaging/chocolatey/mockserver.nuspec" > "$WORK_DIR/mockserver.nuspec"
sed -e "s/\${SHA256_WINDOWS_X86_64}/$WIN_HASH/g" \
    "$REPO_ROOT/packaging/chocolatey/tools/chocolateyinstall.ps1" > "$WORK_DIR/tools/chocolateyinstall.ps1"
cp "$REPO_ROOT/packaging/chocolatey/tools/chocolateyuninstall.ps1" "$WORK_DIR/tools/"

log_info "nuspec content:"
sed 's/^/    /' "$WORK_DIR/mockserver.nuspec"

NUPKG="$WORK_DIR/mockserver.$RELEASE_VERSION.nupkg"

if is_dry_run; then
  log_dry "skip: choco pack + choco push $NUPKG"
elif ! pm_secret_available "$SECRET"; then
  log_info "$SECRET not configured — skipping Chocolatey push (package rendered at $WORK_DIR; see packaging/chocolatey/release-component.md)"
  exit 0
elif ! command -v choco >/dev/null 2>&1; then
  log_info "choco not available on this agent (Windows/mono only) — skipping Chocolatey push (package rendered at $WORK_DIR; see packaging/chocolatey/release-component.md)"
  exit 0
else
  ( cd "$WORK_DIR" && choco pack mockserver.nuspec )
  [[ -f "$NUPKG" ]] || { log_error "Expected nupkg not found: $NUPKG"; exit 1; }
  CHOCO_KEY="$(load_secret "$SECRET" "key")"
  log_info "Pushing $NUPKG to Chocolatey"
  choco push "$NUPKG" --source https://push.chocolatey.org/ --api-key "$CHOCO_KEY"
  unset CHOCO_KEY
fi

rm -rf "$WORK_DIR"
log_info "Chocolatey publish complete"
