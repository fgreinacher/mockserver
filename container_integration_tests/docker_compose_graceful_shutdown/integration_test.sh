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
    # `|| true` so a failed request yields an empty body the assertion below reports as a
    # FAIL, rather than aborting the command-substitution assignment under `set -e`.
    RESPONSE_BODY=$(docker-exec-client "curl -v -s -X PUT 'http://mockserver:1080/graceful/path'" || true)
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
    # Record the modification time of the persisted file BEFORE stopping the container.
    # Read mtime portably: GNU stat (Linux/CI) is `-c %Y`, BSD stat (macOS) is `-f %m`.
    # GNU MUST be tried first: on Linux `stat -f "%m"` means `--file-system` and does NOT
    # fail cleanly — it prints a filesystem-info blob for the file and treats "%m" as a
    # bogus operand, so a BSD-first order captured that non-numeric blob (plus the fallback
    # number), and the `-lt` arithmetic below then aborted the whole script under `set -u`
    # ("File: unbound variable") before a result was recorded. `|| true` keeps a total
    # failure as an empty value, caught by the numeric guard below.
    PRE_STOP_MTIME=$(stat -c "%Y" "${SCRIPT_DIR}/config/persistedExpectations.json" 2>/dev/null \
                     || stat -f "%m" "${SCRIPT_DIR}/config/persistedExpectations.json" 2>/dev/null \
                     || true)
    # Read from inside the container (docker cp) — the host bind-mount file is written by the
    # container's non-root uid and is not readable by the CI agent on Linux, so a host `cat`
    # aborts under `set -e`. See read_container_file in docker-compose.sh.
    PRE_STOP_CONTENT=$(read_container_file "mockserver" "/config/persistedExpectations.json" || true)
    printMessage "Pre-stop mtime: ${PRE_STOP_MTIME}"
    printMessage "Pre-stop content: ${PRE_STOP_CONTENT}"

    # Stop the container gracefully (SIGTERM with default 10s grace period).
    # Guard the command substitution and the stop so a transient hiccup during the
    # shutdown window records a FAIL and falls through to logTestResult, rather than
    # aborting the script under `set -e` before a result is recorded.
    CONTAINER_ID=$(docker-compose -p "${TEST_CASE}" ps -q mockserver || true)
    printMessage "Stopping container ${CONTAINER_ID} gracefully..."
    docker stop "${CONTAINER_ID}" || TEST_EXIT_CODE=1
    printMessage "Container stopped"

    # Verify the persistence file still exists and has content after container exit
    if [[ ! -s "${SCRIPT_DIR}/config/persistedExpectations.json" ]]; then
      printFailureMessage "Expectations file disappeared or became empty after container stop"
      TEST_EXIT_CODE=1
    fi
  fi

  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    # GNU-first for the same reason as the pre-stop read above (see comment there).
    POST_STOP_MTIME=$(stat -c "%Y" "${SCRIPT_DIR}/config/persistedExpectations.json" 2>/dev/null \
                      || stat -f "%m" "${SCRIPT_DIR}/config/persistedExpectations.json" 2>/dev/null \
                      || true)
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
    # Require purely-numeric mtimes before the `-lt` arithmetic below: `[[ -lt ]]` evaluates
    # its operands as arithmetic expressions, so a non-numeric value (empty, or a stray stat
    # blob) is treated as a variable name and aborts the whole script under `set -u`
    # ("<word>: unbound variable") before a result is recorded. An empty or non-numeric read
    # is itself a genuine failure of this assertion.
    if [[ ! "${PRE_STOP_MTIME:-}" =~ ^[0-9]+$ || ! "${POST_STOP_MTIME:-}" =~ ^[0-9]+$ ]]; then
      printFailureMessage "Could not read a numeric mtime via stat (pre=\"${PRE_STOP_MTIME:-}\" post=\"${POST_STOP_MTIME:-}\")"
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

    # `|| true` for the same reason as the pre-shutdown read above.
    RESPONSE_BODY=$(docker-exec-client "curl -v -s -X PUT 'http://mockserver:1080/graceful/path'" || true)
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
