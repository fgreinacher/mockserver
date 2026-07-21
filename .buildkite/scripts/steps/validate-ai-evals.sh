#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# AI-component evaluation harness (.opencode/rules/evaluation-harness.md).
#
# STRICT=1 is what makes this a gate rather than a report: without it the runner
# treats a fixture with no recorded baseline as PENDING and still exits 0, which
# is how the suite sat at "5 fixtures, 0 baselines, OK" before commit aff5730a0.
#
# What this step DOES enforce, mechanically, on every build:
#   * every fixture is well-formed (frontmatter, id matches filename stem,
#     expected_verdict in {PASS,BLOCK,FLAG}, no FLAG on a review agent)
#   * every fixture has a committed .result baseline            (exit 1)
#   * every committed baseline matches its expected_verdict     (exit 1)
#   * the suite is non-empty                                    (exit 2)
#
# What it does NOT do: re-invoke the agents. Buildkite has no model credentials,
# and a live agent run is neither cheap nor deterministic. Recording a baseline
# stays an agent-in-the-loop step run locally (see .opencode/evals/README.md), so
# this gate proves the corpus is complete and self-consistent — it does not prove
# the agents still behave that way today. Re-running the agents is the local
# commit-workflow gate's job on AI-component changes.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i python:3.12 \
  -- bash -c 'STRICT=1 bash .opencode/evals/run-evals.sh'
