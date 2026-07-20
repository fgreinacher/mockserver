#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/../docker-compose.sh"
source "${SCRIPT_DIR}/../logging.sh"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

function cleanup() {
  # Capture container state + logs on a failing exit BEFORE tear-down removes the
  # containers. Must be the FIRST line so `$?` is still the failing status.
  dump_compose_diagnostics_on_failure "$?"
  tear-down 2>/dev/null || true
  rm -rf "${SCRIPT_DIR}/config"
}

function integration_test() {
  # trap before start-up so config/ dir and any partial state get cleaned up
  # even if start-up itself fails under set -euo pipefail.
  trap cleanup EXIT
  runCommand "rm -rf ${SCRIPT_DIR}/config"
  runCommand "mkdir -p ${SCRIPT_DIR}/config && chmod 777 ${SCRIPT_DIR}/config"

  start-up
  TEST_EXIT_CODE=0
  wait_ready "mockserver" || { TEST_EXIT_CODE=1; logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"; return ${TEST_EXIT_CODE}; }

  # Create an expectation that will be persisted
  docker-exec-client "curl -v -s -X PUT 'http://mockserver:1080/mockserver/expectation' -d \\\"{
                        'httpRequest' : {
                          'path' : '/graceful/path'
                        },
                        'httpResponse' : {
                          'body' : 'graceful_response_body'
                        }
                      }\\\"" || TEST_EXIT_CODE=1

  # Verify the expectation works before shutdown
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    RESPONSE_BODY=$(docker-exec-client "curl -v -s -X PUT 'http://mockserver:1080/graceful/path'")
    if [[ "${RESPONSE_BODY}" != "graceful_response_body" ]]; then
      printFailureMessage "Failed to retrieve response body before shutdown, found: \"${RESPONSE_BODY}\""
      TEST_EXIT_CODE=1
    fi
  fi

  # Wait for persistence to flush (MockServer persists on a schedule)
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    sleep 3

    # Verify the persistence file was written while container is still running
    if [[ ! -s "${SCRIPT_DIR}/config/persistedExpectations.json" ]]; then
      printFailureMessage "Expectations file was not written while container was running"
      TEST_EXIT_CODE=1
    fi
  fi

  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    # Record the modification time of the persisted file BEFORE stopping the container
    PRE_STOP_MTIME=$(stat -f "%m" "${SCRIPT_DIR}/config/persistedExpectations.json" 2>/dev/null \
                     || stat -c "%Y" "${SCRIPT_DIR}/config/persistedExpectations.json" 2>/dev/null)
    # Read from inside the container (docker cp) — the host bind-mount file is written by the
    # container's non-root uid and is not readable by the CI agent on Linux, so a host `cat`
    # aborts under `set -e`. See read_container_file in docker-compose.sh.
    PRE_STOP_CONTENT=$(read_container_file "mockserver" "/config/persistedExpectations.json" || true)
    printMessage "Pre-stop mtime: ${PRE_STOP_MTIME}"
    printMessage "Pre-stop content: ${PRE_STOP_CONTENT}"

    # Stop the container gracefully (SIGTERM with default 10s grace period)
    CONTAINER_ID=$(docker-compose -p "${TEST_CASE}" ps -q mockserver)
    printMessage "Stopping container ${CONTAINER_ID} gracefully..."
    docker stop "${CONTAINER_ID}"
    printMessage "Container stopped"

    # Verify the persistence file still exists and has content after container exit
    if [[ ! -s "${SCRIPT_DIR}/config/persistedExpectations.json" ]]; then
      printFailureMessage "Expectations file disappeared or became empty after container stop"
      TEST_EXIT_CODE=1
    fi
  fi

  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    POST_STOP_MTIME=$(stat -f "%m" "${SCRIPT_DIR}/config/persistedExpectations.json" 2>/dev/null \
                      || stat -c "%Y" "${SCRIPT_DIR}/config/persistedExpectations.json" 2>/dev/null)
    # Container is stopped (not removed) here; `docker cp` still reads its filesystem, and
    # read_container_file's `ps -aq` resolves the stopped container. Host `cat` would abort
    # under `set -e` for the same uid/permission reason as pre-stop.
    POST_STOP_CONTENT=$(read_container_file "mockserver" "/config/persistedExpectations.json" || true)
    printMessage "Post-stop mtime: ${POST_STOP_MTIME}"
    printMessage "Post-stop content: ${POST_STOP_CONTENT}"

    # The file content must contain our expectation
    if ! echo "${POST_STOP_CONTENT}" | grep -q "graceful/path"; then
      printFailureMessage "Persisted expectations file does not contain the expected path after stop"
      TEST_EXIT_CODE=1
    fi

    # Verify the mtime is at or after the pre-stop mtime (file was not truncated/lost).
    # Guard against empty mtimes (both stat variants could fail, e.g. on an unusual
    # filesystem) - the integer comparison would otherwise produce a bash error.
    if [[ -z "${PRE_STOP_MTIME:-}" || -z "${POST_STOP_MTIME:-}" ]]; then
      printFailureMessage "Could not read mtime via stat -f/-c (pre=\"${PRE_STOP_MTIME:-}\" post=\"${POST_STOP_MTIME:-}\")"
      TEST_EXIT_CODE=1
    elif [[ "${POST_STOP_MTIME}" -lt "${PRE_STOP_MTIME}" ]]; then
      printFailureMessage "Post-stop mtime (${POST_STOP_MTIME}) is before pre-stop mtime (${PRE_STOP_MTIME}); file may have been corrupted"
      TEST_EXIT_CODE=1
    fi
  fi

  # Restart the container and verify the expectation survives (loaded from persisted file)
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    printMessage "Restarting container to verify persisted expectations are loaded..."
    local files
    files="$(compose-files "${TEST_CASE}")"
    export OVERRIDE_DIR="${SCRIPT_DIR}"
    runCommand "docker-compose ${files} -p ${TEST_CASE} up -d mockserver"
    wait_ready "mockserver" || { TEST_EXIT_CODE=1; logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"; return ${TEST_EXIT_CODE}; }

    RESPONSE_BODY=$(docker-exec-client "curl -v -s -X PUT 'http://mockserver:1080/graceful/path'")
    if [[ "${RESPONSE_BODY}" != "graceful_response_body" ]]; then
      printFailureMessage "Expectation did not survive container restart; response: \"${RESPONSE_BODY}\""
      TEST_EXIT_CODE=1
    else
      printMessage "Expectation survived graceful shutdown and restart"
    fi
  fi

  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  # tear-down + rm config dir handled by EXIT trap above.
  return ${TEST_EXIT_CODE}
}

integration_test
