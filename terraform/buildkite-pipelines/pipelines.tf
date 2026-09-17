locals {
  repository = "https://github.com/mock-server/mockserver-monorepo.git"

  pipelines = {
    "pipeline" = {
      name        = "MockServer"
      description = "Monorepo CI — path-based pipeline orchestrator"
      file        = ".buildkite/pipeline.yml"
      emoji       = ":pipeline:"
      trigger     = "code"
    }
    "java" = {
      name        = "MockServer Java"
      description = "Java server build and test (all Maven modules)"
      file        = ".buildkite/pipeline-java.yml"
      emoji       = ":maven:"
      trigger     = "none"
    }
    "ui" = {
      name        = "MockServer UI"
      description = "Dashboard React SPA — lint, test, build"
      file        = ".buildkite/pipeline-ui.yml"
      emoji       = ":react:"
      trigger     = "none"
    }
    "node" = {
      name        = "MockServer Node"
      description = "Node.js client and launcher — lint and typecheck"
      file        = ".buildkite/pipeline-node.yml"
      emoji       = ":node:"
      trigger     = "none"
    }
    "python" = {
      name        = "MockServer Python"
      description = "Python client — unit and integration tests"
      file        = ".buildkite/pipeline-python.yml"
      emoji       = ":python:"
      trigger     = "none"
    }
    "ruby" = {
      name        = "MockServer Ruby"
      description = "Ruby client — unit and integration tests"
      file        = ".buildkite/pipeline-ruby.yml"
      emoji       = ":ruby:"
      trigger     = "none"
    }
    "go" = {
      name        = "MockServer Go"
      description = "Go client and testcontainers module — unit tests"
      file        = ".buildkite/pipeline-go.yml"
      emoji       = ":golang:"
      trigger     = "none"
    }
    "dotnet" = {
      name        = "MockServer Dotnet"
      description = ".NET client and testcontainers module — unit tests"
      file        = ".buildkite/pipeline-dotnet.yml"
      emoji       = ":dotnet:"
      trigger     = "none"
    }
    "rust" = {
      name        = "MockServer Rust"
      description = "Rust client and testcontainers module — tests and clippy"
      file        = ".buildkite/pipeline-rust.yml"
      emoji       = ":rust:"
      trigger     = "none"
    }
    "php" = {
      name        = "MockServer PHP"
      description = "PHP client — unit tests"
      file        = ".buildkite/pipeline-php.yml"
      emoji       = ":php:"
      trigger     = "none"
    }
    "editors" = {
      name        = "MockServer Editors"
      description = "VS Code extension and JetBrains plugin — build and test"
      file        = ".buildkite/pipeline-editors.yml"
      emoji       = ":vscode:"
      trigger     = "none"
    }
    "maven-plugin" = {
      name        = "MockServer Maven Plugin"
      description = "Maven plugin build and test"
      file        = ".buildkite/pipeline-maven-plugin.yml"
      emoji       = ":maven:"
      trigger     = "none"
    }
    "perf-test" = {
      name        = "MockServer Performance Test"
      description = "Performance test validation + daily performance-regression run"
      file        = ".buildkite/pipeline-perf-test.yml"
      emoji       = ":chart_with_upwards_trend:"
      trigger     = "none"
    }
    "container-tests" = {
      name        = "MockServer Container Tests"
      description = "Container integration test script validation"
      file        = ".buildkite/pipeline-container-tests.yml"
      emoji       = ":docker:"
      trigger     = "none"
    }
    "website" = {
      name        = "MockServer Website"
      description = "Jekyll documentation site build"
      file        = ".buildkite/pipeline-website.yml"
      emoji       = ":jekyll:"
      trigger     = "none"
    }
    "infra" = {
      name        = "MockServer Infra"
      description = "Infrastructure, CI/CD, and shared config validation"
      file        = ".buildkite/pipeline-infra.yml"
      emoji       = ":terraform:"
      trigger     = "none"
    }
    "docker-push-maven" = {
      name        = "MockServer Build Image"
      description = "Build and push mockserver/mockserver:maven CI image"
      file        = ".buildkite/docker-push-maven.yml"
      emoji       = ":docker:"
      trigger     = "none"
    }
    "cleanup" = {
      name        = "MockServer Cleanup"
      description = "Cancel and delete Buildkite builds for closed/merged PRs"
      file        = ".buildkite/pipeline-cleanup.yml"
      emoji       = ":broom:"
      trigger     = "none"
    }
    "release" = {
      name        = "MockServer Release"
      description = "Automated release pipeline for MockServer"
      file        = ".buildkite/release-pipeline.yml"
      emoji       = ":rocket:"
      trigger     = "none"
    }
    "release-preflight" = {
      name        = "MockServer Release Preflight"
      description = "Validates release-queue and default-queue agents have every tool the release pipeline needs"
      file        = ".buildkite/release-preflight-pipeline.yml"
      emoji       = ":mag:"
      trigger     = "none"
    }
  }
}

resource "buildkite_pipeline_schedule" "cleanup_daily" {
  pipeline_id = buildkite_pipeline.pipeline["cleanup"].id
  label       = "Daily closed PR cleanup"
  cronline    = "0 6 * * *"
  branch      = "master"
  message     = "Scheduled: clean up closed PR builds"
}

# Daily performance-regression run. Fires every day at 04:00 UTC (off-peak,
# before the 06:00 cleanup). The build arrives with build.source == 'schedule',
# which the perf-test pipeline's commit-guard step keys off; the guard then
# dispatches the heavy run ONLY when master moved since the last successful run,
# so an idle day costs just the cheap guard query.
resource "buildkite_pipeline_schedule" "perf_regression_daily" {
  pipeline_id = buildkite_pipeline.pipeline["perf-test"].id
  label       = "Daily performance regression"
  cronline    = "0 4 * * *"
  branch      = "master"
  message     = "Scheduled: daily performance regression run"
}

# Perf baseline-freshness safety-net. Gives the baseline-freshness assertion
# (.buildkite/scripts/steps/perf-baseline-freshness.sh, in pipeline-infra.yml) a
# GUARANTEED daily cadence of its own, instead of relying on someone happening to
# touch an infra path. It runs in mockserver-infra — a DIFFERENT pipeline from the
# perf producer above — precisely so it survives the producer dying: a decay
# detector that shares the producer's schedule dies with it. Fires at 16:00 UTC,
# well clear of the producer (04:00) and the cleanup sweep (06:00), so a dead
# producer schedule is detected within ~a day. The freshness step keys off the
# producer's Buildkite build liveness (not S3), so this schedule needs no extra
# credentials. Autoscaling is unchanged: this only enqueues a build; the queue
# scales from and back to min_size = 0 on demand.
resource "buildkite_pipeline_schedule" "infra_baseline_freshness_daily" {
  pipeline_id = buildkite_pipeline.pipeline["infra"].id
  label       = "Daily perf baseline freshness check"
  cronline    = "0 16 * * *"
  branch      = "master"
  message     = "Scheduled: assert perf baseline freshness (safety-net)"
}

locals {
  # Audit finding F-BK-CLOUD-02: pipelines that load secrets via AWS Secrets
  # Manager must be PRIVATE so their build logs are not world-readable. The
  # auto-redaction Buildkite applies is pattern-based and not infallible.
  #
  # Pipelines that don't load secrets — or where the token is fully
  # protected by `set +x` guards and never appears in stdout/stderr —
  # can remain PUBLIC. Public visibility is necessary for OSS contributor
  # UX: external PR submitters need to see their own build logs to debug
  # failures; flipping these PRIVATE blocks them at a Buildkite login
  # page and forces maintainers to relay log excerpts manually.
  #
  # Note on "pipeline" (the top-level mockserver dispatcher):
  # generate-pipeline.sh fetches a Buildkite API token to query the last
  # successful build for path-based change detection. Mitigation is in
  # depth: `{ set +x; } 2>/dev/null` (F-BK-04) suppresses xtrace before
  # the secret fetch; the token is only sent in a curl `-H Authorization`
  # header (never via stdout-visible flags); Buildkite's secret redaction
  # covers known token patterns. Accepted residual risk: the only
  # plausible leak vector is an AWS CLI error mode that includes secret
  # material in the error message — which AWS CLI does not do in practice.
  # If a future refactor of generate-pipeline.sh removes the API-token
  # dependency entirely (e.g. by caching last-successful-SHA as a build
  # artifact), this caveat goes away.
  public_pipelines = toset([
    "pipeline",     # mockserver — top-level dispatcher (see note above)
    "ui",           # mockserver-ui — lint/test only
    "node",         # mockserver-node — lint/test only
    "python",       # mockserver-python — lint/test only
    "ruby",         # mockserver-ruby — lint/test only
    "maven-plugin", # mockserver-maven-plugin — build/test only
    "go",           # mockserver-go — lint/test only
    "dotnet",       # mockserver-dotnet — lint/test only
    "rust",         # mockserver-rust — lint/test only
    "php",          # mockserver-php — lint/test only
    "editors",      # mockserver-editors — lint/test only
  ])
}

resource "buildkite_pipeline" "pipeline" {
  for_each = local.pipelines

  name           = each.value.name
  description    = each.value.description
  repository     = local.repository
  default_branch = "master"
  emoji          = each.value.emoji
  visibility     = contains(local.public_pipelines, each.key) ? "PUBLIC" : "PRIVATE"

  # Assign every pipeline to the Default cluster (Buildkite deprecated
  # unclustered agents — see clusters.tf). Agents register with the cluster
  # token and only run jobs from pipelines in this cluster.
  cluster_id = data.buildkite_cluster.default.id

  # Cancel superseded in-progress builds on feature/PR branches (saves agent VMs
  # during rapid iteration), but NEVER on master: a master build that is canceled
  # mid-run reports as a misleading failure on the triggering pipeline, and the
  # commit goes untested. Long pipelines (container-tests ~20m, performance-test)
  # were the worst hit because, without this filter, every fresh master commit
  # canceled the previous still-running build before it could report. master builds
  # now always run to completion and report true pass/fail. Skipping still applies
  # to queued (not-yet-started) builds on all branches — those report as "skipped"
  # (neutral), not red, so they are left unfiltered.
  cancel_intermediate_builds               = true
  cancel_intermediate_builds_branch_filter = "!master"
  skip_intermediate_builds                 = true

  steps = "steps:\n  - label: \":pipeline:\"\n    command: \"buildkite-agent pipeline upload ${each.value.file}\"\n"

  provider_settings = {
    trigger_mode          = each.value.trigger
    build_branches        = each.value.trigger == "code"
    build_pull_requests   = each.value.trigger == "code"
    build_tags            = false
    publish_commit_status = each.value.trigger == "code"
  }
}
