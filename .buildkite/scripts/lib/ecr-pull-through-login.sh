#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Opt into the ECR pull-through cache for the Docker-gated test steps.
#
# WHAT IT DOES (when enabled and reachable): logs the HOST docker daemon in to
# the mockserver-build account's regional ECR registry and exports
# MOCKSERVER_TEST_IMAGE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com. The
# step then pre-pulls the ECR pull-through names (warming the shared daemon
# cache) and passes the same env var into run-in-docker, so the Testcontainers
# suites resolve the identical ECR names (see test-images.sh / TestContainerImages).
#
# FAIL-SAFE + OPT-IN. This is meant to be SOURCED into a `set -e` step and MUST
# NOT abort it. It does nothing unless MOCKSERVER_ECR_PULL_THROUGH=true, and on
# ANY problem (no opt-in, PR build, no aws/docker, no creds, login failure) it
# leaves MOCKSERVER_TEST_IMAGE_REGISTRY UNSET, so the step falls back to the
# public registries exactly as it does today. This is why the code change is safe
# to ship BEFORE ecr-pull-through-cache.tf is applied: with the flag off (or the
# cache absent) behaviour is unchanged.
#
# The account id is derived at runtime via `aws sts get-caller-identity` so no
# AWS account identifier is hard-coded in the repo (AGENTS.md).
# ──────────────────────────────────────────────────────────────────────

_ecr_pull_through_login() {
  [[ "${MOCKSERVER_ECR_PULL_THROUGH:-false}" == "true" ]] || return 1

  # PR builds have no Docker socket (run-in-docker.sh withholds it) and the step
  # is skipped, so there is nothing to log in for. Mirror that gate.
  local pr="${BUILDKITE_PULL_REQUEST:-false}"
  if [[ "$pr" != "false" && "${ALLOW_PR_DOCKER_SOCKET:-false}" != "true" ]]; then
    return 1
  fi

  command -v aws >/dev/null 2>&1 || return 1
  command -v docker >/dev/null 2>&1 || return 1

  local region="${AWS_REGION:-${AWS_DEFAULT_REGION:-eu-west-2}}"
  local account
  account="$(aws sts get-caller-identity --query Account --output text 2>/dev/null)" || return 1
  [[ -n "$account" && "$account" != "None" ]] || return 1

  local registry="${account}.dkr.ecr.${region}.amazonaws.com"
  aws ecr get-login-password --region "$region" 2>/dev/null \
    | docker login --username AWS --password-stdin "$registry" >/dev/null 2>&1 || return 1

  export MOCKSERVER_TEST_IMAGE_REGISTRY="$registry"
  return 0
}

if _ecr_pull_through_login; then
  echo "--- :aws: ECR pull-through cache enabled — images resolve to ${MOCKSERVER_TEST_IMAGE_REGISTRY}"
else
  echo "--- :information_source: ECR pull-through cache not enabled — using public image registries"
  unset MOCKSERVER_TEST_IMAGE_REGISTRY 2>/dev/null || true
fi
