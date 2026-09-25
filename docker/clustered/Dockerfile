#
# MockServer Clustered Dockerfile
#
# Image variant that includes the Infinispan state backend module and its
# transitive dependencies (JGroups, Infinispan, ProtoStream, etc.) so that
# operators can enable clustered state replication with:
#
#   MOCKSERVER_STATE_BACKEND=infinispan
#   MOCKSERVER_CLUSTER_ENABLED=true
#
# The default (non-clustered) image does NOT ship these libraries, keeping
# its size lean for the majority of users who run a single instance.
#
# Build locally (copy mode — from a local Maven build):
#   cp mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar docker/clustered/
#   mvn -pl mockserver-state-infinispan dependency:copy-dependencies \
#       -DincludeScope=runtime -DexcludeGroupIds=org.mock-server \
#       -DoutputDirectory=docker/clustered/libs
#   cp mockserver-state-infinispan/target/mockserver-state-infinispan-*.jar docker/clustered/libs/
#   docker build -t mockserver/mockserver:clustered docker/clustered
#
# https://github.com/mock-server/mockserver
# https://www.mock-server.com
#

# build image — downloads netty-tcnative from Maven Central (mirrors base image pattern)
FROM alpine:3.24 AS tcnative

# Optional: trust a corporate root CA before `apk add`. The build context contains
# ca-bundle.pem (empty in CI, populated locally behind a TLS-inspecting proxy).
COPY ca-bundle.pem /tmp/local-ca.pem
RUN if [ -s /tmp/local-ca.pem ]; then \
      mkdir -p /etc/ssl/certs && \
      cp /tmp/local-ca.pem /etc/ssl/cert.pem && \
      cp /tmp/local-ca.pem /etc/ssl/certs/ca-certificates.crt; \
    fi && \
    apk add --update ca-certificates wget && \
    if [ -s /tmp/local-ca.pem ]; then \
      mkdir -p /usr/local/share/ca-certificates && \
      cp /tmp/local-ca.pem /usr/local/share/ca-certificates/local-ca.crt && \
      update-ca-certificates >/dev/null 2>&1 || true; \
    fi
# Harden Maven Central downloads against transient DNS/connection blips (e.g. flaky
# networks, CI). GNU wget retries host-resolution + connection-refused errors;
# BusyBox wget ignores /etc/wgetrc, so this is a safe no-op there.
RUN printf 'tries=5\ntimeout=60\nwaitretry=10\nretry_on_host_error=on\nretry_connrefused=on\n' >> /etc/wgetrc
ARG TARGETARCH=amd64
# Audit finding F-DKR-06: SHA256-verify tcnative download from Maven Central.
# bring in the server jar so the tcnative version can be read from it
COPY mockserver-netty-jar-with-dependencies.jar .
RUN NETTY_TCNATIVE="$(unzip -p mockserver-netty-jar-with-dependencies.jar META-INF/mockserver-tcnative.version | tr -d '[:space:]')" \
    && if [ -z "$NETTY_TCNATIVE" ]; then echo "ERROR: META-INF/mockserver-tcnative.version missing or empty in mockserver-netty-jar-with-dependencies.jar" >&2; exit 1; fi \
    && if [ "$TARGETARCH" = "amd64" ]; \
    then TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-x86_64.jar"; \
    else TCNATIVE_URL="https://repo1.maven.org/maven2/io/netty/netty-tcnative-boringssl-static/$NETTY_TCNATIVE/netty-tcnative-boringssl-static-$NETTY_TCNATIVE-linux-aarch_64.jar"; \
    fi \
    && wget -O netty-tcnative-boringssl-static.jar "$TCNATIVE_URL" \
    && wget -qO- "${TCNATIVE_URL}.sha256" > /tmp/expected-tcnative.sha256 \
    && echo "$(cat /tmp/expected-tcnative.sha256)  netty-tcnative-boringssl-static.jar" | sha256sum -c -
RUN unzip netty-tcnative-boringssl-static.jar

# runtime image https://console.cloud.google.com/gcr/images/distroless/global/java21
# Java 21, not 25: Infinispan 14.0.35 calls Subject.getSubject during cache-manager
# startup, which throws UnsupportedOperationException from JDK 24 onward (JEP 486
# permanently disabled the Security Manager). No JVM flag restores it; Infinispan 15
# fixes the call but requires a Java 21+ compile target, which the Java 17 library
# floor does not allow. 21 is the newest base on which clustering starts.
FROM gcr.io/distroless/java21:nonroot@sha256:0a1f5a75661918de9c0813f287f651c3bf2d6dd752eada5f084eb0c1f14ced9e

# maintainer details
LABEL org.opencontainers.image.authors="James Bloom <jamesdbloom@gmail.com>"

# Source provenance (OCI convention). revision = the git commit this image was
# BUILT from; created = the RFC-3339 build time. Both are stamped by the build via
# --build-arg (java-docker-push-snapshot.sh for the snapshot tags), and are EMPTY
# on a plain `docker build` (forks/self-hosters) so the label is simply absent
# rather than wrong. Mirrors docker/local + docker/graaljs.
#
# WHY THIS IMAGE NEEDS IT TOO: the perf harness runs the item-13 clustered A/B
# against THIS image while the run itself is attributed to the SUT (graaljs) image's
# revision. Without a revision here there is no way to prove the two came from the
# same commit, and a stale `mockserver-snapshot-clustered` tag would file a clustered
# ratio under a commit whose clustered code it never executed — a silently meaningless
# comparison. perf-test-run.sh compares this label with the SUT's and refuses to
# measure on a mismatch.
ARG SOURCE_COMMIT=
ARG BUILD_DATE=
LABEL org.opencontainers.image.revision="${SOURCE_COMMIT}"
LABEL org.opencontainers.image.created="${BUILD_DATE}"

# expose ports.
EXPOSE 1080

# copy in the main fat jar
COPY mockserver-netty-jar-with-dependencies.jar /

# Native-TLS fallback for read-only-rootfs deployments (docker run --read-only):
# normally /tmp is writable and Netty extracts the tcnative .so from the jar, so
# this copy is dormant; under --read-only that extraction fails and System.loadLibrary
# finding /usr/lib/libnetty_tcnative_linux_<arch>.so is the only thing keeping
# BoringSSL native TLS alive. Inert for the shaded no-dependencies jar (relocated
# tcnative symbols cannot bind this stock .so). Introduced by e4dd3a61b.
COPY --from=tcnative META-INF/native/libnetty_tcnative_linux_*.so /usr/lib/

# copy in the clustered state backend libraries (Infinispan, JGroups, etc.)
# These are placed on the classpath via the /libs/* glob in the ENTRYPOINT.
COPY libs/ /libs/

# don't run MockServer as root
USER nonroot

# -XX:MaxRAMPercentage=60.0 caps the JVM heap at 60% of the container memory limit so the
# in-memory request/expectation rings size off a bounded heap. A committed heap costs ~1.48x its
# size in real RSS (Netty direct arenas, G1 metadata, thread stacks), so a 75% cap OOM-SIGKILLed a
# 2 GiB container under load; 60% keeps peak RSS near ~88% of the limit. Only applies when the user
# has NOT set an explicit heap; to change the cap set an explicit -Xmx (via JAVA_TOOL_OPTIONS) —
# that disables MaxRAMPercentage. A different MaxRAMPercentage via JAVA_TOOL_OPTIONS does NOT take
# effect: it is prepended before these args, so this entrypoint flag is applied last and wins.
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-XX:MaxRAMPercentage=60.0", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT=1080

# Dashboard usage analytics (PostHog Cloud EU, cookieless). EMPTY by default so a plain
# `docker build` — i.e. forks / self-hosters building this Dockerfile directly — ships analytics
# INERT (the dashboard's activation gate requires a non-empty endpoint AND key). Only the
# project's official release build injects the real values via --build-arg
# (scripts/release/components/docker.sh). The key is a write-only public ingest key, safe to
# expose. A user can always override at runtime with `-e MOCKSERVER_DASHBOARD_ANALYTICS_*`.
ARG DASHBOARD_ANALYTICS_ENDPOINT=
ARG DASHBOARD_ANALYTICS_KEY=
ENV MOCKSERVER_DASHBOARD_ANALYTICS_ENDPOINT=${DASHBOARD_ANALYTICS_ENDPOINT}
ENV MOCKSERVER_DASHBOARD_ANALYTICS_KEY=${DASHBOARD_ANALYTICS_KEY}
# Labels analytics events by the artefact they came from. A plain, non-secret constant — safe to
# commit directly: it is only ever sent when analytics is already active (which still requires the
# injected endpoint+key above), so forks building this Dockerfile directly stay inert.
ENV MOCKSERVER_DASHBOARD_ANALYTICS_DISTRIBUTION=docker-clustered

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["java", "-cp", "/mockserver-netty-jar-with-dependencies.jar:/libs/*", "org.mockserver.cli.HealthCheck"]

CMD []
