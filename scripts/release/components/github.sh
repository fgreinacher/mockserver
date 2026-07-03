#!/usr/bin/env bash
# Create the GitHub Release for the mockserver-X.Y.Z tag.
#
# Dry-run: extract changelog notes + show them, skip `gh release create`.

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

require_cmd docker
require_release_inputs
skip_unless_release_type "github" full,post-maven

log_step "Create GitHub Release for $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

log_info "Extract release notes from changelog.md"
CHANGELOG_EXTRACT=$(sed -n "/## \[$RELEASE_VERSION\]/,/## \[/p" "$REPO_ROOT/changelog.md" | sed '$d')
if [[ -z "$CHANGELOG_EXTRACT" ]]; then
  CHANGELOG_EXTRACT="Release $RELEASE_VERSION"
fi

NOTES_FILE="$REPO_ROOT/.tmp/changelog-extract.md"
mkdir -p "$REPO_ROOT/.tmp"
echo "$CHANGELOG_EXTRACT" > "$NOTES_FILE"
log_info "Notes preview:"
sed 's/^/    /' "$NOTES_FILE"

if is_dry_run; then
  log_dry "skip: gh release create mockserver-$RELEASE_VERSION"
else
  GITHUB_TOKEN=$(load_secret "mockserver-release/github-token" "token")
  # Idempotency by catching the API response rather than pre-checking with
  # `gh release view`. The maniator/gh container image is gh-only (no git),
  # so without an explicit --repo flag `gh release view` can fail to resolve
  # the repository even when the release exists — that's what bit us in
  # build #38, where the precheck silently returned non-zero and we hit
  # HTTP 422 "Release.tag_name already exists" on the subsequent create.
  # Capturing the create stderr and treating that exact error as success is
  # both simpler and more robust.
  log_info "Creating release mockserver-$RELEASE_VERSION"
  create_output=$(in_docker "$GH_IMAGE" \
    -w /build \
    -e "GITHUB_TOKEN=$GITHUB_TOKEN" \
    -- release create "mockserver-$RELEASE_VERSION" \
         --title "MockServer $RELEASE_VERSION" \
         --notes-file ".tmp/changelog-extract.md" \
         --latest 2>&1) && create_exit=0 || create_exit=$?
  if [[ $create_exit -ne 0 ]]; then
    # Match GitHub's specific "release already exists" error strings ONLY.
    # We deliberately do NOT match a bare "HTTP 422" — GitHub returns 422 for
    # other unrelated validation failures (malformed body, missing required
    # field, schema mismatch, etc.) and we don't want to silently mask a real
    # error as "idempotent success".
    if grep -qE "Release\.tag_name already exists|already_exists" <<<"$create_output"; then
      log_info "  release mockserver-$RELEASE_VERSION already exists — treating as idempotent success"
    else
      printf '%s\n' "$create_output"
      log_error "gh release create failed (exit $create_exit)"
      exit $create_exit
    fi
  fi
fi

rm -f "$NOTES_FILE"
log_info "GitHub Release complete"
