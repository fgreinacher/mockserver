#!/usr/bin/env bash
# Publish the MockServer CLI manifest to winget (microsoft/winget-pkgs) via a PR.
# winget installs the self-contained Windows zip bundle (bundles its own Java
# runtime) and exposes its nested bin/mockserver.bat launcher as `mockserver`.
#
# Prerequisites (documented in packaging/winget/release-component.md):
#   - mockserver-release/winget-github-token: a GitHub PAT that can open PRs on
#     microsoft/winget-pkgs (from a fork).
#   - wingetcreate on PATH (Windows-only tool). PR submission runs on a Windows
#     agent; on non-Windows agents the manifest is still rendered for review.
# If either is absent the component renders the manifest and skips submission,
# so the release is never blocked.
#
# Dry-run: resolve the checksum, render + print the manifest, skip submission.

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
skip_unless_release_type "winget" full,post-maven

SECRET="mockserver-release/winget-github-token"

log_step "Publish winget manifest $RELEASE_VERSION (dry-run=$DRY_RUN)"

WORK_DIR="$REPO_ROOT/.tmp/winget-$RELEASE_VERSION"
rm -rf "$WORK_DIR"; mkdir -p "$WORK_DIR"

WIN_ASSET="$(bundle_asset_name windows x86_64 zip)"
WIN_URL="$(release_download_base)/$WIN_ASSET"
if WIN_HASH="$(fetch_release_sha256 "$WIN_ASSET")"; then
  log_info "Resolved checksum for $WIN_ASSET"
elif ! is_dry_run; then
  log_info "Release bundle $WIN_ASSET not found on GitHub Release — skipping winget (bundles not published yet?)"
  exit 0
else
  log_dry "checksum for $WIN_ASSET unresolved — rendering manifest with placeholder"
fi

log_info "Render manifest"
MANIFEST="$WORK_DIR/MockServer.MockServer.yaml"
sed -e "s/\${VERSION}/$RELEASE_VERSION/g" \
    -e "s/\${SHA256_WINDOWS_X86_64}/$WIN_HASH/g" \
    "$REPO_ROOT/packaging/winget/MockServer.MockServer.yaml" > "$MANIFEST"

log_info "Manifest content:"
sed 's/^/    /' "$MANIFEST"

if is_dry_run; then
  log_dry "skip: submit winget PR for $RELEASE_VERSION"
  log_dry "would: wingetcreate update MockServer.MockServer --version $RELEASE_VERSION --urls $WIN_URL --submit --token <$SECRET>"
elif ! pm_secret_available "$SECRET"; then
  log_info "$SECRET not configured — skipping winget PR submission (manifest rendered at $MANIFEST; see packaging/winget/release-component.md)"
  exit 0
elif ! command -v wingetcreate >/dev/null 2>&1; then
  log_info "wingetcreate not available on this agent (Windows-only) — skipping winget PR submission (manifest rendered at $MANIFEST; see packaging/winget/release-component.md)"
  exit 0
else
  WINGET_TOKEN="$(load_secret "$SECRET" "token")"
  log_info "Submitting winget manifest PR via wingetcreate"
  wingetcreate update MockServer.MockServer \
    --version "$RELEASE_VERSION" \
    --urls "$WIN_URL" \
    --submit \
    --token "$WINGET_TOKEN"
  unset WINGET_TOKEN
fi

rm -rf "$WORK_DIR"
log_info "winget publish complete"
