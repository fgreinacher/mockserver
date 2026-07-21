---
description: Commit changes following the full pre-commit workflow (classify, validate, review)
---
Follow the COMPLETE pre-commit workflow defined in `.opencode/rules/commit-workflow.md`. Do NOT skip any steps unless the user explicitly says "skip tests", "skip review", or "just commit".

Validation is mandatory and must be executable where possible. Do not rely only on static inspection.

Parallel session safety is mandatory: commit only files changed in the current session. Never include files you did not edit in this session, and never use blanket staging (`git add .` / `git add -A`).

The authoritative steps are in `commit-workflow.md`; do not re-derive or skip any:

1. **Classify** — `git status --short`; identify only files YOU changed this session; classify by category (java/terraform/bash/docker/docs/config/helm/npm/python/ruby/**control**). Files under `.opencode/rules/**`, `.opencode/agents/**`, `.claude/agents/**`, `.opencode/commands/**`, `.claude/commands/**`, `.opencode/skills/**`, `.opencode/plugins/**`, `.opencode/scripts/**`, `.opencode/evals/**`, `opencode.jsonc`, `.claude/settings*.json`, the review constitution, or CI/test gates are the **control** (AI-component) class — higher-scrutiny (see step 4).
2. **Validate** — run category-specific, executable validations:
   - Java: `./mvnw test -pl <modules>`
   - Terraform: `terraform fmt -check`, `terraform validate`, `terraform plan`
   - Bash: `bash -n <script>` and execute in a safe mode (`--help`/`--version`/`--dry-run`)
   - Docker: `docker build` per changed Dockerfile + a basic smoke command
   - Helm: `helm lint` and `helm template`
   - Website: `bundle exec jekyll build`
   - Docs/config: syntax and link checks
   - **control**: run the evaluation harness in gate mode — `STRICT=1 bash .opencode/evals/run-evals.sh` (a regression blocks; `STRICT=1` also fails closed on a fixture with no recorded baseline, which the lenient form reports as `PENDING … OK`)
3. **Changelog** — if the change is user-facing, add or correct a `## [Unreleased]` entry in `changelog.md` (MANDATORY); if not user-facing, state explicitly why no entry is needed.
4. **Adversarial review** — launch a `review-cheap` subagent (fresh context) on the diff; must return PASS. **Control / AI-component changes use the authoritative `review-final` and are gated-approval** — present the PASS to the user and get explicit approval before committing (separation of duties; never auto-commit a control change). Cap the review loop at 8 iterations; if it does not converge, record the residual risk and escalate — do not commit.
5. **Commit** — only after all gates pass: first run `.opencode/scripts/check-halt.sh commit` (if it exits non-zero, stop — an operator halt is in force); then stage files by explicit path (NEVER `git add .`) and commit with a descriptive message.

If the user provided additional instructions: $ARGUMENTS
