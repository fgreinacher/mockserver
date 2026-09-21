#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/../docker-compose.sh"
source "${SCRIPT_DIR}/../logging.sh"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

function build_initialiser_jar() {
  printMessage "Building initialiser JAR for /libs classpath test"

  # Force-remove any leftover named containers from a prior failed run so the
  # second `docker create --name` below doesn't fail with "name already in use".
  docker rm -f libs_extract libs_jar_extract >/dev/null 2>&1 || true

  # Find the mockserver fat JAR (needed for compilation classpath)
  local MOCKSERVER_JAR
  MOCKSERVER_JAR=$(ls "${SCRIPT_DIR}"/../../mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar 2>/dev/null | head -1)
  if [[ -z "${MOCKSERVER_JAR}" ]]; then
    # Fall back: extract JAR from the integration_testing image
    printMessage "Fat JAR not found locally; extracting from Docker image"
    # The integration_testing image is built from docker/Dockerfile, which since the incremental-pull
    # work ships the classes as TWO jars - /mockserver.jar (org/mockserver/**) and
    # /mockserver-deps.jar - rather than one fat jar. Extract both and MERGE them back, because
    # everything downstream (the cp below, Dockerfile.initialiser's compile classpath) expects a
    # single fat jar at a fixed name. Copying only /mockserver.jar would produce a jar with
    # MockServer's classes and none of its dependencies, which compiles until it doesn't.
    local EXTRACT_DIR="${SCRIPT_DIR}/.jar-extract"
    rm -rf "${EXTRACT_DIR}"; mkdir -p "${EXTRACT_DIR}/merged"
    MOCKSERVER_JAR="${EXTRACT_DIR}/mockserver-netty-jar-with-dependencies.jar"
    docker create --name libs_extract mockserver/mockserver:integration_testing true >/dev/null 2>&1
    docker cp libs_extract:/mockserver.jar "${EXTRACT_DIR}/own.jar"
    docker cp libs_extract:/mockserver-deps.jar "${EXTRACT_DIR}/deps.jar"
    docker rm -f libs_extract >/dev/null 2>&1
    # deps first so its META-INF/MANIFEST.MF lands in the merged jar (own.jar is manifest-less)
    ( cd "${EXTRACT_DIR}/merged" && unzip -qo ../deps.jar && unzip -qo ../own.jar && zip -qr "${MOCKSERVER_JAR}" . )
  fi

  # Copy fat JAR to build context for the Dockerfile
  cp "${MOCKSERVER_JAR}" "${SCRIPT_DIR}/mockserver-netty-jar-with-dependencies.jar"

  # Build the initialiser JAR using a Docker build
  docker build -t libs-test-initialiser:latest -f "${SCRIPT_DIR}/Dockerfile.initialiser" "${SCRIPT_DIR}"

  # Extract the built JAR from the image
  runCommand "mkdir -p ${SCRIPT_DIR}/libs"
  docker create --name libs_jar_extract libs-test-initialiser:latest true >/dev/null 2>&1
  docker cp libs_jar_extract:/libs-test-initialiser.jar "${SCRIPT_DIR}/libs/libs-test-initialiser.jar"
  docker rm -f libs_jar_extract >/dev/null 2>&1

  # Clean up build artifact
  rm -f "${SCRIPT_DIR}/mockserver-netty-jar-with-dependencies.jar"
}

function cleanup() {
  tear-down 2>/dev/null || true
  rm -rf "${SCRIPT_DIR}/libs"
  rm -f "${SCRIPT_DIR}/mockserver-netty-jar-with-dependencies.jar"
  docker rm -f libs_extract libs_jar_extract >/dev/null 2>&1 || true
}

function integration_test() {
  trap cleanup EXIT
  build_initialiser_jar

  start-up
  TEST_EXIT_CODE=0
  wait_ready "mockserver" || { TEST_EXIT_CODE=1; logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"; return ${TEST_EXIT_CODE}; }

  # Verify the initialiser ran by hitting the expectation it registered
  RESPONSE_BODY=$(docker-exec-client "curl -v -s -X PUT 'http://mockserver:1080/libs-test-path'")

  if [[ "${RESPONSE_BODY}" != "libs_classpath_response" ]]; then
    printFailureMessage "Failed to retrieve response body from libs classpath initialiser, found: \"${RESPONSE_BODY}\""
    TEST_EXIT_CODE=1
  fi

  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  # tear-down + libs/jar cleanup handled by EXIT trap above.
  return ${TEST_EXIT_CODE}
}

integration_test
