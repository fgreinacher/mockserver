#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=.buildkite/scripts/lib/last-successful-commit.sh
source "$SCRIPT_DIR/lib/last-successful-commit.sh"

DEFAULT_BRANCH="${BUILDKITE_PULL_REQUEST_BASE_BRANCH:-}"
if [ -z "$DEFAULT_BRANCH" ]; then
  DEFAULT_BRANCH=$(git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null | sed 's@^refs/remotes/origin/@@' || true)
fi
DEFAULT_BRANCH=${DEFAULT_BRANCH:-master}

trigger_all_pipelines() {
  echo "--- :warning: Cannot determine change base — triggering all pipelines"
  CHANGED_FILES=$(git ls-tree -r --name-only HEAD 2>/dev/null || echo "mockserver/")
}

if [ -n "${BUILDKITE_PULL_REQUEST_BASE_BRANCH:-}" ]; then
  MERGE_BASE=$(git merge-base HEAD "origin/${DEFAULT_BRANCH}" 2>/dev/null || echo "HEAD~1")
  CHANGED_FILES=$(git diff --name-only "$MERGE_BASE"..HEAD 2>/dev/null || git diff-tree --no-commit-id --name-only -r HEAD)
else
  LAST_COMMIT=""
  if [ -n "${BUILDKITE:-}" ]; then
    echo "--- :buildkite: Querying last successful build commit"
    LAST_COMMIT=$(last_successful_commit || true)
  fi

  if [ -n "$LAST_COMMIT" ]; then
    echo "    Diffing against last successful build: ${LAST_COMMIT:0:10}"
    CHANGED_FILES=$(git diff --name-only "$LAST_COMMIT"..HEAD 2>/dev/null)
    if [ -z "$CHANGED_FILES" ]; then
      CHANGED_FILES=$(git diff-tree --no-commit-id --name-only -r HEAD 2>/dev/null || true)
    fi
  elif [ -n "${BUILDKITE:-}" ]; then
    trigger_all_pipelines
  else
    CHANGED_FILES=$(git diff-tree --no-commit-id --name-only -r HEAD 2>/dev/null || git diff --name-only HEAD~1..HEAD)
  fi
fi

STEPS=""

# A server change alters the wire format every client library encodes against,
# and test-fixtures/ is the shared parity corpus every client round-trips. Both
# must run the client conformance suites, or wire drift ships with zero
# non-Java verification (the client pipelines are otherwise gated only on their
# own directory). mockserver-maven-plugin/ is excluded — it has its own
# pipeline and does not define the wire format.
SERVER_OR_FIXTURES_CHANGED=false
if printf '%s\n' "$CHANGED_FILES" | grep -E -- "^mockserver/" | grep -qvE -- "^mockserver/mockserver-maven-plugin/"; then
  SERVER_OR_FIXTURES_CHANGED=true
fi
if printf '%s\n' "$CHANGED_FILES" | grep -qE -- "^test-fixtures/"; then
  SERVER_OR_FIXTURES_CHANGED=true
fi

emit_trigger() {
  local pipeline_slug="$1"
  local label="$2"
  STEPS="${STEPS}  - label: \":pipeline: ${label}\"
    command: \".buildkite/scripts/trigger-pipeline.sh ${pipeline_slug} '${label}'\"
    timeout_in_minutes: 120
    agents:
      queue: trigger
    retry:
      automatic:
        - exit_status: -1
          limit: 2
"
}

trigger_if_changed() {
  local path_regex="$1"
  local pipeline_slug="$2"
  local label="$3"
  if printf '%s\n' "$CHANGED_FILES" | grep -qE -- "$path_regex"; then
    echo "--- :pipeline: Triggering ${label} (matched ${path_regex})"
    emit_trigger "$pipeline_slug" "$label"
  fi
}

# Client pipelines additionally trigger on any server or shared-fixture change,
# so wire-format drift is verified against every client library.
trigger_client_if_changed() {
  local path_regex="$1"
  local pipeline_slug="$2"
  local label="$3"
  if printf '%s\n' "$CHANGED_FILES" | grep -qE -- "$path_regex"; then
    echo "--- :pipeline: Triggering ${label} (matched ${path_regex})"
    emit_trigger "$pipeline_slug" "$label"
  elif [ "$SERVER_OR_FIXTURES_CHANGED" = "true" ]; then
    echo "--- :pipeline: Triggering ${label} (server / test-fixtures change — client conformance)"
    emit_trigger "$pipeline_slug" "$label"
  fi
}

# Match changes under mockserver/ excluding the maven-plugin submodule (which has its own pipeline).
# test-fixtures/ is included: the Java model is round-tripped against the same shared corpus.
if printf '%s\n' "$CHANGED_FILES" | grep -E -- "^(mockserver/|mockserver-ui/|test-fixtures/)" | grep -qvE -- "^mockserver/mockserver-maven-plugin/"; then
  trigger_if_changed "^(mockserver/|mockserver-ui/|test-fixtures/)" "mockserver-java" "MockServer Java"
fi
trigger_if_changed "^mockserver-ui/" "mockserver-ui" "MockServer UI"
# The jekyll copy of the OpenAPI spec is included deliberately: the Node suite asserts it stays
# byte-identical to the copy under mockserver-core (generated_types_drift_test.js). Without this,
# a commit touching ONLY the published spec runs the website pipeline alone, the assertion never
# fires, and the divergence surfaces later on an unrelated change — attributed to the wrong commit.
trigger_client_if_changed "^(mockserver-node/|mockserver-client-node/|mockserver-testcontainers/node/|jekyll-www\.mock-server\.com/mockserver-openapi\.yaml)" "mockserver-node" "MockServer Node"
trigger_client_if_changed "^(mockserver-client-python/|mockserver-testcontainers/python/)" "mockserver-python" "MockServer Python"
trigger_client_if_changed "^mockserver-client-ruby/" "mockserver-ruby" "MockServer Ruby"
trigger_client_if_changed "^(mockserver-client-go/|mockserver-testcontainers/go/)" "mockserver-go" "MockServer Go"
trigger_client_if_changed "^(mockserver-client-dotnet/|mockserver-testcontainers/dotnet/)" "mockserver-dotnet" "MockServer .NET"
trigger_client_if_changed "^(mockserver-client-rust/|mockserver-testcontainers/rust/)" "mockserver-rust" "MockServer Rust"
trigger_client_if_changed "^mockserver-client-php/" "mockserver-php" "MockServer PHP"
trigger_if_changed "^(mockserver-vscode/|mockserver-jetbrains/)" "mockserver-editors" "MockServer Editors"
trigger_if_changed "^mockserver/mockserver-maven-plugin/" "mockserver-maven-plugin" "MockServer Maven Plugin"
trigger_if_changed "^mockserver-performance-test/" "mockserver-performance-test" "MockServer Performance Test"
trigger_if_changed "^container_integration_tests/" "mockserver-container-tests" "MockServer Container Tests"
trigger_if_changed "^jekyll-www.mock-server.com/" "mockserver-website" "MockServer Website"
trigger_if_changed "^docker_build/maven/" "mockserver-build-image" "MockServer Build Image"

# examples/ (generated Postman/Bruno collections) and the OpenAPI spec route to
# infra too, so the collections-validate gate catches spec/collection drift.
if printf '%s\n' "$CHANGED_FILES" | grep -qE -- "^(\.buildkite/|\.github/|terraform/|docker/|scripts/|helm/|docs/|examples/|jekyll-www\.mock-server\.com/mockserver-openapi\.yaml|AGENTS\.md|opencode\.jsonc|\.opencode/)"; then
  echo "--- :pipeline: Triggering MockServer Infra (infra changes)"
  STEPS="${STEPS}  - label: \":pipeline: MockServer Infra\"
    command: \".buildkite/scripts/trigger-pipeline.sh mockserver-infra 'MockServer Infra'\"
    timeout_in_minutes: 120
    agents:
      queue: trigger
    retry:
      automatic:
        - exit_status: -1
          limit: 2
"
fi

# Runs on EVERY build, deliberately outside the path-filtered triggers above. The client version
# pins it guards live in per-client directories (mockserver-client-rust/Cargo.toml,
# mockserver-client-python/mockserver/launcher.py, ...), so a commit that drifts one of them routes
# only to that client's own pipeline. Attaching this to any path filter means the guard never sees
# the change it exists to catch. It is a few greps and costs ~1s.
ALWAYS_STEPS='  - label: ":package: validate client version pins"
    command: ".buildkite/scripts/steps/clients-version-consistency.sh"
    timeout_in_minutes: 5
    agents:
      queue: trigger
    retry:
      automatic:
        - exit_status: -1   # agent lost (e.g. Spot reclamation)
          limit: 2
        - exit_status: 255  # agent forced shutdown
          limit: 2
'

if [ -z "$STEPS" ]; then
  echo "--- :pipeline: No project-specific changes detected (running always-on gates)"
  printf "steps:\n%s" "$ALWAYS_STEPS" | buildkite-agent pipeline upload
else
  printf "steps:\n%s%s" "$ALWAYS_STEPS" "$STEPS" | buildkite-agent pipeline upload
fi
