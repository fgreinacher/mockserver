#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Run the AsyncAPI live-broker integration suites (Kafka, Kafka+Avro, AMQP,
# MQTT 3.1.1, MQTT 5) against real brokers via Testcontainers.
#
# WHY THIS STEP EXISTS
#
#   The five *LiveBrokerIntegrationTest suites already ran in the main build —
#   Failsafe's **/*IntegrationTest.java include matches them and the main build
#   runs `clean install`, which reaches `verify`. But the main build deliberately
#   has NO Docker socket, so their Assume gate was never satisfied and they
#   SKIPPED EVERY TEST ON EVERY BUILD while the job still went green. Verified
#   from Buildkite: the `:maven: build` job passed (exit 0) in mockserver-java
#   builds #1580 and #1583, each reporting
#
#     failsafe:integration-test @ mockserver-async
#     Tests run: 5, Failures: 0, Errors: 0, Skipped: 5
#
#   (Both builds failed overall, on the separate cloud blob-store step — a green
#   job that tested nothing is exactly the false positive removed here.)
#
#   That is the same false positive `java-cloud-store-test.sh` was created to
#   remove for the cloud blob-store suites: a suite being *collected* is not the
#   same as a suite *testing anything*.
#
#   Run locally against real brokers the same five suites execute 24 tests in
#   ~75s wall clock, all passing — so this is cheap and genuinely green, not a
#   gate that needs the suites fixed first.
#
# WHY A SEPARATE STEP rather than `-s` on java-build.sh:
#
#   Identical reasoning to java-cloud-store-test.sh — run-in-docker.sh withholds
#   the Docker socket from PR builds and `exit 0`s the whole step when one is
#   requested. Adding `-s` to java-build.sh would make the ENTIRE Java reactor
#   silently exit 0 on every PR build, trading a narrow false positive for a
#   total one. Splitting keeps the main build socket-free and confines the
#   PR-build socket skip to this module, where it is announced in the log.
#
# WHY TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=false:
#
#   Carried over from java-cloud-store-test.sh and required here for the same
#   reason — this is the second Testcontainers-Java step on these agents, and the
#   failure is not self-announcing. Testcontainers-Java starts its Ryuk reaper
#   with Privileged=true by default (TestcontainersConfiguration.isRyukPrivileged()
#   defaults `ryuk.container.privileged` to "true"), and the elastic-ci-stack
#   agents run dockerd with user-namespace remapping, which rejects privileged
#   containers outright:
#
#     BadRequestException: Status 400: privileged mode is incompatible with
#     user namespaces...
#
#   Ryuk reaps through the mounted socket and does not need privileged mode, so
#   dropping only that flag keeps container cleanup working. NOTE the exact
#   variable name: Testcontainers maps a property to an env var by uppercasing,
#   replacing dots and prefixing TESTCONTAINERS_, so `ryuk.container.privileged`
#   becomes TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED. The intuitive-looking
#   TESTCONTAINERS_RYUK_PRIVILEGED is read by nothing and silently has no effect.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MODULE="mockserver-async"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver \
  -m 4g \
  --cache maven \
  --docker-socket \
  -e TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=false \
  -- bash -ec "
    # Build the module's dependencies without running their tests — the main
    # build step already covers those, and this step must stay scoped to the
    # live-broker suites.
    ./mvnw -pl ${MODULE} -am install \
      -DskipTests -Djacoco.skip=true -Dmaven.javadoc.skip=true \
      -Dmaven.gitcommitid.skip=true -P '!build-ui' \
      --batch-mode --no-transfer-progress

    # 'verify' (not 'test') — these are Failsafe ITs, so they only run in the
    # integration-test/verify phase.
    ./mvnw -pl ${MODULE} verify \
      -Djacoco.skip=true -Dmaven.gitcommitid.skip=true \
      --batch-mode --no-transfer-progress

    # Fail closed. The Assume guards mean a missing/broken Docker socket reports
    # these suites as SKIPPED with Maven still exiting 0 — exactly the false
    # positive this step exists to remove. Note the FAILSAFE report directory:
    # these are ITs, so they do not write to surefire-reports.
    /build/.buildkite/scripts/steps/assert-suite-ran.sh \
      'mockserver-async/target/failsafe-reports/TEST-*LiveBrokerIntegrationTest.xml'
  "
