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
#
# `-P clustered-libs` activates a package-bound `dependency:copy-dependencies`
# execution in the mockserver-state-infinispan module that stages that module's
# Infinispan runtime classpath (JGroups, Infinispan, ProtoStream, ...) into
# target/clustered-libs — the /libs/* classpath the `-clustered` image mounts.
# Doing this INSIDE the build reactor (not a second, standalone `-pl` Maven run)
# is deliberate: copy-dependencies operates on the module's already-resolved
# dependency set, so the org.mock-server siblings it must resolve first come from
# the in-session reactor, never ~/.m2. A separate invocation starts a fresh
# reactor with no in-session siblings and can only resolve those SNAPSHOTs from a
# populated ~/.m2 — which `package` never writes, so it failed closed on a clean
# CI agent ("Could not find artifact org.mock-server:mockserver-core:...:SNAPSHOT").
# One reactor invocation has no cross-invocation cache state to get wrong.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# SC2016: the single-quoted bash -c body is intentional — $PWD and $LIBS_DIR must
# expand inside the container's shell at run time, not on the host at compose time.
# shellcheck disable=SC2016
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver \
  -m 7g \
  --cache maven \
  -e "MAVEN_OPTS=-Xms2048m -Xmx6144m" \
  -- bash -c '
    set -euo pipefail
    LIBS_DIR="$PWD/mockserver-state-infinispan/target/clustered-libs"
    # Clear any stale libs from an earlier local build; the reactor recreates the
    # directory during the infinispan module package phase (clustered-libs profile).
    rm -rf "$LIBS_DIR"
    ./mvnw -B --no-transfer-progress -T 1C -DskipTests -Dmaven.test.skip=true \
      -P clustered-libs \
      -pl mockserver-netty,mockserver-k8s-webhook,mockserver-state-infinispan -am package
    # copy-dependencies (above) staged the runtime deps into clustered-libs, excluding
    # org.mock-server. Add the module jar itself so /libs holds module + deps, exactly
    # as build_clustered_docker() assembles it for local dev.
    cp mockserver-state-infinispan/target/mockserver-state-infinispan-*.jar "$LIBS_DIR/"
  '
