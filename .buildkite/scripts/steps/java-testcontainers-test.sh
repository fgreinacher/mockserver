#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Run the mockserver-testcontainers integration suite
# (MockServerContainerIntegrationTest) against a real MockServer container
# started via Testcontainers.
#
# WHY THIS STEP EXISTS
#
#   MockServerContainerIntegrationTest was previously named MockServerContainerIT.
#   A class ending in IT matches NEITHER Surefire (**/*Test.java) NOR Failsafe
#   (**/*IntegrationTest.java), and this module declares no **/*IT.java override,
#   so it was collected by nothing and ran nowhere — a silent gap. Renaming it to
#   *IntegrationTest makes the inherited Failsafe plugin collect it.
#
#   But collection alone reintroduces the SAME false positive the async/cloud
#   steps exist to remove: mockserver-testcontainers is in the main reactor, and
#   the main `:maven: build` deliberately runs WITHOUT a Docker socket, so the
#   suite's `Assume.assumeTrue(DockerAvailability.isAvailable(...))` gate would be
#   unsatisfied and it would SKIP every test on every build while the job stayed
#   green (Tests run: 1, Skipped: 1). A suite being collected is not the same as a
#   suite testing anything.
#
#   This step gives the suite a Docker socket so it actually runs, then asserts it
#   really ran (assert-suite-ran.sh) rather than trusting Maven's exit code — so a
#   skipped socket can no longer pass as a pass.
#
# WHY A SEPARATE STEP rather than `-s` on java-build.sh:
#
#   Identical reasoning to java-async-broker-test.sh and java-cloud-store-test.sh —
#   run-in-docker.sh withholds the Docker socket from PR builds and `exit 0`s the
#   whole step when one is requested. Adding `-s` to java-build.sh would make the
#   ENTIRE Java reactor silently exit 0 on every PR build, trading a narrow false
#   positive for a total one. Splitting keeps the main build socket-free and
#   confines the PR-build socket skip to this module, where it is announced in the
#   log.
#
# WHY TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=false:
#
#   Carried over from the async/cloud steps and required here for the same reason.
#   Testcontainers-Java starts its Ryuk reaper with Privileged=true by default
#   (TestcontainersConfiguration.isRyukPrivileged() defaults `ryuk.container.privileged`
#   to "true"), and the elastic-ci-stack agents run dockerd with user-namespace
#   remapping, which rejects privileged containers outright:
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
#
# WHY -m 7g AND NOT 4g:
#
#   Same defect, same fix as the async/cloud steps. `mockserver/.mvn/jvm.config`
#   pins the Maven JVM to `-Xms2048m -Xmx6144m` and mvnw prepends jvm.config to
#   MAVEN_OPTS, so the JVM running the `-am` dependency build below is permitted a
#   6g heap; under a 4g cgroup the kernel OOM-kills it (exit 137) before any test
#   runs, which silently removes exactly the coverage the assert-suite-ran.sh guard
#   exists to guarantee. 7g matches every other step that runs ./mvnw from
#   mockserver/.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MODULE="mockserver-testcontainers"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver \
  -m 7g \
  --cache maven \
  --docker-socket \
  -e TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=false \
  -- bash -ec "
    # Build the module's dependencies without running their tests — the main
    # build step already covers those, and this step must stay scoped to the
    # container integration suite.
    ./mvnw -pl ${MODULE} -am install \
      -DskipTests -Djacoco.skip=true -Dmaven.javadoc.skip=true \
      -Dmaven.gitcommitid.skip=true -P '!build-ui' \
      --batch-mode --no-transfer-progress

    # 'verify' (not 'test') — MockServerContainerIntegrationTest is a Failsafe IT,
    # so it only runs in the integration-test/verify phase.
    ./mvnw -pl ${MODULE} verify \
      -Djacoco.skip=true -Dmaven.gitcommitid.skip=true \
      --batch-mode --no-transfer-progress

    # Fail closed. The Assume guard means a missing/broken Docker socket reports
    # this suite as SKIPPED with Maven still exiting 0 — exactly the false positive
    # this step exists to remove. Note the FAILSAFE report directory: this is an IT,
    # so it does not write to surefire-reports.
    /build/.buildkite/scripts/steps/assert-suite-ran.sh \
      'mockserver-testcontainers/target/failsafe-reports/TEST-*MockServerContainerIntegrationTest.xml'
  "
