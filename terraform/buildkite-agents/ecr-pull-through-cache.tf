# ---------------------------------------------------------------------------
# ECR Pull-Through Cache for CI Testcontainers backing images
# ---------------------------------------------------------------------------
# WHY: The two Docker-gated Java steps (:cloud: cloud blob-store contract tests,
# :envelope: asyncapi live-broker tests) pull their Testcontainers backing images
# from quay.io, Docker Hub and mcr.microsoft.com at test time. Cold scale-to-zero
# agents therefore depend on those public registries being reachable and
# un-throttled -- when quay.io throttled, the cloud step failed 2 of 3 builds.
# The host pre-pull (.buildkite/scripts/lib/pre-pull-images.sh) makes that fail
# fast and clearly attributed, but it is a mitigation, not a cure.
#
# This is the durable fix: a regional ECR pull-through cache. CI pulls in-region
# from ECR; ECR contacts each upstream ONLY on a first cache miss, then serves
# every later pull from the cache. The image indirection
# (MOCKSERVER_TEST_IMAGE_REGISTRY -- see .buildkite/scripts/lib/test-images.sh and
# mockserver-testing TestContainerImages.java) rewrites the public names to these
# cache repositories in CI, and leaves them untouched for local developers and
# forks that have no ECR access.
#
# TWO CACHED UPSTREAMS, TWO PREFIXES (docker-hub, quay). The ecr_repository_prefix
# values below are the contract the image-name transform depends on -- keep them
# in lockstep with the prefixes in test-images.sh (resolve_test_image) and
# TestContainerImages.toEcr (locked by TestContainerImagesTest). A cached repo is
# addressed as <account>.dkr.ecr.<region>.amazonaws.com/<prefix>/<upstream-path>:<tag>
#   quay.io/minio/minio                     -> <reg>/quay/minio/minio
#   fsouza/fake-gcs-server                  -> <reg>/docker-hub/fsouza/fake-gcs-server
#   rabbitmq (Docker Hub official)          -> <reg>/docker-hub/library/rabbitmq
#   mcr.microsoft.com/azure-storage/azurite -> UNCHANGED (mcr is not cacheable, see below)
#
# CREDENTIALS: Docker Hub pull-through REQUIRES upstream credentials, and ECR
# requires them in a Secrets Manager secret whose NAME begins
# "ecr-pullthroughcache/" with JSON {"username","accessToken"}. The existing
# mockserver-build/dockerhub secret CANNOT be reused: wrong name prefix AND wrong
# JSON key ("token", not "accessToken"). A dedicated secret CONTAINER is created
# here; its value is populated out of band (same convention as the other
# Docker Hub secrets -- never store the material in Terraform). quay.io is pulled
# anonymously and needs no credential. mcr.microsoft.com is not a supported
# upstream at all (see the missing rule below), so azurite is never cached.
#
# STATUS: APPLIED (2026-09-15). Live state matches this file; terraform plan is clean.
# Applying it is still an irreversible production action — re-check plan before any
# further apply, and note the service-linked role must be imported rather than created
# if it already exists in the account.
#
# Caching is nonetheless OFF for CI until the pipeline opts in with
# MOCKSERVER_ECR_PULL_THROUGH=true. Without it nothing sets
# MOCKSERVER_TEST_IMAGE_REGISTRY and every image resolves to its public name exactly
# as before, so the infrastructure existing changes nothing on its own.
# ---------------------------------------------------------------------------

# Service-linked role ECR uses to read the pull-through credential secret and to
# create the cache repositories on a first miss.
#
# APPLY NOTE: if this role already exists in the account (any prior use of ECR
# pull-through), a clean apply of this resource fails with "role name ... has
# been taken". In that case import it first --
#   terraform import aws_iam_service_linked_role.ecr_pull_through \
#     arn:aws:iam::<account>:role/aws-service-role/pullthroughcache.ecr.amazonaws.com/AWSServiceRoleForECRPullThroughCache
# -- or remove this resource. It was absent at authoring time (the account uses
# ECR Public, a different service).
resource "aws_iam_service_linked_role" "ecr_pull_through" {
  aws_service_name = "pullthroughcache.ecr.amazonaws.com"
  description      = "SLR for ECR pull-through cache (read upstream credential secret, create cache repos)"
}

# Docker Hub upstream credentials for the pull-through cache. Name prefix is
# mandated by ECR; value ({"username","accessToken"}) is set out of band.
resource "aws_secretsmanager_secret" "ecr_pullthroughcache_dockerhub" {
  name        = "ecr-pullthroughcache/dockerhub"
  description = "Docker Hub credentials for the ECR pull-through cache (JSON {username, accessToken}). Value set out of band; name prefix required by ECR."
}

# --- Pull-through cache rules ----------------------------------------------

resource "aws_ecr_pull_through_cache_rule" "docker_hub" {
  ecr_repository_prefix = "docker-hub"
  upstream_registry_url = "registry-1.docker.io"
  credential_arn        = aws_secretsmanager_secret.ecr_pullthroughcache_dockerhub.arn

  # The SLR must exist before ECR will accept a credential-backed rule.
  depends_on = [aws_iam_service_linked_role.ecr_pull_through]
}

resource "aws_ecr_pull_through_cache_rule" "quay" {
  ecr_repository_prefix = "quay"
  upstream_registry_url = "quay.io"
}

# NO mcr.microsoft.com RULE. ECR pull-through cache does NOT support
# mcr.microsoft.com as an upstream -- creating the rule fails with
#   UnsupportedUpstreamRegistryException: The upstream registry URL
#   mcr.microsoft.com is invalid.
# (discovered on the first real apply; the supported upstreams are ECR Public,
# Docker Hub, quay.io, registry.k8s.io, ghcr.io, GitLab, and Azure ACR -- NOT the
# Microsoft public container registry mcr.microsoft.com). Azurite
# (mcr.microsoft.com/azure-storage/azurite) therefore CANNOT be cached: it pulls
# direct from Microsoft and stays dependent on that registry's availability. The
# host pre-pull in .buildkite/scripts/lib/pre-pull-images.sh is its only guard.
# The image transform (TestContainerImages.toEcr / resolve_test_image) passes
# mcr.microsoft.com through UNCHANGED for this reason.

# --- Auto-created cache repositories: encryption + cost-control lifecycle ----
# Applies to every repository ECR auto-creates for a pull-through cache miss
# (the "ROOT" prefix is the catch-all that matches docker-hub/ and quay/).
# Expiring images not pulled in 30 days caps storage cost; a later pull simply
# re-imports from the upstream on the next miss. Pinned tags mean a re-import is
# byte-identical.
resource "aws_ecr_repository_creation_template" "pull_through_cache" {
  prefix      = "ROOT"
  applied_for = ["PULL_THROUGH_CACHE"]
  description = "Settings for ECR pull-through cache repositories (encryption + 30-day expiry)"

  image_tag_mutability = "MUTABLE"

  encryption_configuration {
    encryption_type = "AES256"
  }

  lifecycle_policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Expire cached images not pushed/pulled in 30 days"
      selection = {
        tagStatus   = "any"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = 30
      }
      action = { type = "expire" }
    }]
  })
}

# --- Agent IAM: pull through the cache (and trigger first-miss imports) -------
# Scoped to the three cache-prefix repository ARNs. GetAuthorizationToken is a
# registry-level action (Resource "*"); the pull + import + first-miss create are
# scoped to the cache repositories only. Attached to the default and release
# queues in main.tf (the queues that run the Docker-gated Java steps).
resource "aws_iam_policy" "ecr_pull_through" {
  name        = "buildkite-ecr-pull-through"
  description = "Allow Buildkite agents to pull CI Testcontainers images through the ECR pull-through cache (and trigger first-miss imports)"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuthToken"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid    = "PullAndImportFromCache"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "ecr:BatchImportUpstreamImage", # triggers the upstream fetch on a cache miss
          "ecr:CreateRepository",         # first-miss auto-creation of the cache repository
          "ecr:TagResource",
        ]
        Resource = [
          "arn:aws:ecr:${var.region}:${local.account_id}:repository/docker-hub/*",
          "arn:aws:ecr:${var.region}:${local.account_id}:repository/quay/*",
          # No mcr/* -- mcr.microsoft.com is not a supported pull-through upstream
          # (see the missing rule above); azurite pulls direct from Microsoft.
        ]
      },
    ]
  })
}
