#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -m 7g \
  --cache maven \
  -- bash -c 'cd mockserver && ./mvnw -B --no-transfer-progress clean install -DskipTests -DskipITs && ./mvnw -B --no-transfer-progress -f mockserver-maven-plugin/pom.xml clean verify'
