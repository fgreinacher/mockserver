#!/usr/bin/env bash
# Delete the k3d cluster created by run.sh (removes everything).
set -euo pipefail
CLUSTER="${CLUSTER:-mockserver-obs}"
echo "==> Deleting k3d cluster '${CLUSTER}'..."
k3d cluster delete "${CLUSTER}"
echo "==> Done."
