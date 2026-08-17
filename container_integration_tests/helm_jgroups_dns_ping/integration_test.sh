#!/usr/bin/env bash
# shellcheck disable=SC2155
#
# JGroups DNS_PING discovery end-to-end test.
#
# Proves that two MockServer pods form ONE Infinispan/JGroups cluster by
# discovering each other through the headless Service's DNS A records
# (dns.DNS_PING), and that state then converges across them. This exercises the
# Kubernetes discovery path itself — which JGroupsKubernetesStackTest (XML parse
# only) and ClusteredTwoNodeTest (loopback MPING) never run.
#
# Assertions (all hard gates unless noted):
#   1. The headless Service `<release>-headless` exists and is truly headless
#      (clusterIP: None) — the DNS_PING discovery target.
#   2. The pods' JGROUPS_DNS_QUERY env points at that headless Service FQDN —
#      i.e. DNS_PING is actually wired to the headless Service.
#   3. The headless Service resolves to >= 2 pod IPs (its Endpoints / DNS A
#      records) — the discovery source genuinely lists both peers. An in-cluster
#      nslookup is attempted as extra confirmation (best-effort).
#   4. A JGroups cluster VIEW of size >= 2 forms (ISPN000094 "(N)") — the anti
#      "two clusters of one" guard: DNS_PING discovered both peers and merged
#      them into a SINGLE cluster.
#   5. State converges: an expectation created on pod A matches on pod B.
#
# Blocking. Requires the -clustered image; the harness SKIPs this case when the
# image is absent (e.g. the CI helm step with no JDK) and imports it
# deterministically before invoking otherwise.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
TEST_CASE="${TEST_CASE:-helm_jgroups_dns_ping}"
source "${SCRIPT_DIR}/../helm-deploy.sh"
source "${SCRIPT_DIR}/../logging.sh"

RELEASE_NAME="ms-dns"
NAMESPACE="ms-dns"
REPLICAS=2
KUBE_CONTEXT="k3d-mockserver"
HEADLESS_SVC="${RELEASE_NAME}-headless"
HEADLESS_FQDN="${HEADLESS_SVC}.${NAMESPACE}.svc.cluster.local"
CLUSTERED_IMAGE="${MOCKSERVER_CLUSTERED_IMAGE:-mockserver/mockserver:integration_testing_clustered}"

printMessage "Start: \"${TEST_CASE}\""

function cleanup() {
  helm --kube-context "${KUBE_CONTEXT}" -n "${NAMESPACE}" delete "${RELEASE_NAME}" 2>/dev/null || true
  kubectl --context "${KUBE_CONTEXT}" delete namespace "${NAMESPACE}" --wait=false 2>/dev/null || true
}

function get_pod_names() {
  kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get pods \
    -l "app=mockserver,release=${RELEASE_NAME}" \
    -o jsonpath='{.items[*].metadata.name}'
}

# Wait for the target namespace to be fully ABSENT before deploying — a prior
# run or a retry may have left it Terminating, which fails `helm install`.
function ensure_namespace_absent() {
  local ns="$1" attempts=40
  kubectl --context "${KUBE_CONTEXT}" delete namespace "${ns}" --wait=false 2>/dev/null || true
  for _ in $(seq 1 "${attempts}"); do
    kubectl --context "${KUBE_CONTEXT}" get namespace "${ns}" >/dev/null 2>&1 || return 0
    sleep 3
  done
  printFailureMessage "namespace ${ns} still present after waiting — cannot deploy cleanly"
  return 1
}

function wait_for_pods_ready() {
  local attempts=60 ready
  for _ in $(seq 1 "${attempts}"); do
    ready=$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get pods \
      -l "app=mockserver,release=${RELEASE_NAME}" \
      -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null \
      | grep -c "True" || true)
    if [[ "${ready}" -ge "${REPLICAS}" ]]; then
      printMessage "All ${REPLICAS} pods Ready"
      return 0
    fi
    sleep 3
  done
  printFailureMessage "Timed out waiting for ${REPLICAS} ready pods (got ${ready:-0})"
  return 1
}

# Poll pod logs for the Infinispan cluster-view line and require size >= REPLICAS.
# A size of 1 on every pod means DNS_PING did NOT discover peers (two clusters of
# one) — which must FAIL, never pass.
function wait_for_two_node_view() {
  local attempts=60 pods pod view_line view_size
  for _ in $(seq 1 "${attempts}"); do
    pods=$(get_pod_names)
    for pod in ${pods}; do
      view_line=$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" logs "${pod}" 2>/dev/null \
        | grep 'ISPN000094.*cluster view' | tail -1 || echo "")
      if [[ -n "${view_line}" ]]; then
        view_size=$(echo "${view_line}" | grep -o '([0-9]*)' | tail -1 | tr -d '()' || echo "0")
        if [[ -n "${view_size}" && "${view_size}" -ge "${REPLICAS}" ]]; then
          printMessage "JGroups DNS_PING formed a ${view_size}-node view (pod ${pod})"
          return 0
        fi
      fi
    done
    sleep 3
  done
  printFailureMessage "JGroups cluster did not reach a ${REPLICAS}-node view — DNS_PING discovery failed (pods likely formed clusters of one)"
  local p
  for p in $(get_pod_names); do
    printMessage "Logs from ${p}:"
    kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" logs "${p}" 2>/dev/null | grep -iE 'ISPN000094|DNS_PING|dns_query|cluster view' | tail -20 || true
  done
  return 1
}

# PUT an expectation on a specific pod via a short-lived port-forward.
function create_expectation_on_pod() {
  local pod="$1" path="$2" body="$3"
  kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" port-forward "pod/${pod}" 19080:1080 &>/dev/null &
  local pf_pid=$!
  sleep 2
  curl -sf -X PUT "http://127.0.0.1:19080/mockserver/expectation" \
    -H "Content-Type: application/json" \
    -d "{\"httpRequest\":{\"path\":\"${path}\"},\"httpResponse\":{\"body\":\"${body}\"}}" &>/dev/null || true
  kill "${pf_pid}" 2>/dev/null || true
  wait "${pf_pid}" 2>/dev/null || true
  sleep 1
}

# GET a path on a specific pod; echo "status:body".
function hit_pod() {
  local pod="$1" path="$2" port="${3:-19081}"
  kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" port-forward "pod/${pod}" "${port}":1080 &>/dev/null &
  local pf_pid=$!
  sleep 2
  local tmpfile status body
  tmpfile=$(mktemp)
  status=$(curl -s -o "${tmpfile}" -w '%{http_code}' "http://127.0.0.1:${port}${path}" 2>/dev/null || echo "000")
  body=$(cat "${tmpfile}" 2>/dev/null || echo "")
  rm -f "${tmpfile}"
  kill "${pf_pid}" 2>/dev/null || true
  wait "${pf_pid}" 2>/dev/null || true
  sleep 1
  echo "${status}:${body}"
}

function integration_test() {
  trap cleanup EXIT
  local TEST_EXIT_CODE=0

  printMessage "Deploying ${REPLICAS} clustered replicas (DNS_PING via headless Service)"
  ensure_namespace_absent "${NAMESPACE}" || { logTestResult "1" "${TEST_CASE}"; return 1; }
  # Pin releasenameOverride so the release.name helper does NOT append
  # "-mockserver": the headless Service is then exactly `${RELEASE_NAME}-headless`
  # and JGROUPS_DNS_QUERY resolves to `${HEADLESS_FQDN}` as asserted below.
  helm --kube-context "${KUBE_CONTEXT}" upgrade --install \
    --namespace "${NAMESPACE}" --create-namespace \
    --set releasenameOverride="${RELEASE_NAME}" \
    --set replicaCount="${REPLICAS}" \
    --set clustering.enabled=true \
    --set image.repositoryNameAndTag="${CLUSTERED_IMAGE}" \
    --set image.pullPolicy=Never \
    --set service.type=ClusterIP \
    --debug --wait --timeout 180s \
    "${RELEASE_NAME}" "${SCRIPT_DIR}/../../helm/mockserver" || {
    printFailureMessage "Helm install (clustering.enabled) failed"
    TEST_EXIT_CODE=1
  }

  [[ "${TEST_EXIT_CODE}" -eq 0 ]] && { wait_for_pods_ready || TEST_EXIT_CODE=1; }

  # --- Assertion 1: headless Service exists and is truly headless ---
  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    local cluster_ip
    cluster_ip=$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get svc "${HEADLESS_SVC}" \
      -o jsonpath='{.spec.clusterIP}' 2>/dev/null || echo "MISSING")
    if [[ "${cluster_ip}" != "None" ]]; then
      printFailureMessage "headless Service ${HEADLESS_SVC} clusterIP='${cluster_ip}' (expected 'None')"
      TEST_EXIT_CODE=1
    else
      printPassMessage "Headless Service ${HEADLESS_SVC} present (clusterIP: None)"
    fi
  fi

  # --- Assertion 2: DNS_PING is wired to that headless Service FQDN ---
  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    local pod_a
    pod_a=$(get_pod_names | awk '{print $1}')
    local dns_query
    dns_query=$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get pod "${pod_a}" \
      -o jsonpath='{.spec.containers[0].env[?(@.name=="JGROUPS_DNS_QUERY")].value}' 2>/dev/null || echo "")
    if [[ "${dns_query}" != "${HEADLESS_FQDN}" ]]; then
      printFailureMessage "JGROUPS_DNS_QUERY='${dns_query}' (expected '${HEADLESS_FQDN}') — DNS_PING not wired to headless Service"
      TEST_EXIT_CODE=1
    else
      printPassMessage "DNS_PING wired to headless Service: JGROUPS_DNS_QUERY=${dns_query}"
    fi
  fi

  # --- Assertion 3: headless Service resolves to >= REPLICAS pod IPs ---
  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    local ip_count=0 ips
    for _ in $(seq 1 20); do
      ips=$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" get endpoints "${HEADLESS_SVC}" \
        -o jsonpath='{range .subsets[*]}{range .addresses[*]}{.ip}{" "}{end}{end}' 2>/dev/null || echo "")
      ip_count=$(echo "${ips}" | wc -w | tr -d ' ')
      [[ "${ip_count}" -ge "${REPLICAS}" ]] && break
      sleep 3
    done
    if [[ "${ip_count}" -lt "${REPLICAS}" ]]; then
      printFailureMessage "headless Service Endpoints resolve to only ${ip_count} pod IP(s) (expected >= ${REPLICAS}): '${ips}'"
      TEST_EXIT_CODE=1
    else
      printPassMessage "Headless Service DNS source lists ${ip_count} pod IPs (A records): ${ips}"
      # Best-effort: confirm in-cluster DNS actually resolves the FQDN to >=2 A
      # records. Soft — a busybox pull failure must not red an otherwise-proven
      # DNS_PING path (the Endpoints check above is the hard gate).
      local nslookup_out
      nslookup_out=$(kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" run dns-probe-$$ \
        --image=busybox:1.36 --restart=Never --rm -i --command --timeout=60s -- \
        nslookup "${HEADLESS_FQDN}" 2>/dev/null || echo "")
      local a_records
      a_records=$(echo "${nslookup_out}" | grep -c 'Address: ' || true)
      if [[ "${a_records}" -ge "${REPLICAS}" ]]; then
        printMessage "In-cluster nslookup confirmed ${a_records} A records for ${HEADLESS_FQDN}"
      else
        printMessage "In-cluster nslookup inconclusive (soft) — Endpoints check is authoritative"
      fi
    fi
  fi

  # --- Assertion 4: a >= REPLICAS-node JGroups view forms (DNS_PING worked) ---
  [[ "${TEST_EXIT_CODE}" -eq 0 ]] && { wait_for_two_node_view || TEST_EXIT_CODE=1; }

  # --- Assertion 5: state converges across the two pods ---
  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    local pods pod_a pod_b
    pods=$(get_pod_names)
    pod_a=$(echo "${pods}" | awk '{print $1}')
    pod_b=$(echo "${pods}" | awk '{print $2}')
    printMessage "State convergence: create on ${pod_a}, match on ${pod_b}"
    create_expectation_on_pod "${pod_a}" "/dns-ping-test" "converged-via-dns-ping"
    sleep 3
    local response body
    response=$(hit_pod "${pod_b}" "/dns-ping-test" 19083)
    body="${response#*:}"
    if [[ "${body}" == "converged-via-dns-ping" ]]; then
      printPassMessage "State converged across DNS_PING cluster (pod B returned '${body}')"
    else
      printFailureMessage "State did NOT converge: pod B returned '${response}' (expected body 'converged-via-dns-ping')"
      TEST_EXIT_CODE=1
    fi
  fi

  # Diagnostics on failure
  if [[ "${TEST_EXIT_CODE}" -ne 0 ]]; then
    local p
    for p in $(get_pod_names 2>/dev/null || echo ""); do
      printMessage "Diagnostic logs from ${p}:"
      kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" logs "${p}" 2>/dev/null | tail -30 || true
    done
  fi

  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  return "${TEST_EXIT_CODE}"
}

integration_test
