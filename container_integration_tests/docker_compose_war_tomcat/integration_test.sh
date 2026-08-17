#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/../docker-compose.sh"
source "${SCRIPT_DIR}/../logging.sh"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

# The WAR is built by the Maven build and must be copied into the test
# directory before docker-compose build can package it into the Tomcat image.
# It reaches this test from mockserver/mockserver-war/target/ — produced by the
# reactor `package` locally, and downloaded as a Buildkite artifact in CI (the
# `:maven: build` step uploads it; container-tests-run.sh downloads it). A
# missing WAR is a FAILURE, not a skip: WAR deployment is a demonstrated weak
# spot (the ROOT-context percent-decode regression 66b5d51d2 shipped and broke
# builds), so a silent green here would re-open exactly the hole this case exists
# to close.
function prepare_war() {
  local war
  war=$(ls "${SCRIPT_DIR}"/../../mockserver/mockserver-war/target/mockserver-war-*.war 2>/dev/null | head -1)
  if [[ -z "${war}" ]]; then
    printFailureMessage "WAR artifact not found under mockserver/mockserver-war/target/ — expected the Maven reactor to have built it (local dev) or the CI step to have downloaded it as a Buildkite artifact. Failing closed rather than skipping."
    return 1
  fi
  cp "${war}" "${SCRIPT_DIR}/mockserver-war.war"
}

function cleanup() {
  tear-down 2>/dev/null || true
  rm -f "${SCRIPT_DIR}/mockserver-war.war"
}

function integration_test() {
  trap cleanup EXIT

  if ! prepare_war; then
    # WAR absent (or un-copyable): fail closed. This case must never pass by
    # skipping — see prepare_war's header for why.
    logTestResult "1" "${TEST_CASE}"
    return 1
  fi

  # docker-compose.yml lives in the test directory (not an overlay);
  # override compose-files lookup by setting the project directory.
  local files="-f ${SCRIPT_DIR}/docker-compose.yml"
  export OVERRIDE_DIR="${SCRIPT_DIR}"
  runCommand "docker-compose ${files} -p ${TEST_CASE} up --build -d"

  TEST_EXIT_CODE=0

  wait_ready "mockserver" "8080" || { TEST_EXIT_CODE=1; logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"; return ${TEST_EXIT_CODE}; }

  # Seed an expectation via the MockServer API
  docker-exec-client "curl -v -s -X PUT 'http://mockserver:8080/mockserver/expectation' -d \\\"{
                        'httpRequest' : {
                          'path' : '/some/path'
                        },
                        'httpResponse' : {
                          'body' : 'some_response_body'
                        }
                      }\\\"" || TEST_EXIT_CODE=1

  # Verify the expectation matches
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    RESPONSE_BODY=$(docker-exec-client "curl -v -s -X PUT 'http://mockserver:8080/some/path'")

    if [[ "${RESPONSE_BODY}" != "some_response_body" ]]; then
      printFailureMessage "Failed to retrieve response body for WAR-deployed expectation, found: \"${RESPONSE_BODY}\""
      TEST_EXIT_CODE=1
    fi
  fi

  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  # cleanup handled by EXIT trap
  return ${TEST_EXIT_CODE}
}

integration_test
