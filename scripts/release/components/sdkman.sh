#!/usr/bin/env bash
# Publish the MockServer CLI to SDKMAN! via the Vendor API. SDKMAN unpacks the
# self-contained per-platform bundle (each carries its own trimmed Java runtime)
# and puts its bin/ on PATH.
#
# Prerequisite (documented in packaging/sdkman/release-component.md): a SDKMAN!
# vendor account whose consumer key + token live in the Secrets Manager secret
# mockserver-release/sdkman-vendor (fields `consumer-key`, `consumer-token`).
# If the secret is not configured, this component skips gracefully so the
# release is never blocked.
#
# Dry-run: verify bundle availability and print the Vendor API calls that would
# be made, without hitting the API.

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
skip_unless_release_type "sdkman" full,post-maven

SECRET="mockserver-release/sdkman-vendor"
SDKMAN_API="https://vendors.sdkman.io"

log_step "Publish SDKMAN! candidate $RELEASE_VERSION (dry-run=$DRY_RUN)"

if ! is_dry_run && ! pm_secret_available "$SECRET"; then
  log_info "$SECRET not configured — skipping SDKMAN! (see packaging/sdkman/release-component.md)"
  exit 0
fi

# SDKMAN platform id -> bundle archive.
bundle_for_platform() {
  case "$1" in
    LINUX_64)    bundle_asset_name linux   x86_64  tar.gz ;;
    LINUX_ARM64) bundle_asset_name linux   aarch64 tar.gz ;;
    MAC_OSX)     bundle_asset_name darwin  x86_64  tar.gz ;;
    MAC_ARM64)   bundle_asset_name darwin  aarch64 tar.gz ;;
    WINDOWS_64)  bundle_asset_name windows x86_64  zip ;;
  esac
}

# Confirm the bundles exist before registering (skip in execute if not).
MISSING=false
for platform in LINUX_64 LINUX_ARM64 MAC_OSX MAC_ARM64 WINDOWS_64; do
  asset="$(bundle_for_platform "$platform")"
  if fetch_release_sha256 "$asset" >/dev/null; then :; else MISSING=true; fi
done
if $MISSING && ! is_dry_run; then
  log_info "One or more release bundles not found on GitHub Release — skipping SDKMAN! (bundles not published yet?)"
  exit 0
fi

# Load credentials only for a real publish; in dry-run the curls are wrapped by
# dry_run_or and never fire, so placeholder values are enough (and we avoid
# touching AWS / load_secret during a local smoke test).
if is_dry_run; then
  SDKMAN_KEY="DRY_RUN_PLACEHOLDER"
  SDKMAN_TOKEN="DRY_RUN_PLACEHOLDER"
else
  SDKMAN_KEY="$(load_secret "$SECRET" "consumer-key")"
  SDKMAN_TOKEN="$(load_secret "$SECRET" "consumer-token")"
fi

log_info "Register version $RELEASE_VERSION for each platform"
for platform in LINUX_64 LINUX_ARM64 MAC_OSX MAC_ARM64 WINDOWS_64; do
  asset="$(bundle_for_platform "$platform")"
  url="$(release_download_base)/$asset"
  dry_run_or "Register $platform ($asset)" \
    curl -fsSL -X POST "$SDKMAN_API/release" \
      -H "Consumer-Key: $SDKMAN_KEY" \
      -H "Consumer-Token: $SDKMAN_TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"candidate\":\"mockserver\",\"version\":\"$RELEASE_VERSION\",\"platform\":\"$platform\",\"url\":\"$url\"}"
done

# Assumes releases run in ascending order (linear release history), so the
# version just published is always the newest and safe to set as the SDKMAN
# default. A re-publish of an older patch would need this block skipped.
dry_run_or "Set $RELEASE_VERSION as default" \
  curl -fsSL -X PUT "$SDKMAN_API/default" \
    -H "Consumer-Key: $SDKMAN_KEY" \
    -H "Consumer-Token: $SDKMAN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"candidate\":\"mockserver\",\"version\":\"$RELEASE_VERSION\"}"

dry_run_or "Announce release" \
  curl -fsSL -X POST "$SDKMAN_API/announce/struct" \
    -H "Consumer-Key: $SDKMAN_KEY" \
    -H "Consumer-Token: $SDKMAN_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"candidate\":\"mockserver\",\"version\":\"$RELEASE_VERSION\",\"hashtag\":\"mockserver\"}"

unset SDKMAN_KEY SDKMAN_TOKEN
log_info "SDKMAN! publish complete"
