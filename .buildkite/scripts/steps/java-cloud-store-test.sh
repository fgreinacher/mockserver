#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Run the cloud blob-store contract suites (S3/MinIO, GCS/fake-gcs,
# Azure/Azurite) against real backing services via Testcontainers.
#
# WHY THIS IS A SEPARATE STEP rather than `-s` on java-build.sh:
#
#   run-in-docker.sh withholds the Docker socket from PR builds and
#   `exit 0`s the whole step when one is requested (see the DOCKER_SOCKET
#   block, "Skipping Docker-socket step on PR build"). Adding `-s` to
#   java-build.sh would therefore make the ENTIRE Java build — every unit
#   and integration test in the reactor — silently exit 0 on every PR
#   build. That trades a narrow false-positive for a total one.
#
#   Splitting the Docker-dependent modules into their own step keeps the
#   main build socket-free (so it runs on PRs) and confines the PR-build
#   socket skip to the three cloud modules, where it is announced loudly
#   in the log rather than hidden behind an `Assume`.
#
# These three suites are Docker-gated via
# `Assume.assumeTrue(DockerClientFactory.instance().isDockerAvailable())`.
# That guard is correct and stays — it makes the suite degrade gracefully
# off-CI. The defect it was masking was that CI never satisfied it, so the
# suites skipped on 100% of builds while reporting green.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MODULES="mockserver-blob-s3,mockserver-blob-gcs,mockserver-blob-azure"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver \
  -m 4g \
  --cache maven \
  --docker-socket \
  -- bash -ec "
    # Build the modules' dependencies without running their tests — the main
    # build step already covers those, and this step must stay scoped to the
    # cloud contract suites.
    ./mvnw -q -pl ${MODULES} -am install \
      -DskipTests -Djacoco.skip=true -Dmaven.javadoc.skip=true \
      -Dmaven.gitcommitid.skip=true -P '!build-ui' \
      --batch-mode --no-transfer-progress

    ./mvnw -pl ${MODULES} test \
      -Djacoco.skip=true -Dmaven.gitcommitid.skip=true \
      --batch-mode --no-transfer-progress

    # Fail closed. The Assume guards mean a missing/broken Docker socket
    # reports these suites as SKIPPED with Maven still exiting 0 — exactly
    # the false positive this step exists to remove.
    /build/.buildkite/scripts/steps/assert-suite-ran.sh \
      'mockserver-blob-s3/target/surefire-reports/TEST-*BlobStoreContractTest.xml' \
      'mockserver-blob-gcs/target/surefire-reports/TEST-*BlobStoreContractTest.xml' \
      'mockserver-blob-azure/target/surefire-reports/TEST-*BlobStoreContractTest.xml'
  "
