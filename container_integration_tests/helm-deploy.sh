#!/usr/bin/env bash

set -euo pipefail

CLUSTER_NAME="mockserver"
KUBE_CONTEXT="k3d-${CLUSTER_NAME}"

function start-up-k8s() {
  local SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
  if [[ "${REBUILD_CLUSTER:-false}" == "true" ]]; then
    runCommand "k3d cluster delete ${CLUSTER_NAME}"
  fi

  if k3d cluster list 2>&1 | grep -qw "${CLUSTER_NAME}"; then
    printMessage "Found existing cluster"
  else
    # -------------------------------------------------------------------------
    # Local-only TLS CA injection into the in-node containerd trust store
    # (opt-in via K3D_LOCAL_CA_BUNDLE; unset in CI, so the created command is
    # byte-identical there). Mirrors LOCAL_DOCKER_CA_BUNDLE in
    # .buildkite/scripts/run-in-docker.sh.
    #
    # Behind a corporate TLS-inspection proxy the host Docker daemon may already
    # trust the corporate root (so `k3d cluster create` pulls the k3s node image
    # fine), but containerd INSIDE the k3s node has its OWN trust store at
    # /etc/ssl/certs/ca-certificates.crt (public roots only). Without the
    # corporate root there it cannot pull even the rancher/mirrored-pause sandbox
    # image, so every pod fails at sandbox creation with
    # "x509: certificate signed by unknown authority". Overmounting the COMBINED
    # bundle (public roots + corporate root) as containerd's entire trust store
    # is the fix. Warn (do not fail) if the variable is set but the file is
    # missing, matching run-in-docker.sh.
    local ca_volume=""
    if [[ -n "${K3D_LOCAL_CA_BUNDLE:-}" ]]; then
      if [[ -f "${K3D_LOCAL_CA_BUNDLE}" ]]; then
        ca_volume=" --volume ${K3D_LOCAL_CA_BUNDLE}:/etc/ssl/certs/ca-certificates.crt@server:*"
      else
        printMessage "WARNING: K3D_LOCAL_CA_BUNDLE='${K3D_LOCAL_CA_BUNDLE}' not found -- skipping in-node CA injection"
      fi
    fi
    runCommand "k3d cluster create --config ${SCRIPT_DIR}/k3d-config.yaml${ca_volume}"
  fi

  runCommand "k3d image import --cluster ${CLUSTER_NAME} mockserver/mockserver:integration_testing"
}

function tear-down-k8s() {
  if [[ "${DELETE_CLUSTER:-false}" == "true" ]]; then
    runCommand "k3d cluster delete ${CLUSTER_NAME}"
  fi
}

# Matches ONLY the port-forward this script starts, for this namespace and port.
# Deliberately specific: the previous `ps -ef | grep port-forward | grep <port>`
# matched its own grep processes in the same pipeline (so `xargs kill` reported
# "No such process"), and would happily kill an unrelated forward — including one
# belonging to a concurrent worktree — that merely mentioned the same port.
# (namespace, localPort, remotePort). remotePort defaults to localPort, which is
# the common case; helm_mockserver_config_chart forwards 1082:1080, so a pattern
# assuming they are equal silently matches nothing and kills nothing.
function port-forward-pattern() {
  local namespace="${1}"
  local local_port="${2}"
  local remote_port="${3:-${2}}"
  echo "kubectl .*--namespace ${namespace} port-forward svc/${namespace} ${local_port}:${remote_port}"
}

# Deterministic per-(namespace,port) so tear-down can find the right one:
# helm_remote_host_and_port runs two concurrent forwards (1090 and 1080).
function port-forward-log() {
  echo "${TMPDIR:-/tmp}/mockserver-port-forward-${1}-${2}.log"
}

function kill-port-forward() {
  # pkill excludes itself, and -f matches the full command line. No-op (exit 1)
  # when nothing matches, which is the normal case.
  pkill -f "$(port-forward-pattern "${1}" "${2}" "${3:-${2}}")" || true
}

function start-up() {
  local SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
  local namespace="${2:-mockserver}"
  local port="${3:-1080}"
  runCommand "helm --kube-context ${KUBE_CONTEXT} upgrade --install --namespace ${namespace} --create-namespace ${1:-} --debug --wait --version 5.15.0 ${namespace} ${SCRIPT_DIR}/../helm/mockserver"
  # Kill any stale port-forward holding this local port, then WAIT for the port to
  # actually free before binding a new one. A leaked background forward from a prior
  # test is what causes the flaky "address already in use" on bind.
  kill-port-forward "${namespace}" "${port}"
  runCommand "for _ in \$(seq 1 15); do (exec 3<>/dev/tcp/127.0.0.1/${port}) 2>/dev/null && { exec 3>&-; sleep 1; } || break; done"

  # Keep the forward's own stderr. It is the only place the real cause is stated
  # ("Unable to listen on port ...: address already in use"); discarding it to
  # /dev/null is what reduced a hard bind failure to an unexplained curl error.
  local forward_log
  forward_log="$(port-forward-log "${namespace}" "${port}")"
  : >"${forward_log}"
  runCommand "kubectl --context ${KUBE_CONTEXT} --namespace ${namespace} port-forward svc/${namespace} ${port}:${port} >\"${forward_log}\" 2>&1 &"
  export MOCKSERVER_HOST=127.0.0.1:${port}

  # Poll until the forward actually serves rather than a fixed sleep (flaky under
  # load). The loop MUST have an exhaustion branch: without one it fell through
  # silently after 30s and the caller proceeded to curl a port nothing was
  # serving, reporting a generic failure that named neither the forward nor the
  # reason.
  local ready=false
  for _ in $(seq 1 30); do
    if curl -sf -o /dev/null -X PUT "http://127.0.0.1:${port}/mockserver/status"; then
      ready=true
      break
    fi
    sleep 1
  done
  if [[ "${ready}" != "true" ]]; then
    printFailureMessage "port-forward to svc/${namespace} on 127.0.0.1:${port} never became ready after 30s"
    kill-port-forward "${namespace}" "${port}"

    # Pick the hint from what the forward ACTUALLY said, and print the captured output
    # LAST so the final thing a reader sees is evidence rather than a guess.
    #
    # Previously the port-collision hint was printed unconditionally and last. For a
    # "services not found" failure that is a red herring: nothing was listening on the
    # port at all, and a reader who stops at the last line goes hunting for a collision
    # that does not exist. That is exactly what happened with helm_remote_host_and_port.
    if [[ -s "${forward_log}" ]] && grep -q "NotFound" "${forward_log}"; then
      printFailureMessage "The Service does not exist under that name — this is NOT a port collision."
      printFailureMessage "  The chart names its Service from the 'release.name' template, which appends the"
      printFailureMessage "  chart name unless the release name already contains it: release 'foo' creates"
      printFailureMessage "  Service 'foo-mockserver', while release 'mockserver-foo' creates 'mockserver-foo'."
      printFailureMessage "  Pin it with --set releasenameOverride=<name>, or check: kubectl get svc -n ${namespace}"
    else
      printFailureMessage "Anything already listening on 127.0.0.1:${port} (e.g. a k3d 'ports:' publish in k3d-config.yaml) will prevent the forward from binding."
    fi

    printFailureMessage "kubectl port-forward output was:"
    if [[ -s "${forward_log}" ]]; then
      cat "${forward_log}" >&2
    else
      printFailureMessage "  (empty — the forward produced no output; it may have been killed)"
    fi
    return 1
  fi
}

function run-helm-test() {
  printMessage "Running helm test for release: ${1:-mockserver}"
  runCommand "helm --kube-context ${KUBE_CONTEXT} --namespace ${1:-mockserver} test ${1:-mockserver} --timeout 60s"
}

function tear-down() {
  runCommand "helm --kube-context ${KUBE_CONTEXT} --namespace ${1:-mockserver} delete ${1:-mockserver}"
  kill-port-forward "${1:-mockserver}" "${2:-1080}"
  rm -f "$(port-forward-log "${1:-mockserver}" "${2:-1080}")" 2>/dev/null || true
}

function container-logs() {
  printMessage "${1:-mockserver} logs"
  runCommand "kubectl --context ${KUBE_CONTEXT} --namespace ${1:-mockserver} logs $(kubectl --context ${KUBE_CONTEXT} --namespace ${1:-mockserver} get po -l app=mockserver,release=${1:-mockserver} -o=jsonpath='{.items[0].metadata.name}')"
}
