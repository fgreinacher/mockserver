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
# - There is no distroless/java25 base yet, so a jlink-trimmed JDK 25 runtime (same module
#   set as the binary-bundle builder, see docs/code/cli.md) is copied onto
#   distroless/java-base.
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
# java.se aggregator + Netty Unsafe + TLS crypto + DNS + zipfs.
RUN "$JAVA_HOME/bin/jlink" \
      --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.naming.dns,jdk.zipfs \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /runtime

# Training run (JEP 514 one-step): start the server with -XX:AOTCacheOutput, wait until the
# status endpoint answers via the bundled HealthCheck, then stop cleanly so the JVM writes
# the cache at exit. The `ls` fails the build if the cache was not produced.
RUN /runtime/bin/java -XX:AOTCacheOutput=/mockserver.aot \
      -jar /mockserver-netty-jar-with-dependencies.jar -p 1080 & \
    SERVER_PID=$!; \
    for i in $(seq 1 240); do \
      /runtime/bin/java -cp /mockserver-netty-jar-with-dependencies.jar org.mockserver.cli.HealthCheck && break; \
      sleep 0.5; \
    done; \
    kill -TERM "$SERVER_PID" && wait "$SERVER_PID" || true; \
    ls -l /mockserver.aot

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

# -XX:MaxRAMPercentage=75.0 caps the JVM heap at 75% of the container memory limit (same
# rationale as the standard image). -XX:AOTCache loads the baked cache; if the cache is
# incompatible (e.g. different CPU arch) the JVM logs a warning and starts normally.
ENTRYPOINT ["/usr/lib/jvm/temurin25-trimmed/bin/java", "-Dfile.encoding=UTF-8", "-XX:MaxRAMPercentage=75.0", "-XX:AOTCache=/mockserver.aot", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT=1080

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/usr/lib/jvm/temurin25-trimmed/bin/java", "-cp", "/mockserver-netty-jar-with-dependencies.jar", "org.mockserver.cli.HealthCheck"]

CMD []
