#!/usr/bin/env bash
# Shared library for release scripts.
#
# DESIGN: see docs/operations/release-principles.md
#
# Release scripts are CI-agnostic. They read configuration from environment
# variables only. Any CI-specific glue (buildkite-agent meta-data lookups,
# CI annotations, etc.) lives in adapter scripts under .buildkite/scripts/
# or .github/workflows/.

set -euo pipefail

RELEASE_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$RELEASE_LIB_DIR/../.." && pwd)"
export REPO_ROOT

# -----------------------------------------------------------------------------
# Pinned tool images — single source of truth for both local and CI.
# Override via env var if needed for a specific run.
# -----------------------------------------------------------------------------

MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.9.9-eclipse-temurin-17}"
NODE_IMAGE="${NODE_IMAGE:-node:20-bookworm}"
RUBY_IMAGE="${RUBY_IMAGE:-ruby:3.2-bookworm}"
HELM_IMAGE="${HELM_IMAGE:-alpine/helm:3.16.2}"
GH_IMAGE="${GH_IMAGE:-maniator/gh:v2.62.0}"
PYTHON_IMAGE="${PYTHON_IMAGE:-python:3.12-slim-bookworm}"
TERRAFORM_IMAGE="${TERRAFORM_IMAGE:-hashicorp/terraform:1.15}"
# Polyglot client/testcontainers publish toolchains. Previously absent here, so
# the go/rust/dotnet publish components fell back to a host `command -v` probe,
# found nothing on the release-queue AMI, and silently skipped. Pin them so those
# publishes run in a container like every other toolchain. GO_IMAGE tracks the
# highest `go` directive across our modules: mockserver-testcontainers/go needs
# 1.25 (its testcontainers-go v0.42 dep requires go >= 1.25.0), and mockserver-
# client-go (go 1.21) runs fine on a newer toolchain — so pin to 1.25 and avoid a
# runtime GOTOOLCHAIN download.
GO_IMAGE="${GO_IMAGE:-golang:1.25-bookworm}"
RUST_IMAGE="${RUST_IMAGE:-rust:1-bookworm}"
DOTNET_IMAGE="${DOTNET_IMAGE:-mcr.microsoft.com/dotnet/sdk:8.0}"
export MAVEN_IMAGE NODE_IMAGE RUBY_IMAGE HELM_IMAGE GH_IMAGE PYTHON_IMAGE TERRAFORM_IMAGE

REGION="${AWS_REGION:-eu-west-2}"

# -----------------------------------------------------------------------------
# Logging
# -----------------------------------------------------------------------------

log_info()  { echo "--- $*"; }
log_error() { echo "--- :x: $*" >&2; }
log_step()  { echo "--- :arrow_right: $*"; }
log_dry()   { echo "--- :test_tube: [DRY RUN] $*"; }

# -----------------------------------------------------------------------------
# Dry-run support
# -----------------------------------------------------------------------------

# Released scripts read this. Default: dry-run if not explicitly set, so a
# careless local invocation can't deploy. CI adapters explicitly set
# DRY_RUN=false to actually release.
DRY_RUN="${DRY_RUN:-true}"

is_dry_run() { [[ "$DRY_RUN" == "true" ]]; }

dry_run_or() {
  local description="$1"; shift
  if is_dry_run; then
    log_dry "skip: $description"
    log_dry "would: $*"
    return 0
  fi
  log_info "$description"
  "$@"
}

# -----------------------------------------------------------------------------
# Input validation
#
# Every component script calls this near the top. It checks that the env-var
# contract is honoured. No CI-specific lookups happen here — the caller is
# responsible for setting the env vars (whether it's a Buildkite adapter, a
# GitHub Actions workflow, or a local invocation).
# -----------------------------------------------------------------------------

require_release_inputs() {
  : "${RELEASE_VERSION:?RELEASE_VERSION must be set (X.Y.Z)}"
  if [[ ! "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    log_error "RELEASE_VERSION must be X.Y.Z, got: $RELEASE_VERSION"
    exit 1
  fi

  # Auto-derive NEXT_VERSION if not supplied.
  if [[ -z "${NEXT_VERSION:-}" ]]; then
    NEXT_VERSION="$(increment_patch_version "$RELEASE_VERSION")-SNAPSHOT"
  fi
  if [[ ! "$NEXT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
    log_error "NEXT_VERSION must be X.Y.Z-SNAPSHOT, got: $NEXT_VERSION"
    exit 1
  fi

  # Auto-derive OLD_VERSION as the latest released tag OTHER than the one we
  # are about to release. The exclusion matters because prepare.sh pushes the
  # `mockserver-$RELEASE_VERSION` tag before finalize.sh runs — without this
  # filter, `latest_release_version` returns the just-pushed tag and
  # OLD_VERSION would equal RELEASE_VERSION, making finalize.sh's
  # `s/$OLD_VERSION/$RELEASE_VERSION/g` find-and-replace a silent no-op
  # (e.g. Helm Chart.yaml, READMEs, docker-compose, example package.json
  # files all stayed pinned to the previous version through release 6.1.0).
  if [[ -z "${OLD_VERSION:-}" ]]; then
    git -C "$REPO_ROOT" fetch --tags --quiet 2>/dev/null || true
    OLD_VERSION="$(previous_release_version "$RELEASE_VERSION")"
  fi
  if [[ -z "$OLD_VERSION" ]]; then
    log_error "OLD_VERSION could not be derived — no mockserver-X.Y.Z git tag found"
    exit 1
  fi
  if [[ ! "$OLD_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    log_error "OLD_VERSION must be X.Y.Z, got: $OLD_VERSION"
    exit 1
  fi

  RELEASE_TYPE="${RELEASE_TYPE:-full}"
  case "$RELEASE_TYPE" in
    full|maven-only|docker-only|post-maven) ;;
    *) log_error "RELEASE_TYPE must be full|maven-only|docker-only|post-maven, got: $RELEASE_TYPE"; exit 1 ;;
  esac

  CREATE_VERSIONED_SITE="${CREATE_VERSIONED_SITE:-no}"
  case "$CREATE_VERSIONED_SITE" in
    yes|no) ;;
    *) log_error "CREATE_VERSIONED_SITE must be yes|no, got: $CREATE_VERSIONED_SITE"; exit 1 ;;
  esac

  CURRENT_VERSION="$(current_project_version 2>/dev/null || echo "")"

  export RELEASE_VERSION NEXT_VERSION OLD_VERSION RELEASE_TYPE \
         CREATE_VERSIONED_SITE CURRENT_VERSION DRY_RUN
}

# Each component script declares which RELEASE_TYPEs it applies to. If the
# current RELEASE_TYPE isn't in the list, the script exits 0.
skip_unless_release_type() {
  local component="$1"; shift
  local types_csv="$*"
  local IFS=,
  local t
  for t in $types_csv; do
    if [[ "$RELEASE_TYPE" == "$t" ]]; then return 0; fi
  done
  log_info "Skipping $component for RELEASE_TYPE=$RELEASE_TYPE"
  exit 0
}

# -----------------------------------------------------------------------------
# Environment helpers
# -----------------------------------------------------------------------------

require_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || { log_error "Missing required command: $cmd"; exit 1; }
}

# -----------------------------------------------------------------------------
# Version helpers
# -----------------------------------------------------------------------------

current_project_version() {
  grep -m1 -E '^[[:space:]]*<version>[^<]+</version>' "$REPO_ROOT/mockserver/pom.xml" \
    | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/'
}

increment_patch_version() {
  local ver="$1"
  local major="${ver%%.*}"
  local minor_rest="${ver#*.}"
  local minor="${minor_rest%%.*}"
  local patch="${ver##*.}"
  echo "${major}.${minor}.$((patch + 1))"
}

latest_release_version() {
  git -C "$REPO_ROOT" tag --list "mockserver-[0-9]*" --sort=-v:refname \
    | sed 's/^mockserver-//' \
    | awk 'NR == 1 { print; exit }'
}

# Return the highest mockserver-X.Y.Z tag that is NOT $1. Used by
# require_release_inputs to derive OLD_VERSION during finalize, where the new
# release's own tag has already been pushed and would otherwise dominate.
# Falls back to latest_release_version when no exclude is passed.
previous_release_version() {
  local exclude="${1:-}"
  if [[ -z "$exclude" ]]; then
    latest_release_version
    return
  fi
  git -C "$REPO_ROOT" tag --list "mockserver-[0-9]*" --sort=-v:refname \
    | sed 's/^mockserver-//' \
    | grep -v -x -F "$exclude" \
    | awk 'NR == 1 { print; exit }'
}

version_to_subdomain() {
  local ver="$1"
  local major="${ver%%.*}"
  local minor_rest="${ver#*.}"
  local minor="${minor_rest%%.*}"
  echo "${major}-${minor}"
}

# Update <version>OLD</version> -> <version>NEW</version> in every pom.xml
# beneath a directory. Skips target/ directories.
update_pom_versions() {
  local search_dir="$1" old_v="$2" new_v="$3"
  require_cmd python3
  python3 - "$old_v" "$new_v" "$search_dir" << 'PYEOF'
import sys, pathlib
old_v, new_v, search = sys.argv[1], sys.argv[2], sys.argv[3]
old_tag = f"<version>{old_v}</version>"
new_tag = f"<version>{new_v}</version>"
updated = []
for path in pathlib.Path(search).rglob("pom.xml"):
    if "target" in path.parts: continue
    text = path.read_text()
    if old_tag in text:
        path.write_text(text.replace(old_tag, new_tag))
        updated.append(str(path.relative_to(search)))
if not updated:
    print(f"ERROR: no pom.xml under {search} contained {old_tag}", file=sys.stderr)
    sys.exit(1)
for p in updated:
    print(f"  updated: {p}")
PYEOF
}

# Read-only counterpart to update_pom_versions: return 0 if ANY pom.xml beneath a
# directory (excluding target/) contains <version>$2</version>, else 1. Used to
# detect an already-applied version bump so a re-run can no-op instead of failing.
poms_contain_version() {
  local search_dir="$1" v="$2"
  require_cmd python3
  python3 - "$v" "$search_dir" << 'PYEOF'
import sys, pathlib
v, search = sys.argv[1], sys.argv[2]
tag = f"<version>{v}</version>"
for path in pathlib.Path(search).rglob("pom.xml"):
    if "target" in path.parts: continue
    if tag in path.read_text():
        sys.exit(0)
sys.exit(1)
PYEOF
}

# -----------------------------------------------------------------------------
# Docker helpers
# -----------------------------------------------------------------------------

# Retry a command with exponential backoff, to ride out TRANSIENT failures
# (network blips, registry 5xx/429, brief auth propagation). Pair with the
# hard-fail publish policy: a real failure still aborts the release, but a flaky
# one is retried first so a transient hiccup never reddens a release whose
# artifacts are fine.
#
# Usage: retry [attempts] [base_delay_s] -- CMD ARGS...
#   attempts     total attempts (default 3)
#   base_delay_s first backoff in seconds, doubled each retry (default 5)
# Returns the command's exit code from the last attempt (0 on eventual success).
# Honours dry-run transparently — it just runs the (dry-run-aware) command.
retry() {
  local attempts=3 delay=5
  [[ "${1:-}" =~ ^[0-9]+$ ]] && { attempts="$1"; shift; }
  [[ "${1:-}" =~ ^[0-9]+$ ]] && { delay="$1"; shift; }
  [[ "${1:-}" == "--" ]] && shift
  if [[ $# -eq 0 ]]; then log_error "retry: no command given"; return 2; fi
  local attempt=1 rc=0
  while true; do
    # Capture the failure code in the else branch: an `if` whose condition fails
    # with no else returns 0, so `rc=$?` after `fi` would always read 0.
    if "$@"; then return 0; else rc=$?; fi
    if (( attempt >= attempts )); then
      log_info "retry: '$1' failed after ${attempt} attempt(s) (exit ${rc})"
      return "$rc"
    fi
    log_info "retry: '$1' failed (exit ${rc}); attempt ${attempt}/${attempts} — retrying in ${delay}s"
    sleep "$delay"
    delay=$(( delay * 2 ))
    attempt=$(( attempt + 1 ))
  done
}

# Run a publish command that may legitimately fail because this exact VERSION is
# already published (re-running a release after a partial failure, or a prior
# build already shipped this component). An "already published" outcome is a
# SUCCESS for an idempotent release — the artifact the user wants is live — so
# this swallows ONLY that specific case and HARD-fails on every other error.
#
# Usage (pair with retry for transient blips):
#   retry 3 5 -- run_idempotent 'already exists|already published' -- CMD ARGS...
#
#   $1 = extended-regex matched case-insensitively against the command's combined
#        stdout+stderr; a match means "already published".
#
# Because an already-published match returns 0 on the FIRST attempt, `retry` does
# not waste attempts re-publishing something that will never change. Output is
# captured (so it can be inspected) and re-echoed, so the log still shows it. Do
# NOT pass secrets in the command BODY — they would be captured here; pass them
# via `-e` to in_docker, which keeps them out of stdout/stderr (and the banner
# redacts -e values), exactly as the publish components already do.
run_idempotent() {
  local marker="$1"; shift
  [[ "${1:-}" == "--" ]] && shift
  if [[ $# -eq 0 ]]; then log_error "run_idempotent: no command given"; return 2; fi
  local out rc=0
  out=$("$@" 2>&1) || rc=$?
  printf '%s\n' "$out"
  if [[ $rc -eq 0 ]]; then return 0; fi
  if printf '%s' "$out" | grep -qiE "$marker"; then
    log_info ":information_source: '$1' reports this version already published (matched /$marker/) — treating as success (idempotent)"
    return 0
  fi
  return "$rc"
}

# Run a command inside a Docker container with the repo mounted at /build.
# Usage:
#   in_docker IMAGE [-w WORKDIR] [-v VOL:DST] [-e KEY=VAL] -- CMD ARGS...
#
# Wraps the existing run-in-docker.sh which logs the docker command for
# local reproduction. The wrapper script redacts secrets in its log banner.
#
# When run behind a corporate TLS-inspecting proxy, set LOCAL_CA_BUNDLE
# (or rely on NODE_EXTRA_CA_CERTS / AWS_CA_BUNDLE which the lib reads
# automatically) to a PEM file on the host. The CA is:
#   - mounted into the container at /etc/ssl/local-ca.pem
#   - exposed via env vars that each toolchain respects (pip/npm/node/aws/gem/curl/git)
#   - installed into the OS CA bundle (so `curl`, `wget` etc. trust it)
#   - imported into the JDK cacerts truststore (so Maven and JVM tools trust it)
#
# The CA-setup prelude only runs when LOCAL_CA_BUNDLE is provided — in CI
# there's no proxy so the wrapper is a no-op and commands run directly.
in_docker() {
  local -a ca_args=()
  local ca="${LOCAL_CA_BUNDLE:-${NODE_EXTRA_CA_CERTS:-${AWS_CA_BUNDLE:-}}}"
  if [[ -n "$ca" && -f "$ca" ]]; then
    ca_args=(
      -v "$ca:/etc/ssl/local-ca.pem:ro"
      -e "NODE_EXTRA_CA_CERTS=/etc/ssl/local-ca.pem"
      -e "AWS_CA_BUNDLE=/etc/ssl/local-ca.pem"
      -e "SSL_CERT_FILE=/etc/ssl/local-ca.pem"
      -e "REQUESTS_CA_BUNDLE=/etc/ssl/local-ca.pem"
      -e "PIP_CERT=/etc/ssl/local-ca.pem"
      -e "GIT_SSL_CAINFO=/etc/ssl/local-ca.pem"
      -e "CURL_CA_BUNDLE=/etc/ssl/local-ca.pem"
    )
  fi
  "$REPO_ROOT/.buildkite/scripts/run-in-docker.sh" -i "$1" "${ca_args[@]+"${ca_args[@]}"}" "${@:2}"
}

# Remove bind-mounted node_modules dir(s) from INSIDE a container, as the
# container user that created them. Under the elastic-ci-stack's userns-remap,
# files an in-container `npm ci`/`npm i` writes into the bind-mounted workspace
# are owned by a remapped UID the host buildkite-agent user CANNOT delete, so a
# node_modules left behind breaks the NEXT job's git checkout/clean on the same
# agent with "unlinkat .../node_modules/...: permission denied" ->
# "cloning git repository: exit status 128". The container owns the files, so it
# can remove them. Best-effort by design: output is suppressed and the call can
# never fail the caller (so it is safe in an EXIT trap and on agents without
# Docker — a missing `docker` or `node_modules` is a no-op, not a red step). A
# genuine cleanup failure surfaces later as the next job's checkout error, which
# is the same signal we had before this helper. Register on EXIT after the
# workspace npm install, e.g.:  trap 'clean_workspace_node_modules mockserver-vscode' EXIT
clean_workspace_node_modules() {
  local d
  for d in "$@"; do
    in_docker "$NODE_IMAGE" -w "/build/$d" -- sh -c 'rm -rf node_modules' >/dev/null 2>&1 || true
  done
}

# Maven-specific Docker invocation that ALSO installs the host's corp CA
# into the JDK truststore so plugins that download from HTTPS (e.g. the
# frontend-maven-plugin downloading Node.js) work behind a TLS proxy.
#
# In CI, no CA is mounted, the prelude is a no-op, and behaviour matches
# vanilla `in_docker`.
#
# Usage: in_maven [docker-options...] -- <mvn-args...>
in_maven() {
  local -a docker_opts=() mvn_args=()
  local found_sep=false
  for arg in "$@"; do
    if $found_sep; then
      mvn_args+=("$arg")
    elif [[ "$arg" == "--" ]]; then
      found_sep=true
    else
      docker_opts+=("$arg")
    fi
  done
  if [[ ${#mvn_args[@]} -eq 0 ]]; then
    log_error "in_maven: no command after --"
    exit 2
  fi

  # Quote each mvn arg for safe embedding in the bash -ec body.
  local quoted=""
  for a in "${mvn_args[@]}"; do
    quoted+=" $(printf '%q' "$a")"
  done

  in_docker "$MAVEN_IMAGE" \
    "${docker_opts[@]+"${docker_opts[@]}"}" \
    -v mockserver-m2-cache:/root/.m2 \
    -- bash -ec "${ca_install_prelude}exec${quoted}"
}

# Emit a shell snippet that installs the host's corp CA bundle into the
# container's OS trust store AND the JDK truststore. Designed to be the
# first line of a `bash -ec '...'` heredoc passed to in_docker via
# Maven/Java containers.
#
# Idempotent and silent in CI (where /etc/ssl/local-ca.pem isn't mounted)
# so the same heredoc body works in both environments.
ca_install_prelude='
if [ -f /etc/ssl/local-ca.pem ]; then
  if command -v update-ca-certificates >/dev/null 2>&1; then
    cp /etc/ssl/local-ca.pem /usr/local/share/ca-certificates/local-ca.crt 2>/dev/null || true
    update-ca-certificates --fresh >/dev/null 2>&1 || true
  fi
  if command -v keytool >/dev/null 2>&1 && [ -n "${JAVA_HOME:-}" ] && [ -f "${JAVA_HOME}/lib/security/cacerts" ]; then
    keytool -delete -alias local-ca -keystore "${JAVA_HOME}/lib/security/cacerts" -storepass changeit >/dev/null 2>&1 || true
    keytool -importcert -noprompt -trustcacerts -alias local-ca -file /etc/ssl/local-ca.pem -keystore "${JAVA_HOME}/lib/security/cacerts" -storepass changeit >/dev/null 2>&1 || true
  fi
fi
'

# -----------------------------------------------------------------------------
# Cross-step state
#
# Some components produce values that downstream components need (e.g.
# versioned-site computes WEBSITE_BUCKET which helm/javadoc/website/schema
# read). On a single host these can be exported as env vars, but in CI each
# step runs on a different agent so we need persistent storage.
#
# The release scripts are CI-agnostic: they write outputs to a known file
# (.tmp/release-outputs.env) using set_release_output. The CI adapter is
# responsible for syncing that file to the CI's own cross-step state (e.g.
# Buildkite meta-data) and seeding env vars for the next step.
# -----------------------------------------------------------------------------

RELEASE_OUTPUTS_FILE="$REPO_ROOT/.tmp/release-outputs.env"

set_release_output() {
  local key="$1" value="$2"
  mkdir -p "$REPO_ROOT/.tmp"
  # Remove any previous value for this key, then append the new one.
  if [[ -f "$RELEASE_OUTPUTS_FILE" ]]; then
    grep -v "^${key}=" "$RELEASE_OUTPUTS_FILE" > "$RELEASE_OUTPUTS_FILE.tmp" || true
    mv "$RELEASE_OUTPUTS_FILE.tmp" "$RELEASE_OUTPUTS_FILE"
  fi
  printf '%s=%s\n' "$key" "$value" >> "$RELEASE_OUTPUTS_FILE"
  export "$key=$value"
}

# -----------------------------------------------------------------------------
# AWS helpers
# -----------------------------------------------------------------------------

# Load a JSON secret from AWS Secrets Manager. Returns the value of the
# specified key. In --dry-run mode, returns a placeholder so scripts can
# exercise their build/check/lint logic without needing real credentials.
load_secret() {
  local secret_id="$1" key="$2"
  if is_dry_run && [[ -z "${LOAD_REAL_SECRETS_IN_DRY_RUN:-}" ]]; then
    echo "DRY_RUN_PLACEHOLDER_${key^^}"
    return
  fi
  local xtrace_state
  xtrace_state=$(shopt -po xtrace 2>/dev/null || true)
  set +x
  local -a aws_args=(--region "$REGION" --secret-id "$secret_id" --query SecretString --output text)
  [[ -n "${AWS_PROFILE:-}" ]] && aws_args+=(--profile "$AWS_PROFILE")
  local json
  json=$(aws secretsmanager get-secret-value "${aws_args[@]}")
  echo "$json" | jq -r ".$key"
  eval "$xtrace_state"
}

assume_website_role() {
  # Note: NOT skipped in dry-run — terraform plan against the website account
  # needs these creds. Callers who only want write-side actions (S3 sync,
  # CloudFront invalidation) wrap their own dry-run guards around them.
  # Suppress xtrace BEFORE loading any secret so neither the role ARN nor the
  # external id can leak to stderr if a caller had `set -x` active.
  local xtrace_state
  xtrace_state=$(shopt -po xtrace 2>/dev/null || true)
  set +x
  local role_arn
  role_arn=$(load_secret "mockserver-release/website-role" "role_arn")
  local external_id
  external_id=$(load_secret "mockserver-release/website-role" "external_id" 2>/dev/null || true)
  local -a assume_args=(
    --role-arn "$role_arn"
    --role-session-name "mockserver-release-${RELEASE_VERSION}"
    --duration-seconds 3600
    --output json
  )
  if [[ -n "${external_id:-}" && "$external_id" != "null" ]]; then
    assume_args+=(--external-id "$external_id")
  fi
  local creds
  creds=$(aws sts assume-role "${assume_args[@]}")
  AWS_ACCESS_KEY_ID=$(echo "$creds" | jq -r '.Credentials.AccessKeyId')
  AWS_SECRET_ACCESS_KEY=$(echo "$creds" | jq -r '.Credentials.SecretAccessKey')
  AWS_SESSION_TOKEN=$(echo "$creds" | jq -r '.Credentials.SessionToken')
  export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
  eval "$xtrace_state"
}

# -----------------------------------------------------------------------------
# Git helpers
# -----------------------------------------------------------------------------

sync_to_origin_master() {
  if is_dry_run; then
    log_dry "skip: git fetch + reset --hard origin/master"
    return
  fi
  git -C "$REPO_ROOT" fetch --quiet --tags origin master
  # Reset to FETCH_HEAD (the ref just fetched) rather than the origin/master
  # remote-tracking ref. In a normal clone `fetch origin master` updates
  # refs/remotes/origin/master too, but in a shallow/CI clone whose configured
  # refspec doesn't cover the branch it may only move FETCH_HEAD — and then
  # `reset --hard origin/master` would silently land on a stale commit.
  git -C "$REPO_ROOT" reset --quiet --hard FETCH_HEAD
}

# Configure git identity (no-op if already configured) and install a push
# credential via http.extraheader. Idempotent. Skipped entirely in dry-run.
#
# Audit finding F-BK-03: the http.extraheader contains the GitHub token in
# base64. On agents where the workspace persists between builds (Buildkite
# default), this header survives into the next job. Cleanup is performed
# synchronously by `clear_git_push_credentials` in the push helpers below —
# we don't rely on EXIT traps because individual release scripts overwrite
# the EXIT trap for their own cleanup (e.g. maven-plugin.sh removes its
# tee'd deploy log).
configure_git_for_push() {
  if is_dry_run; then return; fi

  if [[ -z "$(git -C "$REPO_ROOT" config user.email 2>/dev/null || true)" ]]; then
    git -C "$REPO_ROOT" config user.email "release@mock-server.com"
  fi
  if [[ -z "$(git -C "$REPO_ROOT" config user.name 2>/dev/null || true)" ]]; then
    git -C "$REPO_ROOT" config user.name "MockServer Release"
  fi

  local token
  token=$(load_secret "mockserver-release/github-token" "token" 2>/dev/null || echo "")
  if [[ -n "$token" && "$token" != "null" ]]; then
    # `base64 | tr -d '\n'` is portable across macOS base64 and GNU base64
    # (which wraps at 76 chars by default — that breaks header parsing).
    git -C "$REPO_ROOT" config "http.https://github.com/.extraheader" \
      "AUTHORIZATION: basic $(printf 'x-access-token:%s' "$token" | base64 | tr -d '\n')"
  fi
}

# Synchronous companion to configure_git_for_push.
# Idempotent: silent no-op if the header was never set.
# Always safe to call multiple times.
clear_git_push_credentials() {
  git -C "$REPO_ROOT" config --unset "http.https://github.com/.extraheader" 2>/dev/null || true
}

git_commit_and_push() {
  local message="$1"; shift
  local -a paths=("$@")
  if is_dry_run; then
    log_dry "would: git add ${paths[*]}"
    log_dry "would: git commit -m \"$message\""
    log_dry "would: git push origin HEAD:master"
    return
  fi
  configure_git_for_push
  # F-BK-03: clear extraheader on every exit path of this function — including
  # early exit from `set -e`, signals, and rebase-retry failure — so the token
  # does not persist on the agent workspace. The RETURN trap fires when the
  # function returns (normally or via `return`) and does not clobber the
  # caller's EXIT trap.
  trap 'clear_git_push_credentials' RETURN
  local rc=0
  {
    git -C "$REPO_ROOT" add "${paths[@]}"
    # Idempotent: a re-run may find the change already committed by an earlier
    # run. `git commit` errors on an empty commit, so skip when nothing staged.
    if git -C "$REPO_ROOT" diff --cached --quiet; then
      log_info "Nothing to commit ($message) - already up to date"
    else
      git -C "$REPO_ROOT" commit -m "$message"
      # Retry on non-fast-forward: if someone pushed to master while we were
      # building, rebase the release commit on top of the new tip and retry.
      # Bounded retries to fail loud if there's a systemic conflict.
      local attempts=0
      while ! git -C "$REPO_ROOT" push origin HEAD:master 2>/tmp/push_err.$$; do
        if ! grep -qE "non-fast-forward|rejected" /tmp/push_err.$$; then
          cat /tmp/push_err.$$ >&2
          rm -f /tmp/push_err.$$
          rc=1
          break
        fi
        attempts=$((attempts + 1))
        if [[ "$attempts" -gt 5 ]]; then
          log_error "Push to master kept losing the race after $attempts retries"
          cat /tmp/push_err.$$ >&2
          rm -f /tmp/push_err.$$
          rc=1
          break
        fi
        log_info "Push rejected (non-fast-forward) — rebasing on origin/master and retrying ($attempts)"
        git -C "$REPO_ROOT" fetch --quiet origin master
        git -C "$REPO_ROOT" rebase origin/master
      done
      rm -f /tmp/push_err.$$
    fi
  } || rc=$?
  return $rc
}

git_tag_and_push() {
  local tag="$1"
  if is_dry_run; then
    log_dry "would: git tag $tag"
    log_dry "would: git push origin $tag"
    return
  fi
  configure_git_for_push
  # F-BK-03: clear extraheader on every exit path — RETURN trap fires even on
  # early exit from `set -e` within the function, without clobbering the
  # caller's EXIT trap.
  trap 'clear_git_push_credentials' RETURN
  local rc=0
  {
    # Idempotent: a re-run may find the tag already created by an earlier run.
    if git -C "$REPO_ROOT" rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1 \
      || git -C "$REPO_ROOT" ls-remote --exit-code origin "refs/tags/$tag" >/dev/null 2>&1; then
      log_info "Tag $tag already exists - skipping"
    else
      git -C "$REPO_ROOT" tag "$tag"
      git -C "$REPO_ROOT" push origin "$tag"
    fi
  } || rc=$?
  return $rc
}

# -----------------------------------------------------------------------------
# Package-manager channel helpers
#
# Shared by the optional package-manager publish components (scoop, winget,
# chocolatey, homebrew, sdkman, asdf). Those channels all publish the SAME
# self-contained jlink bundles that the `binary` component uploads to the
# GitHub Release "mockserver-<version>":
#
#   mockserver-<version>-linux-x86_64.tar.gz   (+ .sha256 sidecar)
#   mockserver-<version>-linux-aarch64.tar.gz  (+ .sha256)
#   mockserver-<version>-darwin-x86_64.tar.gz  (+ .sha256)
#   mockserver-<version>-darwin-aarch64.tar.gz (+ .sha256)
#   mockserver-<version>-windows-x86_64.zip    (+ .sha256)
#
# Each archive expands to a single top directory
# `mockserver-<version>-<os>-<arch>/` containing `bin/mockserver`
# (or `bin/mockserver.bat`), `lib/mockserver.jar` and a trimmed `runtime/`.
# -----------------------------------------------------------------------------

# GitHub Release asset download base for the current RELEASE_VERSION.
release_download_base() {
  echo "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-$RELEASE_VERSION"
}

# Bundle archive basename for an os/arch, e.g.
#   bundle_asset_name windows x86_64 zip -> mockserver-1.2.3-windows-x86_64.zip
bundle_asset_name() {
  local os="$1" arch="$2" ext="$3"
  echo "mockserver-$RELEASE_VERSION-$os-$arch.$ext"
}

# Placeholder SHA256 rendered into manifests when the real checksum can't be
# resolved (dry-run against a not-yet-published version). 64 zeros is obviously
# fake, so a manifest that ever shipped with it is trivially greppable.
PM_PLACEHOLDER_SHA256="0000000000000000000000000000000000000000000000000000000000000000"

# Fetch the SHA256 of a release asset from its `.sha256` sidecar (the format
# `binary.sh` publishes: `<hash>  <filename>`). Echoes the hash on stdout.
# Returns 0 when the real hash was fetched, non-zero (and echoes
# PM_PLACEHOLDER_SHA256) when the sidecar is unreachable — e.g. the bundles are
# not published yet, or this is a fake smoke-test version. Callers decide
# whether a miss means "render with placeholder and continue" (dry-run) or
# "skip this channel" (execute).
fetch_release_sha256() {
  local asset="$1"
  local url line hash
  url="$(release_download_base)/$asset.sha256"
  if line=$(curl -fsSL --max-time 60 "$url" 2>/dev/null); then
    hash=$(printf '%s\n' "$line" | awk 'NR==1{print $1}')
    if [[ "$hash" =~ ^[0-9a-fA-F]{64}$ ]]; then
      echo "$hash"
      return 0
    fi
  fi
  echo "$PM_PLACEHOLDER_SHA256"
  return 1
}

# Returns 0 if the named Secrets Manager secret exists. In dry-run it never
# touches AWS and always returns 0, so a local `--dry-run` smoke test renders
# the manifest without credentials. In execute mode a missing secret returns
# non-zero, letting the component skip an unconfigured optional channel cleanly
# instead of failing the release.
pm_secret_available() {
  local secret_id="$1"
  is_dry_run && return 0
  local -a args=(--region "$REGION" --secret-id "$secret_id")
  [[ -n "${AWS_PROFILE:-}" ]] && args+=(--profile "$AWS_PROFILE")
  aws secretsmanager describe-secret "${args[@]}" >/dev/null 2>&1
}

# Returns 0 if a public GitHub repo (owner/name) exists and is reachable. Used
# by the channels that publish by pushing to a companion repo (scoop bucket,
# asdf plugin, homebrew tap) so the component skips gracefully until that repo
# has been created. dry-run always returns 0 (no network dependency locally).
pm_repo_available() {
  local repo="$1"
  is_dry_run && return 0
  curl -fsSL -o /dev/null --max-time 30 "https://api.github.com/repos/$repo" 2>/dev/null
}
