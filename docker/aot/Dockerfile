#
# MockServer Dockerfile — EXPERIMENTAL JDK 25 AOT-cache variant (Project Leyden, JEP 483/514)
#
# Bakes an ahead-of-time cache (class loading/linking + profiles) into the image via a
# training run at build time, roughly halving container time-to-ready versus the
# standard image while remaining the real HotSpot JVM (100% feature parity — unlike
# GraalVM native-image, which cannot support MockServer's runtime user-class loading).
#
# Notes:
# - The AOT cache is CPU-architecture and JDK-build specific; it is created in this image
#   build, so multi-arch builds each get their own cache.
# - A jlink-trimmed JDK 25 runtime (same module set as the binary-bundle builder, see
#   docs/code/cli.md) is copied onto distroless/java-base: the baked AOT cache is trained
#   against this exact runtime, so it ships the runtime rather than the stock distroless/java25
#   base to keep the cache and the JDK build in lockstep.
# - netty-tcnative is intentionally omitted: TLS uses the JDK provider. Functionally
#   identical; TLS handshake throughput may be lower than the standard image.
#
# https://github.com/mock-server/mockserver-monorepo
# https://www.mock-server.com
#

ARG source=download

# build image — downloads release artifacts from Sonatype
FROM alpine:3.24 AS download

# Optional: trust a corporate root CA before `apk add`. The build context contains
# ca-bundle.pem (empty in CI, populated locally behind a TLS-inspecting proxy).
COPY ca-bundle.pem /tmp/local-ca.pem
RUN if [ -s /tmp/local-ca.pem ]; then \
      mkdir -p /etc/ssl/certs && \
      cp /tmp/local-ca.pem /etc/ssl/cert.pem && \
      cp /tmp/local-ca.pem /etc/ssl/certs/ca-certificates.crt; \
    fi && \
    apk add --update openssl ca-certificates bash wget && \
    if [ -s /tmp/local-ca.pem ]; then \
      mkdir -p /usr/local/share/ca-certificates && \
      cp /tmp/local-ca.pem /usr/local/share/ca-certificates/local-ca.crt && \
      update-ca-certificates >/dev/null 2>&1 || true; \
    fi
RUN printf 'tries=5\ntimeout=60\nwaitretry=10\nretry_on_host_error=on\nretry_connrefused=on\n' >> /etc/wgetrc
# SHA256-verify all downloads from Maven Central (audit finding F-DKR-06).
ARG VERSION=RELEASE
ARG REPOSITORY_URL=https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/${VERSION}/mockserver-netty-${VERSION}-jar-with-dependencies.jar
RUN wget --max-redirect=10 -O mockserver-netty-jar-with-dependencies.jar "$REPOSITORY_URL" \
    && wget --max-redirect=10 -qO- "${REPOSITORY_URL}.sha256" > /tmp/expected.sha256 \
    && echo "$(cat /tmp/expected.sha256)  mockserver-netty-jar-with-dependencies.jar" | sha256sum -c -

# build image — copies JAR from context
FROM alpine:3.24 AS copy
COPY mockserver-netty-jar-with-dependencies.jar .

FROM ${source} AS intermediate

# AOT build stage — jlink-trimmed JDK 25 runtime + training run producing the AOT cache
FROM eclipse-temurin:25-jdk-noble AS aot-build

COPY --from=intermediate mockserver-netty-jar-with-dependencies.jar /mockserver-netty-jar-with-dependencies.jar

# Same module set validated end-to-end for the jlink binary bundle (docs/code/cli.md):
# java.se aggregator + Netty Unsafe + TLS crypto + DNS + zipfs + jdk.management. jdk.management supplies
# com.sun.management.ThreadMXBean, which the Prometheus jvm_memory_allocated_bytes gauge
# (JvmMetricsCollector) needs — java.se aggregates only java.* modules, so without it that metric is
# silently suppressed.
RUN "$JAVA_HOME/bin/jlink" \
      --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.naming.dns,jdk.zipfs,jdk.management \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /runtime

# Training run (JEP 514 one-step): start the server with -XX:AOTCacheOutput, wait until the
# status endpoint answers via the bundled HealthCheck, then stop cleanly so the JVM writes
# the cache at exit. The `ls` fails the build if the cache was not produced.
# GC: this variant intentionally stays on the JDK default collector (G1), NOT the -XX:+UseZGC the
# other server images set — ZGC's fixed start-up cost defeats this variant's minimal-time-to-ready
# purpose. Do not add -XX:+UseZGC here. See docs/code/startup-performance.md for the measurements.
RUN /runtime/bin/java -XX:AOTCacheOutput=/mockserver.aot \
      -jar /mockserver-netty-jar-with-dependencies.jar -p 1080 & \
    SERVER_PID=$!; \
    for i in $(seq 1 240); do \
      /runtime/bin/java -cp /mockserver-netty-jar-with-dependencies.jar org.mockserver.cli.HealthCheck && break; \
      sleep 0.5; \
    done; \
    kill -TERM "$SERVER_PID" && wait "$SERVER_PID" || true; \
    ls -l /mockserver.aot

# HEALTHCHECK probe — a ~2 MB static binary, NOT a second JVM: a probe JVM started inside the
# container's memory cgroup every 10s pushed a 512 MiB container over its limit under load. Source:
# docker/healthcheck/mockserver-healthcheck.go (copied byte-identically into each image context).
# Cross-compiled on the BUILD platform: the Go toolchain crashes under QEMU emulation, so it must
# never run as the non-native leg of a multi-arch build. Needs BuildKit (the classic builder has no
# BUILDPLATFORM). The ELF e_machine check fails the build on a wrong-arch binary.
FROM --platform=$BUILDPLATFORM golang:1.27-alpine@sha256:8a5910f31396cd4d89662f56c68b3ae31d374308270a1c3bd96672ee5ed43414 AS healthcheck
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
COPY mockserver-healthcheck.go ./
RUN GOOS="${TARGETOS:-$(go env GOOS)}" \
 && GOARCH="${TARGETARCH:-$(go env GOARCH)}" \
 && case "$GOARCH" in amd64) ELF_MACHINE=62 ;; arm64) ELF_MACHINE=183 ;; \
      *) echo "ERROR: no ELF machine check for GOARCH=$GOARCH" >&2; exit 1 ;; esac \
 && CGO_ENABLED=0 GOTOOLCHAIN=local GOOS="$GOOS" GOARCH="$GOARCH" \
      go build -trimpath -buildvcs=false -ldflags="-s -w -buildid=" \
      -o /mockserver-healthcheck mockserver-healthcheck.go \
 && ACTUAL="$(od -An -tu2 -j18 -N2 /mockserver-healthcheck)" \
 && if [ "${ACTUAL##* }" != "$ELF_MACHINE" ]; then \
      echo "ERROR: /mockserver-healthcheck ELF machine ${ACTUAL##* }, expected $ELF_MACHINE for $GOARCH" >&2; exit 1; \
    fi

# runtime image — distroless base + trimmed JDK 25 runtime + jar + AOT cache
# (digest-pinned per repo convention; update the digest together with the Temurin 25 build,
# since the baked AOT cache is specific to the JDK build it was trained with)
FROM gcr.io/distroless/java-base-debian12:nonroot@sha256:a9930cad62d02853d7f3dede7281c4b916cbf74493c2d8d38564121aad92bf6c

LABEL org.opencontainers.image.authors="James Bloom <jamesdbloom@gmail.com>"

EXPOSE 1080

COPY --from=aot-build /runtime /usr/lib/jvm/temurin25-trimmed
COPY --from=aot-build /mockserver-netty-jar-with-dependencies.jar /
COPY --from=aot-build /mockserver.aot /

USER nonroot

# -XX:MaxRAMPercentage=50.0 caps the JVM heap at half the container memory limit, as in the sibling
# images: native JVM memory and kernel socket buffers need the rest (512 MiB is the supported floor).
# -XX:AOTCache loads the baked cache; if the cache is incompatible (e.g.
# different CPU arch) the JVM logs a warning and starts normally.
# GC: kept on the JDK default (G1), unlike the sibling server images' -XX:+UseZGC — see the training
# stage above. To trade start-up for ZGC's lower tail latency, pass -XX:+UseZGC via JAVA_TOOL_OPTIONS.
ENTRYPOINT ["/usr/lib/jvm/temurin25-trimmed/bin/java", "-Dfile.encoding=UTF-8", "-XX:MaxRAMPercentage=50.0", "-XX:AOTCache=/mockserver.aot", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT=1080

COPY --from=healthcheck /mockserver-healthcheck /mockserver-healthcheck
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/mockserver-healthcheck"]

CMD []
