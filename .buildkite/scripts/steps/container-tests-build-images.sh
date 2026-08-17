#!/usr/bin/env bash
set -euo pipefail

# Build, FROM THIS TREE, the Java artefacts the k3d Helm suite's three
# image-dependent cases need so they run BLOCKING in CI instead of skipping:
#
#   helm_sidecar_injection      -> mockserver/mockserver-webhook   image
#   helm_clustered_convergence  -> mockserver/mockserver:integration_testing_clustered
#   helm_jgroups_dns_ping       -> mockserver/mockserver:integration_testing_clustered
#
# Artefacts produced and handed to the helm step as Buildkite artifacts:
#   - mockserver-netty  fat jar                     -> base + clustered images
#   - mockserver-k8s-webhook fat jar                -> webhook handler image
#   - mockserver-state-infinispan jar + runtime deps-> clustered image /libs/*
#
# WHY ITS OWN STEP (mirrors node-launcher-build-jar.sh):
# The helm step (helm-integration-test.sh) runs on a Docker-only agent with NO
# JDK — it cannot run the Maven reactor these images need. Both steps live in the
# SAME pipeline (mockserver-container-tests), so a Buildkite artifact reaches the
# helm step (steps share no filesystem but DO share the build's artifacts). The
# heavy multi-module Maven build stays here; the k3d step only does `docker build`
# from the jars (a COPY into distroless — seconds), keeping it lean.
#
# WHY JARS, NOT `docker save` IMAGES:
# Measured 2026-08-17: the jars total ~200 MB (netty 99 MB, webhook 13 MB,
# infinispan runtime deps 85 MB across 159 jars) versus ~1.3 GB for the three
# images they produce (base 440 MB + clustered 620 MB + webhook 269 MB). A
# combined `docker save` dedupes their shared distroless base so the real
# figure is lower, but it stays a multiple of the jars. Shipping the small
# inputs and rebuilding the images cheaply in the consumer is the same hand-off
# shape node-launcher-build-jar.sh / the WAR artifact use — the image never
# crosses the boundary, the jar does.
#
# `-pl mockserver-netty,mockserver-k8s-webhook,mockserver-state-infinispan -am`
# builds those three modules and their upstream reactor deps ONCE (the union is
# resolved a single time); `-DskipTests -Dmaven.test.skip=true` skips all test
# compilation/execution — testing the Java is the Java pipeline's job, this step
# only needs the assembled jars.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver \
  -m 7g \
  --cache maven \
  -e "MAVEN_OPTS=-Xms2048m -Xmx6144m" \
  -- bash -c '
    set -euo pipefail
    ./mvnw -B --no-transfer-progress -T 1C -DskipTests -Dmaven.test.skip=true \
      -pl mockserver-netty,mockserver-k8s-webhook,mockserver-state-infinispan -am package
    # Stage the Infinispan runtime classpath (JGroups, Infinispan, ProtoStream, ...)
    # the clustered image mounts on its /libs/* classpath. Exclude org.mock-server
    # (the module jar itself is copied in explicitly next) and add it back so the
    # clustered image /libs holds module + deps, exactly as build_clustered_docker()
    # assembles it for local dev.
    LIBS_DIR="$PWD/mockserver-state-infinispan/target/clustered-libs"
    rm -rf "$LIBS_DIR" && mkdir -p "$LIBS_DIR"
    ./mvnw -B --no-transfer-progress -pl mockserver-state-infinispan \
      dependency:copy-dependencies -DincludeScope=runtime -DexcludeGroupIds=org.mock-server \
      -DoutputDirectory="$LIBS_DIR"
    cp mockserver-state-infinispan/target/mockserver-state-infinispan-*.jar "$LIBS_DIR/"
  '
