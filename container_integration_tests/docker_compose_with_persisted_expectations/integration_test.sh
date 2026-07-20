#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/../docker-compose.sh"
source "${SCRIPT_DIR}/../logging.sh"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

function integration_test() {
  runCommand "rm -rf ${SCRIPT_DIR}/config"
  runCommand "mkdir -p ${SCRIPT_DIR}/config && chmod 777 ${SCRIPT_DIR}/config"
  start-up
  TEST_EXIT_CODE=0
  wait_ready "mockserver" || { TEST_EXIT_CODE=1; logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"; return ${TEST_EXIT_CODE}; }
  docker-exec-client "curl -v -s -X PUT 'http://mockserver:1080/mockserver/expectation' -d \\\"{
                        'httpRequest' : {
                          'path' : '/some/path'
                        },
                        'httpResponse' : {
                          'body' : 'some_response_body'
                        }
                      }\\\"" || TEST_EXIT_CODE=1
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    RESPONSE_BODY=$(docker-exec-client "curl -v -s -X PUT 'http://mockserver:1080/some/path'")

    if [[ "${RESPONSE_BODY}" != "some_response_body" ]]; then
      printFailureMessage "Failed to retrieve response body for expectation matched by path, found: \"${RESPONSE_BODY}\""
      TEST_EXIT_CODE=1
    fi

    if [[ ! -s "${SCRIPT_DIR}/config/persistedExpectations.json" ]]; then
      printFailureMessage "Expectations were not persisted to: \"${SCRIPT_DIR}/config/persistedExpectations.json\""
      TEST_EXIT_CODE=1
    else
      printMessage "Expectations were persisted to: \"${SCRIPT_DIR}/config/persistedExpectations.json\":"
      # Read the persisted file from INSIDE the container via docker cp, not from the host
      # bind-mount: MockServer writes it as the container's non-root uid, and on Linux CI that
      # file is not readable by the agent user, so a host `cat` aborts this test under `set -e`
      # before it can record a result. (Docker Desktop for macOS hides this by remapping the
      # ownership to the caller.) See read_container_file in docker-compose.sh.
      read_container_file "mockserver" "/config/persistedExpectations.json" || true
      echo
    fi
  fi
  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
#  tear-down
  return ${TEST_EXIT_CODE}
}

integration_test
