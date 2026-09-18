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

echo "--- :package: Found JAR: $SHADED_JAR"
cp "$SHADED_JAR" docker/local/mockserver-netty-jar-with-dependencies.jar

SMOKE_TAG="mockserver/mockserver:smoke-test-$$"
SMOKE_CONTAINER="mockserver-smoke-$$"

cleanup() {
  docker rm -f "$SMOKE_CONTAINER" 2>/dev/null || true
  docker rmi "$SMOKE_TAG" 2>/dev/null || true
}
trap cleanup EXIT

echo "--- :docker: Building local image for smoke test"
docker build --tag "$SMOKE_TAG" docker/local

echo "--- :test_tube: Running smoke test"
docker run -d --name "$SMOKE_CONTAINER" -p 0:1080 "$SMOKE_TAG"

SMOKE_PORT=$(docker port "$SMOKE_CONTAINER" 1080 | head -1 | awk -F: '{print $NF}')
echo "MockServer container started on port $SMOKE_PORT"

DEADLINE=$((SECONDS + 30))
while [ $SECONDS -lt $DEADLINE ]; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "http://localhost:${SMOKE_PORT}/mockserver/status" 2>/dev/null || true)
  if [ "$STATUS" = "200" ]; then
    echo "MockServer responded with 200 OK"
    break
  fi
  sleep 1
done

if [ "$STATUS" != "200" ]; then
  echo "Smoke test FAILED: MockServer did not return 200 within 30s (last status: $STATUS)"
  echo "Container logs:"
  docker logs "$SMOKE_CONTAINER" 2>&1 | tail -30
  exit 1
fi

EXPECTATION_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
  "http://localhost:${SMOKE_PORT}/mockserver/expectation" \
  -H "Content-Type: application/json" \
  -d '{
    "httpRequest": {"method": "GET", "path": "/smoke-test"},
    "httpResponse": {"statusCode": 200, "body": "ok"}
  }' 2>/dev/null || true)

if [ "$EXPECTATION_RESPONSE" != "201" ]; then
  echo "Smoke test FAILED: could not create expectation (status: $EXPECTATION_RESPONSE)"
  exit 1
fi

MOCK_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${SMOKE_PORT}/smoke-test" 2>/dev/null || true)
if [ "$MOCK_RESPONSE" != "200" ]; then
  echo "Smoke test FAILED: mock did not respond correctly (status: $MOCK_RESPONSE)"
  exit 1
fi

echo "Smoke test PASSED: MockServer starts, accepts expectations, and serves mock responses"

docker rm -f "$SMOKE_CONTAINER" 2>/dev/null || true

echo "--- :test_tube: Running env var port override smoke test"
ENVVAR_CONTAINER="mockserver-envvar-$$"

cleanup_envvar() {
  docker rm -f "$ENVVAR_CONTAINER" 2>/dev/null || true
  docker rmi "$SMOKE_TAG" 2>/dev/null || true
}
trap cleanup_envvar EXIT

docker run -d --name "$ENVVAR_CONTAINER" -e MOCKSERVER_SERVER_PORT=1234 -p 0:1234 "$SMOKE_TAG"

ENVVAR_PORT=$(docker port "$ENVVAR_CONTAINER" 1234 | head -1 | awk -F: '{print $NF}')
echo "MockServer container started with MOCKSERVER_SERVER_PORT=1234 on host port $ENVVAR_PORT"

DEADLINE=$((SECONDS + 30))
STATUS=""
while [ $SECONDS -lt $DEADLINE ]; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "http://localhost:${ENVVAR_PORT}/mockserver/status" 2>/dev/null || true)
  if [ "$STATUS" = "200" ]; then
    echo "MockServer responded with 200 OK on overridden port"
    break
  fi
  sleep 1
done

if [ "$STATUS" != "200" ]; then
  echo "Env var smoke test FAILED: MockServer did not start on port 1234 within 30s (last status: $STATUS)"
  echo "Container logs:"
  docker logs "$ENVVAR_CONTAINER" 2>&1 | tail -30
  exit 1
fi

EXPECTATION_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
  "http://localhost:${ENVVAR_PORT}/mockserver/expectation" \
  -H "Content-Type: application/json" \
  -d '{
    "httpRequest": {"method": "GET", "path": "/envvar-test"},
    "httpResponse": {"statusCode": 200, "body": "ok"}
  }' 2>/dev/null || true)

if [ "$EXPECTATION_RESPONSE" != "201" ]; then
  echo "Env var smoke test FAILED: could not create expectation (status: $EXPECTATION_RESPONSE)"
  exit 1
fi

MOCK_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${ENVVAR_PORT}/envvar-test" 2>/dev/null || true)
if [ "$MOCK_RESPONSE" != "200" ]; then
  echo "Env var smoke test FAILED: mock did not respond correctly on overridden port (status: $MOCK_RESPONSE)"
  exit 1
fi

echo "Env var smoke test PASSED: MOCKSERVER_SERVER_PORT correctly overrides default port"

docker rm -f "$ENVVAR_CONTAINER" 2>/dev/null || true
docker rmi "$SMOKE_TAG" 2>/dev/null || true
trap - EXIT

.buildkite/scripts/docker-login.sh
.buildkite/scripts/ecr-login.sh

ECR_REPO="public.ecr.aws/t2x9c0i6/mockserver"

# Source provenance stamped into the pushed images as OCI labels
# (org.opencontainers.image.revision / .created). SOURCE_COMMIT is the commit this
# snapshot was built from — the perf harness reads it back off the running SUT and
# refuses to attribute a perf result to a commit the measured binary did not come
# from (mutable snapshot tag + cached agent = otherwise-unprovable provenance).
SOURCE_COMMIT="${BUILDKITE_COMMIT:-$(git rev-parse HEAD 2>/dev/null || echo '')}"
BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "--- :label: stamping image provenance: revision=${SOURCE_COMMIT:0:12} created=${BUILD_DATE}"

echo "--- :docker: Building and pushing mockserver/mockserver:snapshot (multi-arch)"

DOCKER_CMD="docker buildx build --platform linux/amd64,linux/arm64 --push --build-arg SOURCE_COMMIT=$SOURCE_COMMIT --build-arg BUILD_DATE=$BUILD_DATE --tag mockserver/mockserver:snapshot --tag mockserver/mockserver:mockserver-snapshot --tag ${ECR_REPO}:snapshot --tag ${ECR_REPO}:mockserver-snapshot docker/local"

echo "┌──────────────────────────────────────────────────────────────────"
echo "│ Docker Command (copy to reproduce locally):"
echo "│"
echo "│   $DOCKER_CMD"
echo "│"
echo "└──────────────────────────────────────────────────────────────────"
echo ""

docker buildx create --use --name builder 2>/dev/null || docker buildx use builder
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  --build-arg SOURCE_COMMIT="$SOURCE_COMMIT" \
  --build-arg BUILD_DATE="$BUILD_DATE" \
  --tag mockserver/mockserver:snapshot \
  --tag mockserver/mockserver:mockserver-snapshot \
  --tag "${ECR_REPO}:snapshot" \
  --tag "${ECR_REPO}:mockserver-snapshot" \
  docker/local

echo "--- :docker: Building and pushing mockserver/mockserver:snapshot-graaljs (multi-arch)"
cp docker/local/mockserver-netty-jar-with-dependencies.jar docker/graaljs/mockserver-netty-jar-with-dependencies.jar
# Stage a CA bundle into the graaljs build context. The alpine stages COPY it
# in and (when non-empty) trust it before `apk add`, so builds behind a
# corporate TLS-inspecting proxy succeed. Empty file in CI is a no-op. Populated
# from MOCKSERVER_LOCAL_CA_BUNDLE (or the NODE_EXTRA_CA_CERTS / AWS_CA_BUNDLE
# fallbacks). Remove any stale bundle first so a fresh one is always written.
rm -f docker/graaljs/ca-bundle.pem
docker/ensure-ca-bundle.sh docker/graaljs >/dev/null
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  --build-arg source=copy \
  --build-arg SOURCE_COMMIT="$SOURCE_COMMIT" \
  --build-arg BUILD_DATE="$BUILD_DATE" \
  --tag mockserver/mockserver:snapshot-graaljs \
  --tag mockserver/mockserver:mockserver-snapshot-graaljs \
  --tag "${ECR_REPO}:snapshot-graaljs" \
  --tag "${ECR_REPO}:mockserver-snapshot-graaljs" \
  docker/graaljs

echo "--- :docker: Building and pushing mockserver/mockserver-webhook:snapshot (multi-arch)"
# Download the webhook fat jar artifact from the build step
buildkite-agent artifact download "mockserver/mockserver-k8s-webhook/target/mockserver-k8s-webhook-*-jar-with-dependencies.jar" . 2>/dev/null || true

WEBHOOK_JAR=""
shopt -s nullglob
for f in mockserver/mockserver-k8s-webhook/target/mockserver-k8s-webhook-*-jar-with-dependencies.jar; do
  WEBHOOK_JAR="$f"
  break
done
shopt -u nullglob

if [[ -n "$WEBHOOK_JAR" ]]; then
  cp "$WEBHOOK_JAR" docker/webhook/mockserver-webhook.jar
  # Error-isolated: a webhook push failure must never abort the pipeline —
  # the main + GraalJS images have already been published above.
  # Push Docker Hub first (primary registry), then ECR separately.
  if ! docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --push \
    --tag mockserver/mockserver-webhook:snapshot \
    --tag mockserver/mockserver-webhook:mockserver-snapshot \
    docker/webhook; then
    echo "WARNING: webhook Docker Hub push failed — continuing (main images already published)"
  fi
  if ! docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --push \
    --tag "${ECR_REPO}-webhook:snapshot" \
    --tag "${ECR_REPO}-webhook:mockserver-snapshot" \
    docker/webhook; then
    echo "WARNING: webhook ECR push failed — continuing (Docker Hub is the primary registry)"
  fi
else
  echo "WARNING: Webhook fat jar not found — skipping webhook snapshot image push"
fi
