#!/usr/bin/env bash
set -euo pipefail

# The container-integration harness (integration_tests.sh -> build_docker) builds
# docker/Dockerfile with --build-arg source=copy, staging whatever it finds at
# mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar.
# docker/Dockerfile is the public download-mode REFERENCE image: its source=download
# path fetches the DEFAULT fat jar from Maven Central, and its jarprep stage trims and
# then ASSERTS default-fat-jar native contents (every platform's tcnative present, and
# NO quiche - HTTP/3 ships in the separate -http3 classifier). For the copy smoke test
# to exercise the same thing the download path ships, it MUST be fed the SAME artifact:
# the default mockserver-netty jar-with-dependencies.
#
# NOT the shaded mockserver-netty-no-dependencies jar: that relocates netty, carries NO
# tcnative under the stock name and DOES carry quiche, so it fails jarprep's tcnative and
# quiche assertions. The shaded jar is what docker/local consumes and is exercised by the
# ":docker: build and push :snapshot" step (java-docker-push-snapshot.sh), not here. This
# matches how helm-integration-test.sh already stages the default fat jar for docker/clustered.
echo "--- :buildkite: Downloading default fat JAR artifact"
buildkite-agent artifact download "mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar" .

shopt -s nullglob
FAT_JAR=""
for f in mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar; do
  FAT_JAR="$f"
  break
done
shopt -u nullglob
if [ -z "$FAT_JAR" ]; then
  echo "Error: default fat JAR (mockserver-netty-*-jar-with-dependencies.jar) not found after artifact download — the upstream :maven: build step must upload it"
  exit 1
fi
echo "Default fat JAR present: $FAT_JAR"
# Already at the exact path+name integration_tests.sh globs, so no re-stage is needed.

# The docker_compose_war_tomcat case deploys the WAR into a Tomcat container.
# The WAR is built by the reactor in the ":maven: build" step and uploaded as an
# artifact there (Buildkite steps share no filesystem). Download it into the path
# the test globs (mockserver/mockserver-war/target/). Fail closed if it is
# absent — this case exists to guard WAR deployment (a demonstrated weak spot),
# so a missing WAR must red the step, never silently skip.
echo "--- :buildkite: Downloading WAR artifact"
buildkite-agent artifact download "mockserver/mockserver-war/target/mockserver-war-*.war" .
shopt -s nullglob
WARS=( mockserver/mockserver-war/target/mockserver-war-*.war )
shopt -u nullglob
if [ ${#WARS[@]} -eq 0 ]; then
  echo "Error: WAR artifact not found after download — docker_compose_war_tomcat cannot run. Failing closed."
  exit 1
fi
echo "WAR artifact present: ${WARS[0]}"

echo "--- :docker: Running container integration tests (Docker Compose only)"
export SKIP_JAVA_BUILD=true
export SKIP_HELM_TESTS=true
export SKIP_DOCKER_REBUILD_CLIENT=false

exec container_integration_tests/integration_tests.sh
