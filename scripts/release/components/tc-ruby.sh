#!/usr/bin/env bash
# Publish the testcontainers-mockserver gem to RubyGems.
#
# Mirrors tc-python.sh (PyPI) for structure and rubygems.sh (the MockServer Ruby
# CLIENT gem) for the Ruby build/push mechanics. The gem version constant lives
# in lib/testcontainers/mockserver/version.rb; the default image tag is derived
# at runtime from the mockserver-client gem version, so only the gem VERSION
# needs a bump.
#
# update-version-references.sh bumps the CLIENT gem's version.rb
# (mockserver-client-ruby/...) but NOT this testcontainers gem's version.rb, so —
# exactly like tc-python.sh self-bumps pyproject.toml — this component bumps its
# own version.rb from RELEASE_VERSION.
#
# Build + gem push run in the pinned $RUBY_IMAGE via in_docker (no host ruby/gem
# required). The RubyGems API key is passed via `-e GEM_HOST_API_KEY=...`
# (redacted by run-in-docker) and dereferenced inside the single-quoted body, so
# the literal key never lands in the logged command args.
#
# Failure policy: a real build/push failure aborts the step (set -e). The one
# graceful exit 0 is when the RubyGems secret is genuinely absent — this is a
# soft_fail publish channel (like every tc-* sibling), so an unconfigured
# credential skips the channel rather than reddening the release.
#
# Dry-run: bump version.rb in-place + gem build (skip gem push), restore on exit.

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
require_cmd curl
require_cmd jq
require_release_inputs
skip_unless_release_type "tc-ruby" full,post-maven

log_step "Publish testcontainers-mockserver (Ruby) $RELEASE_VERSION (dry-run=$DRY_RUN)"

COMPONENT_DIR="$REPO_ROOT/mockserver-testcontainers/ruby"
VERSION_RB="$COMPONENT_DIR/lib/testcontainers/mockserver/version.rb"
GEM_NAME="testcontainers-mockserver"

read_version_rb() {
  grep -E 'VERSION[[:space:]]*=' "$VERSION_RB" | head -1 | sed -E 's/.*"([^"]+)".*/\1/'
}

# Bump the gem VERSION constant (real run commits this below; dry-run bumps it
# in-place so the build exercises the real version, restoring on exit).
if ! is_dry_run; then
  log_info "Updating version to $RELEASE_VERSION in version.rb"
  sed -i.bak "s/VERSION = \".*\"/VERSION = \"${RELEASE_VERSION}\"/" "$VERSION_RB"
  rm -f "$VERSION_RB.bak"
else
  CURRENT_RB_VERSION="$(read_version_rb)"
  if [[ "$CURRENT_RB_VERSION" != "$RELEASE_VERSION" ]]; then
    mkdir -p "$REPO_ROOT/.tmp"
    cp "$VERSION_RB" "$REPO_ROOT/.tmp/tc-ruby-version.rb.bak"
    # shellcheck disable=SC2064  # expand the path now, not at trap-fire time
    trap "cp '$REPO_ROOT/.tmp/tc-ruby-version.rb.bak' '$VERSION_RB' 2>/dev/null || true" EXIT
    sed -i.bak "s/VERSION = \".*\"/VERSION = \"${RELEASE_VERSION}\"/" "$VERSION_RB"
    rm -f "$VERSION_RB.bak"
    log_info "dry-run: bumped version.rb to $RELEASE_VERSION in-place (not committed)"
  fi
fi

# Fail-fast version guard: refuse to build/publish the wrong version. Runs BEFORE
# the "already on RubyGems" idempotency check so a stale version.rb fails loud
# rather than hiding behind an "already published" message.
VERSION="$(read_version_rb)"
if [[ "$VERSION" != "$RELEASE_VERSION" ]]; then
  log_error "version.rb VERSION ($VERSION) does not match RELEASE_VERSION ($RELEASE_VERSION) — refusing to publish wrong version"
  exit 1
fi

# Idempotent: an already-published version means a prior run did this.
if ! is_dry_run; then
  log_info "Check RubyGems for existing $VERSION"
  http_code=$(curl -s -o /dev/null -w "%{http_code}" \
    "https://rubygems.org/api/v1/versions/${GEM_NAME}.json")
  case "$http_code" in
    200)
      if curl -sf "https://rubygems.org/api/v1/versions/${GEM_NAME}.json" \
           | jq -e ".[] | select(.number == \"$VERSION\")" >/dev/null 2>&1; then
        log_info "$GEM_NAME $VERSION already on RubyGems — skipping"
        exit 0
      fi ;;
    404) ;;
    *) log_error "RubyGems returned HTTP $http_code"; exit 1 ;;
  esac
fi

rm -f "$COMPONENT_DIR"/${GEM_NAME}-*.gem 2>/dev/null || true

log_info "Build gem (Ruby in Docker)"
in_docker "$RUBY_IMAGE" \
  -w /build/mockserver-testcontainers/ruby \
  -- gem build "${GEM_NAME}.gemspec"

if is_dry_run; then
  log_dry "skip: gem push to RubyGems"
  log_info "Built: $COMPONENT_DIR/${GEM_NAME}-${VERSION}.gem"
  ls -la "$COMPONENT_DIR"/${GEM_NAME}-*.gem 2>/dev/null || true
  exit 0
fi

# Graceful skip when the RubyGems credential is not configured. tc-ruby is a
# soft_fail channel, so an absent secret skips the channel rather than failing
# the release. Uses GetSecretValue (which the release agent holds) — not
# DescribeSecret — so a permissions gap can't cause a false skip.
GEM_HOST_API_KEY=$(load_secret "mockserver-build/rubygems" "api_key" 2>/dev/null || echo "")
if [[ -z "$GEM_HOST_API_KEY" || "$GEM_HOST_API_KEY" == "null" ]]; then
  log_info ":warning: mockserver-build/rubygems secret missing/empty — skipping testcontainers-mockserver (Ruby) publish (see mockserver-testcontainers/ruby/release-component.md)"
  exit 0
fi

log_info "Push to RubyGems"
in_docker "$RUBY_IMAGE" \
  -w /build/mockserver-testcontainers/ruby \
  -e "GEM_HOST_API_KEY=$GEM_HOST_API_KEY" \
  -e "GEM_NAME=$GEM_NAME" \
  -e "VERSION=$VERSION" \
  -- bash -ec '
    set +x
    gem push "${GEM_NAME}-${VERSION}.gem"
  '

# Commit version bump (best-effort — the publish itself is already done).
git_commit_and_push "release: publish testcontainers-mockserver (Ruby) $RELEASE_VERSION to RubyGems" \
  "$VERSION_RB" || \
  log_info ":warning: could not commit version bump (non-fatal — publish already succeeded)"

log_info "testcontainers-mockserver (Ruby) publish complete"
