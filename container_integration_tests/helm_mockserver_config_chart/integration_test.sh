#!/usr/bin/env bash
# shellcheck disable=SC2155

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
TEST_CASE="${TEST_CASE:-$(basename "${SCRIPT_DIR}")}"
source "${SCRIPT_DIR}/../helm-deploy.sh"
source "${SCRIPT_DIR}/../logging.sh"

CLUSTER_NAME="mockserver"
KUBE_CONTEXT="k3d-${CLUSTER_NAME}"
CONFIG_CHART_DIR="${SCRIPT_DIR}/../../helm/mockserver-config"
MOCKSERVER_CHART_DIR="${SCRIPT_DIR}/../../helm/mockserver"
NAMESPACE="mockserver-cfg"
PORT="1082"

printMessage "Start: \"${SCRIPT_DIR/\//}\""

function deploy_config_chart() {
  printMessage "Deploying mockserver-config chart with custom values"
  runCommand "helm --kube-context ${KUBE_CONTEXT} upgrade --install --namespace ${NAMESPACE} --create-namespace --debug --wait ${NAMESPACE}-config ${CONFIG_CHART_DIR}"
}

function deploy_mockserver_chart() {
  printMessage "Deploying mockserver chart referencing mockserver-config ConfigMap"
  # app.mountConfigMap=true tells the deployment to set MOCKSERVER_PROPERTY_FILE
  # pointing at the ConfigMap created by the mockserver-config chart.
  # app.config.enabled=false (default) means mockserver chart does NOT create
  # its own ConfigMap; it relies on the one from mockserver-config.
  # No --version: installing from a local chart directory, so Helm ignores the
  # registry version filter. Avoid hardcoding a stale version string.
  runCommand "helm --kube-context ${KUBE_CONTEXT} upgrade --install --namespace ${NAMESPACE} --create-namespace --set image.repositoryNameAndTag=mockserver/mockserver:integration_testing --set app.mountConfigMap=true --debug --wait ${NAMESPACE} ${MOCKSERVER_CHART_DIR}"
  kill-port-forward "${NAMESPACE}" "${PORT}" "1080"
  runCommand "kubectl --context ${KUBE_CONTEXT} --namespace ${NAMESPACE} port-forward svc/${NAMESPACE} ${PORT}:1080 &"
  export MOCKSERVER_HOST=127.0.0.1:${PORT}
  sleep 3
}

function tear_down_all() {
  runCommand "helm --kube-context ${KUBE_CONTEXT} --namespace ${NAMESPACE} delete ${NAMESPACE} || true"
  runCommand "helm --kube-context ${KUBE_CONTEXT} --namespace ${NAMESPACE} delete ${NAMESPACE}-config || true"
  kill-port-forward "${NAMESPACE}" "${PORT}" "1080"
}

function integration_test() {
  trap tear_down_all EXIT
  deploy_config_chart
  deploy_mockserver_chart

  TEST_EXIT_CODE=0
  sleep 3

  # Verify MockServer status is healthy. /mockserver/status accepts PUT (not GET) -
  # other tests in this suite use the same -X PUT convention.
  STATUS_CODE=$(runCommand "curl -v -s -o /dev/null -w '%{http_code}' -X PUT 'http://${MOCKSERVER_HOST}/mockserver/status'") || TEST_EXIT_CODE=1
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    if [[ "${STATUS_CODE}" != "200" ]]; then
      printFailureMessage "MockServer status endpoint returned unexpected HTTP status: \"${STATUS_CODE}\" (expected 200)"
      TEST_EXIT_CODE=1
    fi
  fi

  # The mockserver-config chart's default initializerJson.json contains expectations
  # for /firstExampleExpectation and /secondExampleExpectation — verify they are loaded
  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    RESPONSE_BODY=$(runCommand "curl -v -s -X PUT 'http://${MOCKSERVER_HOST}/firstExampleExpectation'") || TEST_EXIT_CODE=1
    if [[ "${RESPONSE_BODY}" != "some response" ]]; then
      printFailureMessage "Failed to retrieve response for /firstExampleExpectation from mockserver-config chart, found: \"${RESPONSE_BODY}\""
      TEST_EXIT_CODE=1
    fi
  fi

  if [[ "${TEST_EXIT_CODE}" == "0" ]]; then
    RESPONSE_BODY=$(runCommand "curl -v -s -X PUT 'http://${MOCKSERVER_HOST}/secondExampleExpectation'") || TEST_EXIT_CODE=1
    if [[ "${RESPONSE_BODY}" != "some response" ]]; then
      printFailureMessage "Failed to retrieve response for /secondExampleExpectation from mockserver-config chart, found: \"${RESPONSE_BODY}\""
      TEST_EXIT_CODE=1
    fi
  fi

  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  tear_down_all
  return ${TEST_EXIT_CODE}
}

integration_test
