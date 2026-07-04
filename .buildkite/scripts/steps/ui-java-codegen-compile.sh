#!/usr/bin/env bash
# Permanent CI gate: compile the dashboard composer's GENERATED Java.
#
# The composer's "Java" tab (mockserver-ui/src/lib/standardCodegen.ts ->
# standardToJava) emits fluent MockServer client code. It is otherwise only
# verified by TypeScript string-assertion tests, which pin the shape of the
# emitted snippet but CANNOT catch a client-API rename (a fluent method that no
# longer exists on MockServerClient or the org.mockserver.model.* builders) — the
# generated Java would ship broken silently. This gate closes that gap: it emits a
# broad matrix of standardToJava outputs and javac-compiles them against the REAL
# mockserver-client-java jar, failing on any drift.
#
# THREE phases, each in the toolchain it needs:
#   1. Maven image  — build mockserver-client-java (+deps) and dump its full
#                      compile classpath.
#   2. node:22      — run the emitter (Node native TS type-stripping; NO npm ci,
#                      standardCodegen.ts is dependency-free).
#   3. Maven image  — javac --release 17 the emitted samples against that classpath.
#
# CI runs each phase inside its Docker image via run-in-docker.sh (the raw agent
# has no mvn/node/javac). For fast local validation set
# CODEGEN_COMPILE_USE_DOCKER=false to run the exact same command strings against
# host mvn/node/javac instead.
#
# This step is path-gated in generate-pipeline.sh to the mockserver-java pipeline,
# which fires on changes under mockserver/ (incl. mockserver-client-java/) AND
# mockserver-ui/ — the two inputs whose drift this gate is designed to catch.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

USE_DOCKER="${CODEGEN_COMPILE_USE_DOCKER:-true}"

# Work dir (under the gitignored .tmp/) and the classpath dump file, expressed as
# absolute paths INSIDE the execution context — /build when mounted in Docker,
# the real repo root when running natively on the host.
if [ "$USE_DOCKER" = "true" ]; then
  BASE="/build"
else
  BASE="$REPO_ROOT"
fi
WORK_DIR="$BASE/.tmp/codegen-compile-gate"
CP_FILE="$WORK_DIR/classpath.txt"
SAMPLES_DIR="$WORK_DIR/samples"
CLASSES_DIR="$WORK_DIR/classes"

CLIENT_CLASSES="$BASE/mockserver/mockserver-client-java/target/classes"

# ---- Phase 1: build the client jar + dump its compile classpath (Maven) -------
MAVEN_CMDS="$(cat <<EOF
set -eu
rm -rf "$WORK_DIR"
mkdir -p "$SAMPLES_DIR" "$CLASSES_DIR"
echo '--- :maven: building mockserver-client-java (+deps) and dumping classpath'
cd "$BASE/mockserver"
./mvnw -q -pl mockserver-client-java -am install -DskipTests -T 1C
./mvnw -q -pl mockserver-client-java dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
echo "    classpath written to $CP_FILE (\$(wc -c < "$CP_FILE") bytes)"
EOF
)"

# ---- Phase 2: emit the kitchen-sink samples (Node, no npm ci) ------------------
EMIT_CMD="$(cat <<EOF
set -eu
echo '--- :node: emitting standardToJava() samples'
node "$BASE/mockserver-ui/scripts/emit-java-codegen-samples.mjs" "$SAMPLES_DIR"
EOF
)"

# ---- Phase 3: compile the samples against the real client jar (javac) ---------
JAVAC_CMD="$(cat <<EOF
set -eu
echo '--- :java: compiling emitted samples against the real client jar'
n=\$(ls "$SAMPLES_DIR"/*.java | wc -l | tr -d ' ')
# Coverage floor (review COR-02): a regression that silently shrinks emitter
# output must fail loudly, not pass with reduced coverage. Bump when adding cases.
if [ "\$n" -lt 23 ]; then
  echo "FATAL: expected at least 23 emitted samples, found \$n" >&2
  exit 1
fi
echo "    compiling \$n sample(s)"
javac --release 17 -cp "$CLIENT_CLASSES:\$(cat "$CP_FILE")" -d "$CLASSES_DIR" "$SAMPLES_DIR"/*.java
compiled=\$(ls "$CLASSES_DIR"/*.class | wc -l | tr -d ' ')
echo "--- :white_check_mark: all generated Java compiled (\$compiled class file(s))"
EOF
)"

if [ "$USE_DOCKER" = "true" ]; then
  "$SCRIPT_DIR/../run-in-docker.sh" \
    -i mockserver/mockserver:maven \
    -m 7g \
    --cache maven \
    -- bash -c "$MAVEN_CMDS"

  "$SCRIPT_DIR/../run-in-docker.sh" \
    -i node:22 \
    -- bash -c "$EMIT_CMD"

  # javac needs the SAME maven cache mounted: classpath.txt holds absolute paths
  # into ~/.m2/repository (where --cache maven mounts), so those jars must be
  # present in this container too.
  "$SCRIPT_DIR/../run-in-docker.sh" \
    -i mockserver/mockserver:maven \
    --cache maven \
    -- bash -c "$JAVAC_CMD"
else
  echo "=== running natively (CODEGEN_COMPILE_USE_DOCKER=false) ==="
  bash -c "$MAVEN_CMDS"
  bash -c "$EMIT_CMD"
  bash -c "$JAVAC_CMD"
fi
