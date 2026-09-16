#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/../docker-compose.sh"
source "${SCRIPT_DIR}/../logging.sh"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

# Assert the baked AppCDS archive (/mockserver.jsa) is ACTUALLY MAPPED at runtime,
# not merely present on disk. The image build only does `ls -l /mockserver.jsa`
# (existence), and the entrypoint runs the JVM default -Xshare:auto, under which a
# missing / corrupt / bind-mounted-away / arch-mismatched archive logs a warning and
# starts normally — silently giving back the measured ~34% startup win (855ms -> 566ms,
# 7.3.1-SNAPSHOT, 2026-07-02) with nothing anywhere failing.
#
# The compose file forces -Xshare:on via JAVA_TOOL_OPTIONS. Under -Xshare:on the JVM
# ABORTS initialisation when the archive cannot be mapped, so the server never binds and
# never becomes ready. This makes readiness a clean BOOLEAN proof the archive mapped:
# no wall-clock timing is parsed, so the check cannot be flaky and can safely block a
# merge. -Xlog:cds additionally lets us confirm the DYNAMIC /mockserver.jsa archive
# mapped (not just the base CDS archive), which also guards the entrypoint wiring.
function integration_test() {
  trap 'dump_compose_diagnostics_on_failure "$?"; tear-down 2>/dev/null || true' EXIT
  start-up
  TEST_EXIT_CODE=0

  # CORE GATE. Because -Xshare:on is forced, a server that becomes ready has necessarily
  # mapped /mockserver.jsa — an unusable archive would have aborted JVM init before the
  # port was bound.
  if ! wait_ready "mockserver"; then
    printFailureMessage "MockServer did not become ready under -Xshare:on: the AppCDS archive /mockserver.jsa was NOT mapped (missing, corrupt, bind-mounted away, or built for a different architecture). Startup has silently regressed by ~34% because the shipped entrypoint uses -Xshare:auto, which only warns. Look at docker/Dockerfile: the -XX:ArchiveClassesAtExit training run that produces /mockserver.jsa, the COPY of /mockserver.jsa into the runtime image, and the ENTRYPOINT's -XX:SharedArchiveFile=/mockserver.jsa."
    TEST_EXIT_CODE=1
    logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
    return ${TEST_EXIT_CODE}
  fi

  # Sanity: the ready server actually serves the status endpoint (PUT method).
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    STATUS_RESPONSE=$(docker-exec-client "curl -s -o /dev/null -w '%{http_code}' -X PUT 'http://mockserver:1080/mockserver/status'") || TEST_EXIT_CODE=1
    if [[ "${TEST_EXIT_CODE}" == "0" && "${STATUS_RESPONSE}" != "200" ]]; then
      printFailureMessage "MockServer status endpoint returned unexpected HTTP status: \"${STATUS_RESPONSE}\" (expected 200)"
      TEST_EXIT_CODE=1
    fi
  fi

  # CONFIRMATION: the dynamic archive specifically mapped. The -Xlog:cds "Opened shared
  # archive file /mockserver.jsa" line is printed only when the JVM successfully opens and
  # maps THAT archive. It is absent if the entrypoint ever stopped pointing
  # -XX:SharedArchiveFile at /mockserver.jsa (the server would still boot on the base CDS
  # archive alone under -Xshare:on, so the readiness gate above would not catch that).
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    CONTAINER_ID=$(docker-compose -p "${TEST_CASE}" ps -q mockserver)
    if [[ -z "${CONTAINER_ID}" ]]; then
      printFailureMessage "Could not resolve the mockserver container id for the AppCDS log check"
      TEST_EXIT_CODE=1
    else
      # `|| true`: grep exits non-zero when it matches nothing, which under `set -o pipefail`
      # would abort the test before it can record a real result.
      CDS_LOG=$(docker logs "${CONTAINER_ID}" 2>&1 | grep -F '[cds]' || true)
      if ! echo "${CDS_LOG}" | grep -qF "Opened shared archive file /mockserver.jsa"; then
        printFailureMessage "The dynamic AppCDS archive /mockserver.jsa was not reported as mapped by -Xlog:cds. The server booted under -Xshare:on but on the base CDS archive alone, which means the entrypoint is no longer loading /mockserver.jsa (check -XX:SharedArchiveFile in docker/Dockerfile's ENTRYPOINT). Startup has silently regressed. CDS log was: \"${CDS_LOG}\""
        TEST_EXIT_CODE=1
      fi
    fi
  fi

  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  # tear-down handled by EXIT trap above.
  return ${TEST_EXIT_CODE}
}

integration_test
