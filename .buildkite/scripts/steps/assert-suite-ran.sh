#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Fail closed when a Docker-gated test suite skipped instead of running.
#
# Suites guarded by `Assume.assumeTrue(DockerAvailability.isAvailable(...))`
# report as SKIPPED and let Maven exit 0 when Docker is unusable. A CI step
# that only checks Maven's exit code therefore reports green having tested
# nothing. This asserts the suite actually executed non-skipped tests.
#
# This is the necessary counterpart to a fail-SAFE probe: the probe degrades an
# unusable Docker to a SKIP (rather than an ERROR, which would defeat the guard
# off-CI), and this script turns that SKIP back into a loud failure in CI, where
# skipping silently is exactly the false positive we are removing.
#
# Usage:
#   assert-suite-ran.sh <test-report-glob> [<test-report-glob> ...]
#
# Example:
#   assert-suite-ran.sh 'mockserver-blob-s3/target/surefire-reports/TEST-*ContractTest.xml'
#   assert-suite-ran.sh 'mockserver-async/target/failsafe-reports/TEST-*IntegrationTest.xml'
#
# Works for BOTH surefire and failsafe reports: Failsafe declares a different xsd
# but emits the same <testsuite tests= skipped=> attributes this parses. Point the
# glob at failsafe-reports for *IT/*IntegrationTest suites, and run it after the
# `verify` phase rather than `test`.
#
# Exits non-zero if any glob matches no report, or if a matched report shows
# zero tests or every test skipped.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: assert-suite-ran.sh <test-report-glob> [...]" >&2
  exit 2
fi

status=0

for pattern in "$@"; do
  # shellcheck disable=SC2086
  reports=$(ls $pattern 2>/dev/null || true)
  if [ -z "$reports" ]; then
    echo "+++ :bangbang: no test report matched '${pattern}' — the suite did not run" >&2
    status=1
    continue
  fi

  while IFS= read -r report; do
    [ -n "$report" ] || continue
    tests=$(sed -n 's/.*<testsuite[^>]* tests="\([0-9]*\)".*/\1/p' "$report" | head -1)
    skipped=$(sed -n 's/.*<testsuite[^>]* skipped="\([0-9]*\)".*/\1/p' "$report" | head -1)
    tests=${tests:-0}
    skipped=${skipped:-0}

    if [ "$tests" -eq 0 ] || [ "$tests" -eq "$skipped" ]; then
      echo "+++ :bangbang: $(basename "$report"): ${tests} tests, ${skipped} skipped — suite did not execute, failing closed" >&2
      status=1
    else
      echo "--- :white_check_mark: $(basename "$report"): ${tests} tests ran (${skipped} skipped)"
    fi
  done <<EOF
$reports
EOF
done

exit $status
