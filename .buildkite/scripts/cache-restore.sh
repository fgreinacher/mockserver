#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# cache-restore.sh -- Download a dependency cache tarball from S3
# ---------------------------------------------------------------------------
# FAIL-SAFE: This script MUST NEVER abort a build. Every external command
# (aws, tar, mkdir) is guarded so that missing buckets, missing credentials,
# permission errors, empty caches, or any other failure results in a clean
# no-op (exit 0) and a diagnostic message.
#
# Usage:
#   cache-restore.sh <cache-type>
#
# cache-type is one of: maven, npm, pip, bundler, gradle
#
# The script:
#   1. Computes a cache key from the relevant lockfile(s)
#   2. Downloads <bucket>/<cache-type>/<key>.tar.gz from S3
#   3. Extracts it into a workspace-local directory that run-in-docker.sh
#      will volume-mount into the build container
#
# Environment:
#   BUILDKITE_BUILD_CHECKOUT_PATH  - set by Buildkite (repo checkout root)
#   BUILDKITE_PLUGIN_S3_CACHE_BUCKET - override bucket name (default: mockserver-ci-dependency-cache)
#   AWS_REGION / AWS_DEFAULT_REGION  - override region (default: eu-west-2)
# ---------------------------------------------------------------------------
set -uo pipefail
# NOTE: set -e is intentionally OMITTED -- we handle every error inline.

CACHE_TYPE="${1:-}"
BUCKET="${BUILDKITE_PLUGIN_S3_CACHE_BUCKET:-mockserver-ci-dependency-cache}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-west-2}}"
CHECKOUT="${BUILDKITE_BUILD_CHECKOUT_PATH:-.}"
CACHE_BASE="${CHECKOUT}/.buildkite-cache"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log()  { echo "--- :s3: [cache-restore] $*"; }
warn() { echo "~~~ :warning: [cache-restore] $*"; }

# Exit cleanly -- cache misses and errors are NOT build failures.
bail() { warn "$*"; exit 0; }

# ---------------------------------------------------------------------------
# Validate inputs
# ---------------------------------------------------------------------------
case "$CACHE_TYPE" in
  maven|npm|pip|bundler|gradle) ;;
  *) bail "Unknown cache type '${CACHE_TYPE}' -- skipping restore" ;;
esac

# ---------------------------------------------------------------------------
# Compute cache key from lockfiles
# ---------------------------------------------------------------------------
compute_key() {
  local files=()
  case "$CACHE_TYPE" in
    maven)
      # Hash all pom.xml files in the mockserver/ Maven reactor
      while IFS= read -r -d '' f; do
        files+=("$f")
      done < <(find "${CHECKOUT}/mockserver" -name 'pom.xml' -print0 2>/dev/null)
      ;;
    npm)
      # Hash package-lock.json from UI and node client
      for d in mockserver-ui mockserver-client-node mockserver-node; do
        [[ -f "${CHECKOUT}/${d}/package-lock.json" ]] && files+=("${CHECKOUT}/${d}/package-lock.json")
        [[ -f "${CHECKOUT}/${d}/package.json" ]] && files+=("${CHECKOUT}/${d}/package.json")
      done
      ;;
    pip)
      for f in "${CHECKOUT}/mockserver-client-python/pyproject.toml" \
               "${CHECKOUT}/mockserver-client-python/setup.cfg" \
               "${CHECKOUT}/mockserver-client-python/requirements.txt"; do
        [[ -f "$f" ]] && files+=("$f")
      done
      ;;
    bundler)
      for d in mockserver-client-ruby jekyll-www.mock-server.com; do
        [[ -f "${CHECKOUT}/${d}/Gemfile" ]] && files+=("${CHECKOUT}/${d}/Gemfile")
        [[ -f "${CHECKOUT}/${d}/Gemfile.lock" ]] && files+=("${CHECKOUT}/${d}/Gemfile.lock")
      done
      ;;
    gradle)
      # Key on the wrapper descriptors ONLY. Each gradle-wrapper.properties pins a
      # distributionUrl (gradle-<ver>-bin.zip) and a distributionSha256Sum, so the
      # key rotates exactly when a Gradle version is bumped -- which is precisely
      # when a fresh distribution download is wanted -- and stays stable across
      # ordinary dependency edits so the cached distribution (the expensive thing)
      # persists build-to-build. A stale distribution can never silently win: the
      # wrapper stores each distribution under a URL-derived subdir and re-validates
      # it against distributionSha256Sum, re-downloading on any mismatch. Sorted for
      # a deterministic key regardless of filesystem traversal order across agents.
      while IFS= read -r -d '' f; do
        files+=("$f")
      done < <(find "${CHECKOUT}" -name 'gradle-wrapper.properties' \
                 -not -path '*/node_modules/*' -not -path '*/.buildkite-cache/*' \
                 -print0 2>/dev/null | sort -z)
      ;;
  esac

  if [[ ${#files[@]} -eq 0 ]]; then
    echo ""
    return
  fi

  # Sort for determinism, then hash. Use sha256sum or shasum (macOS).
  local hash_cmd="sha256sum"
  if ! command -v sha256sum >/dev/null 2>&1; then
    hash_cmd="shasum -a 256"
  fi
  cat "${files[@]}" 2>/dev/null | $hash_cmd | cut -d' ' -f1
}

CACHE_KEY=$(compute_key)
if [[ -z "$CACHE_KEY" ]]; then
  bail "No lockfiles found for cache type '${CACHE_TYPE}' -- nothing to restore"
fi

S3_KEY="${CACHE_TYPE}/${CACHE_KEY}.tar.gz"
LOCAL_DIR="${CACHE_BASE}/${CACHE_TYPE}"

log "Cache key: ${CACHE_KEY:0:16}..."
log "S3 path: s3://${BUCKET}/${S3_KEY}"
log "Local dir: ${LOCAL_DIR}"

# ---------------------------------------------------------------------------
# Check prerequisites
# ---------------------------------------------------------------------------
if ! command -v aws >/dev/null 2>&1; then
  bail "AWS CLI not installed -- skipping cache restore"
fi

# Quick credential check -- if no creds are available, bail immediately
# rather than waiting for an AWS timeout.
if ! aws sts get-caller-identity --region "$REGION" >/dev/null 2>&1; then
  bail "No AWS credentials available -- skipping cache restore"
fi

# ---------------------------------------------------------------------------
# Create local cache directory (writable by the agent user)
# ---------------------------------------------------------------------------
if ! mkdir -p "$LOCAL_DIR" 2>/dev/null; then
  bail "Cannot create cache directory ${LOCAL_DIR} -- skipping restore"
fi

# ---------------------------------------------------------------------------
# Download from S3
# ---------------------------------------------------------------------------
TARBALL="${LOCAL_DIR}.tar.gz"

log "Downloading cache from S3..."
if ! aws s3 cp "s3://${BUCKET}/${S3_KEY}" "$TARBALL" \
     --region "$REGION" \
     --only-show-errors \
     2>&1; then
  rm -f "$TARBALL" 2>/dev/null
  bail "S3 download failed (cache miss or bucket not found) -- clean build"
fi

if [[ ! -f "$TARBALL" ]]; then
  bail "Downloaded tarball not found -- clean build"
fi

# ---------------------------------------------------------------------------
# Extract
# ---------------------------------------------------------------------------
log "Extracting cache ($(du -sh "$TARBALL" 2>/dev/null | cut -f1 || echo '?'))..."
if ! tar xzf "$TARBALL" -C "$LOCAL_DIR" 2>&1; then
  warn "Tar extraction failed -- removing corrupt cache"
  rm -rf "$LOCAL_DIR" "$TARBALL" 2>/dev/null
  mkdir -p "$LOCAL_DIR" 2>/dev/null || true
  bail "Cache extraction failed -- clean build"
fi

rm -f "$TARBALL" 2>/dev/null

ITEM_COUNT=$(find "$LOCAL_DIR" -type f 2>/dev/null | head -100 | wc -l | tr -d ' ')
log "Cache restored: ~${ITEM_COUNT}+ files in ${LOCAL_DIR}"
exit 0
