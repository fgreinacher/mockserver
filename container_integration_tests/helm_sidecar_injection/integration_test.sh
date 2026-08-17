#!/usr/bin/env bash
# shellcheck disable=SC2155
#
# Live MutatingAdmissionWebhook sidecar-injection end-to-end test.
#
# Deploys the chart with webhook.enabled=true (self-signed TLS bootstrap Jobs +
# webhook handler Deployment + MutatingWebhookConfiguration), then drives a REAL
# pod CREATE through the admission path and proves the webhook MUTATED the pod
# spec. It asserts the resulting pod SPEC (not readiness, not merely that the MWC
# object exists) carries:
#   - the injected `mockserver-sidecar` container
#   - the injected `mockserver-iptables-init` init container
#   - the `mockserver.org/injected: "true"` idempotency annotation
#
# A NEGATIVE CONTROL pod (same labelled namespace, but WITHOUT the opt-in
# annotation) must NOT receive a sidecar — so a webhook that injects
# unconditionally, or a spec check that always "finds" the sidecar, fails the
# test. This is what makes a green run meaningful rather than self-satisfying.
#
# failurePolicy=Fail is set deliberately: if the webhook is unreachable or its
# caBundle is wrong, the annotated pod CREATE is REJECTED (loud), rather than
# silently admitted without a sidecar.
#
# Requires the webhook handler image; the harness only invokes this case when
# that image is present (SKIP otherwise) and passes it via MOCKSERVER_WEBHOOK_IMAGE.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
TEST_CASE="${TEST_CASE:-helm_sidecar_injection}"
source "${SCRIPT_DIR}/../helm-deploy.sh"
source "${SCRIPT_DIR}/../logging.sh"

RELEASE_NAME="ms-webhook"
NAMESPACE="ms-webhook"
TARGET_NS="ms-webhook-target"
KUBE_CONTEXT="k3d-mockserver"
# k3d cluster name (helm-deploy.sh sets CLUSTER_NAME; derive from the context otherwise).
CLUSTER_NAME="${CLUSTER_NAME:-${KUBE_CONTEXT#k3d-}}"

# Image refs — provided by the harness. Fall back to sensible local defaults so
# the case is runnable standalone during development.
WEBHOOK_IMAGE="${MOCKSERVER_WEBHOOK_IMAGE:-$(docker images mockserver/mockserver-webhook --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep -v ':<none>$' | head -1)}"
SIDECAR_IMAGE="${MOCKSERVER_SIDECAR_IMAGE:-mockserver/mockserver:integration_testing}"

printMessage "Start: \"${TEST_CASE}\" (webhook image: ${WEBHOOK_IMAGE:-<none>})"

function cleanup() {
  helm --kube-context "${KUBE_CONTEXT}" -n "${NAMESPACE}" delete "${RELEASE_NAME}" 2>/dev/null || true
  kubectl --context "${KUBE_CONTEXT}" delete namespace "${TARGET_NS}" --wait=false 2>/dev/null || true
  kubectl --context "${KUBE_CONTEXT}" delete namespace "${NAMESPACE}" --wait=false 2>/dev/null || true
  # The MutatingWebhookConfiguration is cluster-scoped; helm delete removes it,
  # but drop it defensively so a leftover cannot break unrelated pod CREATEs.
  kubectl --context "${KUBE_CONTEXT}" delete mutatingwebhookconfiguration \
    "${RELEASE_NAME}-sidecar-injector" 2>/dev/null || true
}

# Create a pod in the target namespace and return once the API server has
# persisted it (CREATE is when admission/injection happens). The pod need not
# become Ready — we inspect its SPEC, which is fixed at admission time.
function create_target_pod() {
  local name="$1"
  local inject="$2"   # "true" to add the opt-in annotation, "" to omit it
  local annotations=""
  if [[ "${inject}" == "true" ]]; then
    annotations=$'\n    mockserver.org/inject: "true"'
  fi
  cat <<YAML | kubectl --context "${KUBE_CONTEXT}" apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: ${name}
  namespace: ${TARGET_NS}
  labels:
    app: injection-target${annotations:+
  annotations:${annotations}}
spec:
  restartPolicy: Never
  containers:
    - name: app
      image: ${SIDECAR_IMAGE}
      imagePullPolicy: Never
YAML
}

# Wait for a namespace to be fully ABSENT before deploying — a prior run or a
# retry may have left it Terminating, which fails `helm install`.
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

# Echo the space-separated container names of a pod's SPEC.
function pod_container_names() {
  kubectl --context "${KUBE_CONTEXT}" -n "${TARGET_NS}" get pod "$1" \
    -o jsonpath='{.spec.containers[*].name}' 2>/dev/null || echo ""
}

function pod_init_container_names() {
  kubectl --context "${KUBE_CONTEXT}" -n "${TARGET_NS}" get pod "$1" \
    -o jsonpath='{.spec.initContainers[*].name}' 2>/dev/null || echo ""
}

function integration_test() {
  trap cleanup EXIT
  local TEST_EXIT_CODE=0

  if [[ -z "${WEBHOOK_IMAGE}" ]]; then
    printFailureMessage "no webhook image resolved — harness should have SKIPPED this case"
    logTestResult "1" "${TEST_CASE}"
    return 1
  fi

  local webhook_tag="${WEBHOOK_IMAGE##*:}"
  local webhook_repo="${WEBHOOK_IMAGE%:*}"

  # --- Deploy the chart with the admission webhook enabled ---
  printMessage "Deploying chart with webhook.enabled=true"
  ensure_namespace_absent "${NAMESPACE}" || { logTestResult "1" "${TEST_CASE}"; return 1; }
  ensure_namespace_absent "${TARGET_NS}" || { logTestResult "1" "${TEST_CASE}"; return 1; }

  # --- Prove the chart's DEFAULT webhook TLS bootstrap image is pullable, then
  # preload it. This case deploys with NO setupImage override, so the self-signed
  # TLS Jobs run the SHIPPED default. Rendering + pulling it here makes a
  # withdrawn/unpullable default fail LOUDLY in this test rather than only in a
  # user's cluster (the "green while the real default is broken" trap). ---
  local default_setup_image
  default_setup_image=$(helm template "${RELEASE_NAME}" "${SCRIPT_DIR}/../../helm/mockserver" \
    --set releasenameOverride="${RELEASE_NAME}" \
    --set webhook.enabled=true \
    --show-only templates/webhook-tls-selfsigned.yaml 2>/dev/null \
    | awk '/^[[:space:]]*image:[[:space:]]/{print $2; exit}')
  if [[ -z "${default_setup_image}" ]]; then
    printFailureMessage "could not render the chart's default webhook.tls.setupImage"
    logTestResult "1" "${TEST_CASE}"; return 1
  elif [[ "${default_setup_image}" == *"bitnami/kubectl"* ]]; then
    printFailureMessage "chart default setupImage is a withdrawn bitnami/kubectl tag: ${default_setup_image}"
    logTestResult "1" "${TEST_CASE}"; return 1
  elif ! docker pull "${default_setup_image}" >/dev/null 2>&1; then
    printFailureMessage "chart default webhook.tls.setupImage is NOT pullable: ${default_setup_image}"
    logTestResult "1" "${TEST_CASE}"; return 1
  fi
  printPassMessage "chart default webhook.tls.setupImage renders and is pullable: ${default_setup_image}"
  # Preload into the cluster so the in-cluster bootstrap Jobs cannot flake on
  # registry egress; soft — the Job can still pull it itself if import fails.
  k3d image import --cluster "${CLUSTER_NAME}" "${default_setup_image}" >/dev/null 2>&1 \
    || printMessage "k3d image import of ${default_setup_image} failed (soft) — Job will pull it in-cluster"
  # Pin releasenameOverride so the chart's release.name helper does NOT append
  # "-mockserver" (release 'ms-webhook' otherwise renders resources as
  # 'ms-webhook-mockserver-*'). With this, every ${RELEASE_NAME}-* name below
  # (the MWC, webhook Service, TLS secret/Jobs) is exactly what the chart emits.
  helm --kube-context "${KUBE_CONTEXT}" upgrade --install \
    --namespace "${NAMESPACE}" --create-namespace \
    --set releasenameOverride="${RELEASE_NAME}" \
    --set image.repositoryNameAndTag="${SIDECAR_IMAGE}" \
    --set webhook.enabled=true \
    --set webhook.image.repository="${webhook_repo}" \
    --set webhook.image.tag="${webhook_tag}" \
    --set webhook.image.pullPolicy=Never \
    --set webhook.failurePolicy=Fail \
    --set webhook.sidecar.image="${SIDECAR_IMAGE}" \
    --debug --wait --timeout 180s \
    "${RELEASE_NAME}" "${SCRIPT_DIR}/../../helm/mockserver" || {
    printFailureMessage "Helm install (webhook.enabled) failed"
    TEST_EXIT_CODE=1
  }

  # Confirm the caBundle was patched into the MWC by the post-install Job — an
  # empty caBundle means injection would be rejected (failurePolicy=Fail) and the
  # whole test would be meaningless. Poll: the patch is a post-install hook and
  # its update can land fractionally after `helm --wait` returns.
  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    local ca_bundle="" attempt
    for attempt in $(seq 1 20); do
      ca_bundle=$(kubectl --context "${KUBE_CONTEXT}" get mutatingwebhookconfiguration \
        "${RELEASE_NAME}-sidecar-injector" \
        -o jsonpath='{.webhooks[0].clientConfig.caBundle}' 2>/dev/null || echo "")
      [[ -n "${ca_bundle}" ]] && break
      sleep 3
    done
    if [[ -z "${ca_bundle}" ]]; then
      printFailureMessage "MWC caBundle still empty after polling — self-signed TLS patch Job did not patch it"
      kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" logs \
        -l job-name="${RELEASE_NAME}-webhook-tls-patch" --tail=20 2>/dev/null || true
      TEST_EXIT_CODE=1
    else
      printMessage "MWC caBundle present (${#ca_bundle} base64 chars)"
    fi
  fi

  # --- Prepare the labelled target namespace (MWC namespaceSelector matches it) ---
  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    kubectl --context "${KUBE_CONTEXT}" create namespace "${TARGET_NS}" 2>/dev/null || true
    kubectl --context "${KUBE_CONTEXT}" label namespace "${TARGET_NS}" \
      mockserver.org/sidecar-injection=enabled --overwrite
  fi

  # --- Positive case: annotated pod MUST be injected ---
  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    printMessage "Creating annotated pod (expect injection)"
    # Retry CREATE briefly: with failurePolicy=Fail a race on webhook endpoint
    # readiness would reject the pod; a genuine misconfig still fails all retries.
    local created=false attempt
    for attempt in $(seq 1 10); do
      if create_target_pod "injected" "true"; then created=true; break; fi
      printMessage "pod CREATE rejected (attempt ${attempt}) — webhook endpoint may still be warming; retrying"
      sleep 3
    done
    if [[ "${created}" != "true" ]]; then
      printFailureMessage "annotated pod CREATE was rejected on every attempt (webhook unreachable / caBundle wrong)"
      TEST_EXIT_CODE=1
    fi
  fi

  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    local containers init_containers injected_anno sidecar_image
    containers="$(pod_container_names injected)"
    init_containers="$(pod_init_container_names injected)"
    injected_anno=$(kubectl --context "${KUBE_CONTEXT}" -n "${TARGET_NS}" get pod injected \
      -o jsonpath='{.metadata.annotations.mockserver\.org/injected}' 2>/dev/null || echo "")
    sidecar_image=$(kubectl --context "${KUBE_CONTEXT}" -n "${TARGET_NS}" get pod injected \
      -o jsonpath='{.spec.containers[?(@.name=="mockserver-sidecar")].image}' 2>/dev/null || echo "")

    printMessage "injected pod containers: [${containers}], initContainers: [${init_containers}], injected-anno: '${injected_anno}', sidecar-image: '${sidecar_image}'"

    if [[ " ${containers} " != *" mockserver-sidecar "* ]]; then
      printFailureMessage "INJECTION FAILED: 'mockserver-sidecar' NOT present in pod spec containers [${containers}]"
      TEST_EXIT_CODE=1
    fi
    if [[ " ${init_containers} " != *" mockserver-iptables-init "* ]]; then
      printFailureMessage "INJECTION FAILED: 'mockserver-iptables-init' NOT present in initContainers [${init_containers}]"
      TEST_EXIT_CODE=1
    fi
    if [[ "${injected_anno}" != "true" ]]; then
      printFailureMessage "INJECTION FAILED: idempotency annotation mockserver.org/injected='${injected_anno}' (expected 'true')"
      TEST_EXIT_CODE=1
    fi
    if [[ "${sidecar_image}" != "${SIDECAR_IMAGE}" ]]; then
      printFailureMessage "INJECTION FAILED: sidecar image is '${sidecar_image}' (expected '${SIDECAR_IMAGE}')"
      TEST_EXIT_CODE=1
    fi
    if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
      printPassMessage "Sidecar injected into pod spec: mockserver-sidecar (${sidecar_image}) + mockserver-iptables-init init container"
    fi
  fi

  # --- Negative control: un-annotated pod MUST NOT be injected ---
  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    printMessage "Creating un-annotated pod (expect NO injection — discriminates a webhook that injects unconditionally)"
    create_target_pod "not-injected" "" || {
      printFailureMessage "un-annotated pod CREATE failed"
      TEST_EXIT_CODE=1
    }
  fi
  if [[ "${TEST_EXIT_CODE}" -eq 0 ]]; then
    local neg_containers neg_init neg_anno
    neg_containers="$(pod_container_names not-injected)"
    neg_init="$(pod_init_container_names not-injected)"
    neg_anno=$(kubectl --context "${KUBE_CONTEXT}" -n "${TARGET_NS}" get pod not-injected \
      -o jsonpath='{.metadata.annotations.mockserver\.org/injected}' 2>/dev/null || echo "")
    printMessage "un-annotated pod containers: [${neg_containers}], initContainers: [${neg_init}], injected-anno: '${neg_anno}'"
    # Assert ALL three injection artefacts are absent, not just the sidecar
    # container — a partial/unconditional-injection bug (init container or
    # idempotency annotation added without the opt-in) must also fail here.
    if [[ " ${neg_containers} " == *" mockserver-sidecar "* ]]; then
      printFailureMessage "NEGATIVE CONTROL FAILED: un-annotated pod was injected — webhook injects unconditionally"
      TEST_EXIT_CODE=1
    elif [[ " ${neg_init} " == *" mockserver-iptables-init "* ]]; then
      printFailureMessage "NEGATIVE CONTROL FAILED: un-annotated pod got the mockserver-iptables-init init container (partial unconditional injection)"
      TEST_EXIT_CODE=1
    elif [[ "${neg_anno}" == "true" ]]; then
      printFailureMessage "NEGATIVE CONTROL FAILED: un-annotated pod carries mockserver.org/injected=true (partial unconditional injection)"
      TEST_EXIT_CODE=1
    else
      printPassMessage "Negative control OK: un-annotated pod has no sidecar, no iptables-init, no injected annotation"
    fi
  fi

  logTestResult "${TEST_EXIT_CODE}" "${TEST_CASE}"
  return "${TEST_EXIT_CODE}"
}

integration_test
