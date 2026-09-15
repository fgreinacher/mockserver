#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Resolver for the container images the Docker-gated Java steps pre-pull.
#
# SINGLE SOURCE OF TRUTH. The public image names/tags live once, in
#   mockserver/mockserver-testing/src/main/resources/org/mockserver/test/test-container-images.properties
# read here (shell / pre-pull call sites) AND by TestContainerImages.java (the
# suites), so the pre-pull list and the test constants can no longer drift.
#
# This file is meant to be SOURCED, and exposes:
#   test_image <key>          -> the reference for <key> (public, or ECR-rewritten
#                                when MOCKSERVER_TEST_IMAGE_REGISTRY is set)
#   resolve_test_image <ref>  -> <ref> rewritten to the ECR pull-through cache
#                                when MOCKSERVER_TEST_IMAGE_REGISTRY is set, else <ref>
#
# The rewrite MUST match TestContainerImages.toEcr (Java) and the
# ecr_repository_prefix values in terraform/buildkite-agents/ecr-pull-through-cache.tf:
#   quay.io            -> quay/<path>          (host stripped)
#   Docker Hub         -> docker-hub/<path>    (official single-name images get library/)
#   mcr.microsoft.com  -> UNCHANGED            (ECR cannot cache mcr; azurite pulls direct)
# ──────────────────────────────────────────────────────────────────────

# Repo root relative to THIS file (.buildkite/scripts/lib/test-images.sh).
_TEST_IMAGES_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_TEST_IMAGES_REPO_ROOT="$(cd "${_TEST_IMAGES_LIB_DIR}/../../.." && pwd)"
_TEST_IMAGES_PROPS="${_TEST_IMAGES_REPO_ROOT}/mockserver/mockserver-testing/src/main/resources/org/mockserver/test/test-container-images.properties"

# Rewrite a public image reference to its ECR pull-through cache equivalent when
# MOCKSERVER_TEST_IMAGE_REGISTRY is set; otherwise echo it unchanged.
resolve_test_image() {
  local ref="$1"
  local registry="${MOCKSERVER_TEST_IMAGE_REGISTRY:-}"
  if [[ -z "$registry" ]]; then
    printf '%s\n' "$ref"
    return 0
  fi

  # Split off a trailing ":tag" (the ':' after the last '/'). Our tags contain no
  # ':' and none of our hosts carry a ":port", so this is unambiguous.
  local after_slash="${ref##*/}"
  local name tag
  if [[ "$after_slash" == *:* ]]; then
    tag=":${after_slash#*:}"
    name="${ref%:*}"
  else
    tag=""
    name="$ref"
  fi

  local first="${name%%/*}"
  local prefix path
  if [[ "$name" == */* && ( "$first" == *.* || "$first" == *:* ) ]]; then
    # Registry-qualified: strip the host, map it to its ECR prefix.
    case "$first" in
      quay.io) prefix="quay"; path="${name#*/}" ;;
      mcr.microsoft.com)
        # mcr.microsoft.com is NOT a supported ECR pull-through upstream
        # (UnsupportedUpstreamRegistryException on rule creation), so azurite
        # always pulls direct from Microsoft -- pass the ref through UNCHANGED.
        # Deliberate passthrough, not a fallthrough (see ecr-pull-through-cache.tf
        # and TestContainerImages.toEcr).
        printf '%s\n' "$ref"
        return 0
        ;;
      *)
        echo "test-images.sh: no ECR pull-through prefix mapped for host '${first}' in '${ref}'." \
             "Add a rule in ecr-pull-through-cache.tf and map the host here and in TestContainerImages.toEcr" \
             "(or, for an upstream ECR does not support, add an explicit passthrough like mcr.microsoft.com)." >&2
        return 1
        ;;
    esac
  else
    # Docker Hub. Official (single-segment) images live under library/.
    prefix="docker-hub"
    path="$name"
    if [[ "$path" != */* ]]; then
      path="library/${path}"
    fi
  fi
  printf '%s\n' "${registry}/${prefix}/${path}${tag}"
}

# Resolve a properties key to its (possibly ECR-rewritten) image reference.
test_image() {
  local key="$1"
  if [[ ! -f "$_TEST_IMAGES_PROPS" ]]; then
    echo "test-images.sh: image manifest not found at ${_TEST_IMAGES_PROPS}" >&2
    return 1
  fi
  local val
  val="$(grep -E "^${key}=" "$_TEST_IMAGES_PROPS" | head -1 | cut -d= -f2-)"
  if [[ -z "$val" ]]; then
    echo "test-images.sh: no image mapped for key '${key}' in ${_TEST_IMAGES_PROPS}" >&2
    return 1
  fi
  resolve_test_image "$val"
}
