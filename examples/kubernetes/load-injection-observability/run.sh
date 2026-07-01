#!/usr/bin/env bash
# MockServer load-injection observability demo on a local k3s (k3d) cluster.
#
# Stands up MockServer (injector + mock SUT), an OpenTelemetry Collector,
# Prometheus and Grafana; preloads a load scenario; triggers the run; and
# opens port-forwards so you can watch every load-injection metric live on the
# provisioned Grafana dashboard — alongside JVM and pod CPU/memory.
#
# Prerequisites: docker, k3d (https://k3d.io), kubectl. All images are public.
# Tear down with ./teardown.sh (deletes the whole cluster).
set -euo pipefail

CLUSTER="${CLUSTER:-mockserver-obs}"
NS="mockserver-demo"
SCENARIO="storefront-load"
cd "$(dirname "$0")"

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' is required but not installed."; exit 1; }; }
need docker; need k3d; need kubectl

echo "==> [1/6] Creating k3d cluster '${CLUSTER}' (if absent)..."
if ! k3d cluster list 2>/dev/null | awk '{print $1}' | grep -qx "${CLUSTER}"; then
  k3d cluster create "${CLUSTER}" --agents 1 --wait
else
  echo "    cluster '${CLUSTER}' already exists — reusing it"
fi
kubectl config use-context "k3d-${CLUSTER}" >/dev/null

echo "==> [2/6] Creating namespace and config..."
kubectl apply -f manifests/00-namespace.yaml
# ConfigMaps built from the on-disk JSON files (kept as first-class editable files):
kubectl create configmap mockserver-config --from-file=config/ -n "${NS}" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl create configmap grafana-dashboards --from-file=grafana/dashboards/ -n "${NS}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> [3/6] Applying MockServer, OTEL Collector, Prometheus and Grafana..."
kubectl apply -f manifests/

echo "==> [4/6] Waiting for workloads to become ready..."
for d in otel-collector prometheus grafana mockserver; do
  kubectl rollout status "deploy/${d}" -n "${NS}" --timeout=180s
done

echo "==> [5/6] Opening port-forwards (grafana:3000, prometheus:9090, mockserver:1080)..."
PIDS=()
cleanup() { trap - EXIT INT TERM; echo; echo "==> stopping port-forwards (cluster stays up — run ./teardown.sh to delete it)"; for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM
kubectl port-forward -n "${NS}" svc/grafana    3000:3000 >/dev/null 2>&1 & PIDS+=($!)
kubectl port-forward -n "${NS}" svc/prometheus 9090:9090 >/dev/null 2>&1 & PIDS+=($!)
kubectl port-forward -n "${NS}" svc/mockserver 1080:1080 >/dev/null 2>&1 & PIDS+=($!)

# wait for the mockserver port-forward to accept connections
for _ in $(seq 1 30); do
  curl -s -o /dev/null "http://localhost:1080/mockserver/status" && break || sleep 1
done

echo "==> [6/6] Triggering load scenario '${SCENARIO}'..."
start_resp="$(curl -s -X PUT "http://localhost:1080/mockserver/loadScenario/start" -d "{\"names\":[\"${SCENARIO}\"]}")"
if echo "${start_resp}" | grep -q '"state" : "RUNNING"'; then
  echo "    scenario RUNNING"
else
  echo "    WARNING: scenario did not report RUNNING. Response was:"; echo "${start_resp}"
fi

cat <<EOF

============================================================================
  MockServer load-injection observability demo is live.

  Grafana     : http://localhost:3000   (dashboard: "MockServer — Load
                Injection & Observability", anonymous admin, no login needed)
  Prometheus  : http://localhost:9090
  MockServer  : http://localhost:1080   (metrics: /mockserver/metrics)

  Prove the OpenTelemetry (OTLP) push channel is live too:
    kubectl logs deploy/otel-collector -n ${NS} --tail=20
    (or query Prometheus: up{job="mockserver-otlp"})

  Re-trigger / stop the run:
    curl -X PUT http://localhost:1080/mockserver/loadScenario/start -d '{"names":["${SCENARIO}"]}'
    curl -X PUT http://localhost:1080/mockserver/loadScenario/stop  -d '{"all":true}'

  Press Ctrl-C to stop the port-forwards. Run ./teardown.sh to delete the cluster.
============================================================================
EOF

wait
