#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# cache-save.sh -- Upload a dependency cache tarball to S3
# ---------------------------------------------------------------------------
# FAIL-SAFE: This script MUST NEVER abort a build. Every external command
# (aws, tar, mkdir) is guarded so that missing buckets, missing credentials,
# permission errors, or any other failure results in a clean no-op (exit 0).
#
# Usage:
#   cache-save.sh <cache-type>
#
# cache-type is one of: maven, npm, pip, bundler, gradle
#
# The script:
#   1. Checks if a workspace-local cache directory exists (populated by the
#      build step via run-in-docker.sh volume mounts)
#   2. Computes the same lockfile-based cache key as cache-restore.sh
#   3. Skips upload if the key already exists in S3 (cache hit = no work)
#   4. Tars and uploads to S3
#
# Environment:
#   BUILDKITE_BUILD_CHECKOUT_PATH  - set by Buildkite (repo checkout root)
#   BUILDKITE_PLUGIN_S3_CACHE_BUCKET - override bucket name
#   AWS_REGION / AWS_DEFAULT_REGION  - override region
# ---------------------------------------------------------------------------
set -uo pipefail
# NOTE: set -e is intentionally OMITTED.

CACHE_TYPE="${1:-}"
BUCKET="${BUILDKITE_PLUGIN_S3_CACHE_BUCKET:-mockserver-ci-dependency-cache}"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-west-2}}"
CHECKOUT="${BUILDKITE_BUILD_CHECKOUT_PATH:-.}"
CACHE_BASE="${CHECKOUT}/.buildkite-cache"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log()  { echo "--- :s3: [cache-save] $*"; }
warn() { echo "~~~ :warning: [cache-save] $*"; }
bail() { warn "$*"; exit 0; }

# ---------------------------------------------------------------------------
# Validate inputs
# ---------------------------------------------------------------------------
case "$CACHE_TYPE" in
  maven|npm|pip|bundler|gradle) ;;
  *) bail "Unknown cache type '${CACHE_TYPE}' -- skipping save" ;;
esac

# ---------------------------------------------------------------------------
# Compute cache key (same algorithm as cache-restore.sh)
# ---------------------------------------------------------------------------
compute_key() {
  local files=()
  case "$CACHE_TYPE" in
    maven)
      while IFS= read -r -d '' f; do
        files+=("$f")
      done < <(find "${CHECKOUT}/mockserver" -name 'pom.xml' -print0 2>/dev/null)
      ;;
    npm)
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
      # Must match cache-restore.sh exactly: key on the wrapper descriptors only
      # (distributionUrl + distributionSha256Sum), so the key rotates on a Gradle
      # version bump and is otherwise stable, letting the cached distribution
      # persist across builds. Sorted for a deterministic key across agents.
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

  local hash_cmd="sha256sum"
  if ! command -v sha256sum >/dev/null 2>&1; then
    hash_cmd="shasum -a 256"
  fi
  cat "${files[@]}" 2>/dev/null | $hash_cmd | cut -d' ' -f1
}

CACHE_KEY=$(compute_key)
if [[ -z "$CACHE_KEY" ]]; then
  bail "No lockfiles found for cache type '${CACHE_TYPE}' -- nothing to save"
fi

S3_KEY="${CACHE_TYPE}/${CACHE_KEY}.tar.gz"
LOCAL_DIR="${CACHE_BASE}/${CACHE_TYPE}"

log "Cache key: ${CACHE_KEY:0:16}..."
log "S3 path: s3://${BUCKET}/${S3_KEY}"

# ---------------------------------------------------------------------------
# Check if there is anything to save
# ---------------------------------------------------------------------------
if [[ ! -d "$LOCAL_DIR" ]]; then
  bail "Cache directory ${LOCAL_DIR} does not exist -- nothing to save"
fi

FILE_COUNT=$(find "$LOCAL_DIR" -type f 2>/dev/null | head -5 | wc -l | tr -d ' ')
if [[ "$FILE_COUNT" -eq 0 ]]; then
  bail "Cache directory ${LOCAL_DIR} is empty -- nothing to save"
fi

# ---------------------------------------------------------------------------
# Check prerequisites
# ---------------------------------------------------------------------------
if ! command -v aws >/dev/null 2>&1; then
  bail "AWS CLI not installed -- skipping cache save"
fi

if ! aws sts get-caller-identity --region "$REGION" >/dev/null 2>&1; then
  bail "No AWS credentials available -- skipping cache save"
fi

# ---------------------------------------------------------------------------
# Check if this exact key already exists in S3 (skip redundant uploads)
# ---------------------------------------------------------------------------
if aws s3api head-object --bucket "$BUCKET" --key "$S3_KEY" --region "$REGION" >/dev/null 2>&1; then
  log "Cache key already exists in S3 -- skipping upload"
  exit 0
fi

# ---------------------------------------------------------------------------
# Tar and upload
# ---------------------------------------------------------------------------
TARBALL="${LOCAL_DIR}.tar.gz"

log "Creating tarball from ${LOCAL_DIR}..."
if ! tar czf "$TARBALL" -C "$LOCAL_DIR" . 2>&1; then
  rm -f "$TARBALL" 2>/dev/null
  bail "Tar creation failed -- skipping cache save"
fi

TARBALL_SIZE=$(du -sh "$TARBALL" 2>/dev/null | cut -f1 || echo '?')
log "Uploading cache (${TARBALL_SIZE})..."

if ! aws s3 cp "$TARBALL" "s3://${BUCKET}/${S3_KEY}" \
     --region "$REGION" \
     --only-show-errors \
     2>&1; then
  rm -f "$TARBALL" 2>/dev/null
  bail "S3 upload failed -- cache not saved (build still succeeds)"
fi

rm -f "$TARBALL" 2>/dev/null
log "Cache saved successfully"
exit 0
