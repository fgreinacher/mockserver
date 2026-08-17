#!/usr/bin/env bash
set -euo pipefail

echo "--- :buildkite: Downloading shaded JAR artifact"
buildkite-agent artifact download "mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar" .

shopt -s nullglob
SHADED_JAR=""
for f in mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar; do
  case "$(basename "$f")" in
    *-sources.jar|*-javadoc.jar|original-*) continue ;;
  esac
  SHADED_JAR="$f"
  break
done
shopt -u nullglob
if [ -z "$SHADED_JAR" ]; then
  echo "Error: shaded JAR not found after artifact download"
  exit 1
fi

echo "--- :package: Copying shaded JAR as jar-with-dependencies"
JAR_DIR="mockserver/mockserver-netty/target"
mkdir -p "$JAR_DIR"
VERSION=$(basename "$SHADED_JAR" | sed -E 's/^mockserver-netty-no-dependencies-(.+)\.jar$/\1/')
if [ -z "$VERSION" ] || [ "$VERSION" = "$(basename "$SHADED_JAR")" ]; then
  echo "Error: could not extract version from $SHADED_JAR"
  exit 1
fi
JAR_NAME="mockserver-netty-${VERSION}-jar-with-dependencies.jar"
cp "$SHADED_JAR" "$JAR_DIR/$JAR_NAME"

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
