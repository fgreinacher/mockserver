#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Run the transparent-proxy original-destination end-to-end suites:
#
#   SoOriginalDstEndToEndIntegrationTest   (iptables REDIRECT + SO_ORIGINAL_DST, --cap-add=NET_ADMIN)
#   TproxyEndToEndIntegrationTest          (iptables TPROXY / IP_TRANSPARENT,    --cap-add=NET_ADMIN)
#   EbpfOriginalDestinationEndToEndIntegrationTest (pinned BPF map read path,    --privileged)
#
# WHY THIS STEP EXISTS
#
#   These three suites were named *EndToEndIT. Surefire collects **/*Test.java and
#   Failsafe collects **/*IntegrationTest.java, so a class ending `IT` matched
#   NEITHER — they were never compiled into a run set and never executed on any
#   build (a grep of real CI logs finds zero occurrences). Renaming them to
#   *EndToEndIntegrationTest lets Failsafe collect them. This step then RUNS them
#   and asserts they actually executed, so "collected" can no longer masquerade as
#   "tested" (the same false positive java-cloud-store-test.sh and
#   java-async-broker-test.sh remove for their Docker-gated suites).
#
# WHY IT IS OPT-IN (RUN_TRANSPARENT_PROXY_E2E) AND NOT WIRED TO RUN BY DEFAULT
#
#   Unlike the cloud/async suites (which talk to the Docker daemon via docker-java
#   over the mounted socket), these suites shell out to the `docker` CLI to build
#   and run a SIBLING container, and they need real kernel privilege inside it:
#
#     * The maven CI image (docker_build/maven/Dockerfile) does NOT ship the
#       `docker` CLI binary, so `DockerCliTestSupport.isDockerAvailable()` returns
#       false and every suite SKIPS on the current agents.
#     * The elastic-ci-stack build agents run dockerd with user-namespace
#       remapping, which REJECTS `--privileged` outright ("privileged mode is
#       incompatible with user namespaces"). The eBPF suite needs --privileged, so
#       even with a docker CLI present it would be refused and SKIP (the suites
#       detect this via DockerCliTestSupport.containerStartRejected(...) and skip
#       cleanly rather than error).
#
#   A fail-closed assert-suite-ran over suites that can only SKIP would turn the
#   pipeline PERMANENTLY RED without testing anything — the exact anti-pattern
#   DockerAvailability's javadoc calls out: "a fail-closed assertion is only safe
#   once the suite can actually pass." So by default this step does NOT run them;
#   it prints a LOUD, EXPANDED notice making the untested state VISIBLE on every
#   build (not a silent green that pretends coverage).
#
#   To actually exercise them, run this step on an agent that (a) has the `docker`
#   CLI, (b) mounts the Docker socket, and (c) permits NET_ADMIN / --privileged
#   sibling containers (i.e. a daemon WITHOUT user-namespace remapping, or a
#   dedicated privileged queue), and set RUN_TRANSPARENT_PROXY_E2E=true. Then the
#   assert-suite-ran guard below fails closed if any suite skipped instead of
#   running.
#
# WHY -m 7g AND NOT 4g:
#
#   Same defect, same fix as java-cloud-store-test.sh (see its header for the
#   measurements). `mockserver/.mvn/jvm.config` pins the Maven JVM to
#   `-Xms2048m -Xmx6144m` and mvnw prepends jvm.config to MAVEN_OPTS, so the JVM
#   running the `-am` dependency build below is permitted a 6g heap; under a 4g
#   cgroup the kernel OOM-kills it (exit 137) before any test runs. This step is
#   opt-in today, so it has not been observed failing that way — the limit is
#   corrected here so it does not bite the first time someone sets
#   RUN_TRANSPARENT_PROXY_E2E=true. 7g matches every other step that runs
#   ./mvnw from mockserver/.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE="mockserver-netty"

if [[ "${RUN_TRANSPARENT_PROXY_E2E:-false}" != "true" ]]; then
  echo "+++ :warning: transparent-proxy end-to-end suites NOT executed on this agent"
  cat <<'EOF'
The three privileged transparent-proxy end-to-end suites

  SoOriginalDstEndToEndIntegrationTest
  TproxyEndToEndIntegrationTest
  EbpfOriginalDestinationEndToEndIntegrationTest

are COLLECTED by Failsafe but were NOT run on this agent, so their coverage is
NOT asserted here. They shell out to the `docker` CLI and need NET_ADMIN /
--privileged sibling containers:

  * the maven CI image has no `docker` CLI binary, and
  * the elastic-ci-stack agents run dockerd with user-namespace remapping, which
    rejects --privileged (the eBPF suite).

Running them under a fail-closed assertion on such an agent would go permanently
RED without testing anything. To run them for real, use an agent with the docker
CLI, the Docker socket, and NET_ADMIN/--privileged support, and set
RUN_TRANSPARENT_PROXY_E2E=true. See docs/infrastructure/ci-cd.md and
docs/infrastructure/service-mesh.md.
EOF
  exit 0
fi

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver \
  -m 7g \
  --cache maven \
  --docker-socket \
  -- bash -ec "
    # Build the module and its dependencies (produces the fat JAR the suites mount
    # into the sibling container via findFatJar); the main build covers unit tests.
    ./mvnw -pl ${MODULE} -am install \
      -DskipTests -Djacoco.skip=true -Dmaven.javadoc.skip=true \
      -Dmaven.gitcommitid.skip=true -P '!build-ui' \
      --batch-mode --no-transfer-progress

    # 'verify' (not 'test') — these are Failsafe ITs, so they only run in the
    # integration-test/verify phase. Scope to the three transparent-proxy suites.
    ./mvnw -pl ${MODULE} verify \
      -Dtest='ZzzNoSuchUnitTest' \
      -Dit.test='TproxyEndToEndIntegrationTest,SoOriginalDstEndToEndIntegrationTest,EbpfOriginalDestinationEndToEndIntegrationTest' \
      -DfailIfNoTests=false \
      -Djacoco.skip=true -Dmaven.gitcommitid.skip=true \
      --batch-mode --no-transfer-progress

    # Fail closed. The Assume guards mean a missing docker CLI, an unusable socket,
    # a missing kernel module, or a daemon that refuses the privileged container
    # all report these suites as SKIPPED with Maven still exiting 0 — exactly the
    # false positive this step exists to remove. These are ITs, so read the
    # FAILSAFE report directory.
    /build/.buildkite/scripts/steps/assert-suite-ran.sh \
      'mockserver-netty/target/failsafe-reports/TEST-*SoOriginalDstEndToEndIntegrationTest.xml' \
      'mockserver-netty/target/failsafe-reports/TEST-*TproxyEndToEndIntegrationTest.xml' \
      'mockserver-netty/target/failsafe-reports/TEST-*EbpfOriginalDestinationEndToEndIntegrationTest.xml'
  "
