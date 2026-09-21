#
# MockServer Dockerfile
#
# https://github.com/mock-server/mockserver
# https://www.mock-server.com
#

ARG source=download

# build image — downloads release artifacts from Sonatype, tcnative from Maven Central
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
# Harden Maven Central downloads against transient DNS/connection blips (e.g. flaky
# networks, CI). GNU wget retries host-resolution + connection-refused errors;
# BusyBox wget ignores /etc/wgetrc, so this is a safe no-op there.
RUN printf 'tries=5\ntimeout=60\nwaitretry=10\nretry_on_host_error=on\nretry_connrefused=on\n' >> /etc/wgetrc
# Audit finding F-DKR-06: SHA256-verify all downloads from Maven Central.
# Maven Central publishes .sha256 alongside every artifact.
ARG VERSION=RELEASE
ARG REPOSITORY_URL=https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/${VERSION}/mockserver-netty-${VERSION}-jar-with-dependencies.jar
RUN wget --max-redirect=10 -O mockserver-netty-jar-with-dependencies.jar "$REPOSITORY_URL" \
    && wget --max-redirect=10 -qO- "${REPOSITORY_URL}.sha256" > /tmp/expected.sha256 \
    && echo "$(cat /tmp/expected.sha256)  mockserver-netty-jar-with-dependencies.jar" | sha256sum -c -
ARG TARGETARCH=amd64
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

# build image — copies JAR from context, downloads tcnative from Maven Central
FROM alpine:3.24 AS copy

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
COPY mockserver-netty-jar-with-dependencies.jar .
ARG TARGETARCH=amd64
# Audit finding F-DKR-06: SHA256-verify tcnative download from Maven Central.
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

FROM ${source} AS intermediate

# AppCDS build stage — jlink-trimmed JDK 26 runtime + training run producing the AppCDS archive.
# Mirrors docker/local/Dockerfile (the release/snapshot artifact); this Dockerfile is the public
# download-mode reference and additionally ships netty-tcnative (see the runtime stage below).
# NOTE: the JVM inside this runtime image is JDK 26 — the MockServer library itself is still
# compiled to the Java 17 bytecode floor (see AGENTS.md Java Compatibility Policy); running that
# jar on a newer JVM is fully supported and is a runtime-only choice, independent of compilation.
# ── Per-arch native trim + uncompressed repack (image download-size optimisation) ──
# The fat jar bundles netty native binaries for EVERY platform under META-INF/native/:
# linux, macOS and windows, each in x86_64 + aarch64. A linux container can only ever
# load its own arch's ELF .so — the macOS blobs are Mach-O (.jnilib) and the Windows
# ones are PE (.dll), object formats an ELF loader physically cannot map, and the
# wrong-arch linux .so is just as unusable. Dropping them removes ~13 MiB from the
# compressed image download with zero loss of function. Keyed on the same TARGETARCH
# used above to fetch the matching tcnative.
#
# The KEEP filter is the "*<arch>.so" SUFFIX, and that shape is deliberate: the linux
# natives for one arch are differently-named files —
#   libnetty_tcnative_linux_<arch>.so
#   libnetty_transport_native_epoll_<arch>.so   <-- note: NO "linux_" in the name
# so a "linux_<arch>" filter would silently delete the epoll .so and degrade epoll to
# NIO with no error anywhere in the log. Matching the "<arch>.so" suffix keeps both
# ELF .so and drops .jnilib/.dll/wrong-arch. NEVER narrow this filter such that it
# could remove this arch's epoll or tcnative .so.
#
# libnetty_quiche42_linux_<arch>.so used to be a third file here. The QUIC natives no
# longer ship in the default fat jar (they moved to the jar-with-dependencies-http3
# classifier), so there is nothing for this filter to keep: THESE IMAGES NO LONGER
# SERVE HTTP/3. That is a real capability change and it is worth being precise about
# the price, because it is not the ~11 MiB the standalone jar saves — this trim already
# discarded four of the five platforms' quiche binaries, so the image was only ever
# carrying ~2.4 MiB of them. A user who sets http3Port here gets an actionable start-up
# failure (MockServer.requireQuicNative) rather than a silently unserved UDP port, and
# can restore HTTP/3 by mounting netty-codec-native-quic-<ver>-linux-<arch>.jar (~2.7 MiB)
# into /libs, which is already on the classpath in the ENTRYPOINT below.
#
# The jar is then repacked with STORED (uncompressed) entries. A jar is already a zip,
# so the image layer's gzip gains almost nothing on it; storing the class bytes raw lets
# the layer gzip compress them and shrinks the jar layer a further ~16 MiB. The AppCDS
# training run below re-derives /mockserver.jsa from THIS repacked jar, so the archive
# stays consistent (verified to map under -Xshare:on).
#
# ── Split into own.jar + deps.jar for INCREMENTAL image download ──
# (docs/plans/performance-programme.md, "Scopes this programme never covered" item 1.)
# A single file cannot span image layers, so the ONLY way to make a version bump re-pull
# less than the whole ~57 MiB (compressed) jar layer is to ship MockServer's own classes
# and its third-party dependencies as SEPARATE artifacts:
#   own.jar   = org/mockserver/**            — MockServer's own classes, change every release
#   deps.jar  = everything else (the deps)   — byte-stable across a patch release
# Measured on the 8.0.1 fat jar: own = ~16% of the compressed bytes, deps = ~84%. Across the
# 8.0.0 -> 8.0.1 boundary 88.5% of the dependency bytes were byte-identical (and that boundary
# even bumped BouncyCastle/Netty/OTel; a patch that bumps no dependency changes 0% of deps.jar).
# So deps.jar's layer is REUSED on `docker pull` at upgrade instead of re-pulled, and on a fresh
# pull it and the runtime layer fetch CONCURRENTLY rather than one 57 MiB layer fetched serially.
# The union own.jar + deps.jar on the classpath is byte-for-byte the same entry set as the fat
# jar (verified: 2256 + 41844 = 44100 entries), so runtime behaviour is identical by construction
# — the split point only decides WHICH layer an entry lands in, never whether it loads (both jars
# are on the -cp). Nothing uses `-jar`, so a Main-Class manifest is irrelevant; the version is
# baked into org/mockserver/version/Version.class, not read from a manifest.
#
# Layer REUSE additionally requires deps.jar to be BYTE-REPRODUCIBLE across releases: identical
# dependency bytes must yield an identical file, hence an identical layer digest. `zip -r` walks
# the filesystem in readdir order and stamps live mtimes — non-deterministic — so both jars are
# instead built from a SORTED entry list with a FIXED timestamp on every entry AND on the output
# file (COPY carries the source mtime into the layer tar). Directory entries are omitted; a jar
# without them is valid and loads via the classpath the same way (Spring Boot / Gradle jars do
# this), and it keeps the deterministic single-pass `zip -@` simple.
FROM intermediate AS jarprep
RUN apk add --no-cache zip unzip
ARG TARGETARCH=amd64
WORKDIR /jarprep
# FIVE native families are trimmed, not one. netty's live under META-INF/native/ and are matched by
# ARCH SUFFIX; zstd-jni, snappy, JNA and lz4-java each use their own directory layout and are matched by
# DIRECTORY PREFIX. Measured in the 8.0.1 fat jar: 28.17 MiB of native binaries, of which 14.62 MiB
# cannot be loaded by a linux container at all - zstd-jni alone ships 18 platform builds where
# exactly one is loadable. Trimming only META-INF/native/ left roughly
# 9 MiB of unusable binaries in the image.
#
# Two details in the RUN below that are easy to undo by accident:
#   MANIFEST.MF is re-added FIRST. A plain recursive `zip -r` orders entries by filesystem
#   walk, leaving the manifest buried. Random-access JarFile (what `java -jar` and `-cp` use)
#   finds it wherever it sits, but a streaming java.util.jar.JarInputStream reader only looks
#   at the first couple of entries and would silently see a null manifest.
#   The final greps FAIL THE BUILD if no native for this arch survived the trim. Without them a
#   filter that silently matched nothing would still produce a working - merely larger - image,
#   and the whole size win would disappear with no signal. There is one assertion PER FAMILY on
#   purpose: a single combined check passes as long as any one family survives, which is exactly
#   how a broken filter for one of the other four would go unnoticed. lz4-java was found only by
#   listing what SURVIVED a trim run - it had been invisible in the by-owner breakdown.
RUN set -eu; \
    if [ "$TARGETARCH" = "amd64" ]; then \
      ARCH=x86_64; \
      KEEP_ZSTD="linux/amd64/"; \
      KEEP_SNAPPY="org/xerial/snappy/native/Linux/x86_64/"; \
      KEEP_JNA="com/sun/jna/linux-x86-64/"; \
      KEEP_LZ4="net/jpountz/util/linux/amd64/"; \
    else \
      ARCH=aarch_64; \
      KEEP_ZSTD="linux/aarch64/"; \
      KEEP_SNAPPY="org/xerial/snappy/native/Linux/aarch64/"; \
      KEEP_JNA="com/sun/jna/linux-aarch64/"; \
      KEEP_LZ4="net/jpountz/util/linux/aarch64/"; \
    fi; \
    cp /mockserver-netty-jar-with-dependencies.jar app.jar; \
    for e in $(unzip -l app.jar 'META-INF/native/*' | awk '/META-INF\/native\/.+[^\/]$/{print $NF}'); do \
      case "$e" in *"$ARCH".so) : ;; *) echo "trim $e"; zip -q -d app.jar "$e" ;; esac; \
    done; \
    trim_prefix() { \
      keep="$1"; glob="$2"; \
      for e in $(unzip -l app.jar "$glob" | awk '$NF ~ /\.(so|dll|dylib|jnilib)$/ {print $NF}'); do \
        case "$e" in "$keep"*) : ;; *) echo "trim $e"; zip -q -d app.jar "$e" ;; esac; \
      done; \
    }; \
    trim_prefix "$KEEP_ZSTD"   '*libzstd-jni*'; \
    trim_prefix "$KEEP_SNAPPY" 'org/xerial/snappy/native/*'; \
    trim_prefix "$KEEP_JNA"    'com/sun/jna/*'; \
    trim_prefix "$KEEP_LZ4"    'net/jpountz/util/*'; \
    mkdir ex && (cd ex && unzip -q ../app.jar) && rm app.jar; \
    find ex -exec touch -h -t 200001010000.00 {} +; \
    ( cd ex; \
      find . -type f | sed 's#^\./##' | LC_ALL=C sort > /tmp/all.txt; \
      grep    '^org/mockserver/' /tmp/all.txt                                    > /tmp/own.txt; \
      grep -v '^org/mockserver/' /tmp/all.txt | grep -vx 'META-INF/MANIFEST.MF' > /tmp/deps.txt; \
      zip -q -0 -X ../deps.jar META-INF/MANIFEST.MF; \
      zip -q -0 -X -@ ../deps.jar < /tmp/deps.txt; \
      zip -q -0 -X -@ ../own.jar  < /tmp/own.txt ); \
    touch -t 200001010000.00 deps.jar own.jar; \
    echo "own.jar entries: $(unzip -l own.jar | tail -1); deps.jar entries: $(unzip -l deps.jar | tail -1)"; \
    unzip -Z1 own.jar | grep -qx "org/mockserver/cli/Main.class" || { echo "ERROR: own.jar is missing MockServer's own classes"; exit 1; }; \
    LEAK="$(unzip -Z1 own.jar | grep -v '^org/mockserver/' || true)"; \
    if [ -n "$LEAK" ]; then echo "ERROR: own.jar contains non-org/mockserver entries - the split predicate is wrong:"; echo "$LEAK"; exit 1; fi; \
    echo "kept natives:"; unzip -l deps.jar 'META-INF/native/*' "${KEEP_ZSTD}*" "${KEEP_SNAPPY}*" "${KEEP_JNA}*" "${KEEP_LZ4}*"; \
    unzip -l deps.jar 'META-INF/native/*' | grep -q "libnetty_transport_native_epoll_$ARCH.so" || { echo "ERROR: no netty epoll $ARCH native survived the trim"; exit 1; }; \
    unzip -l deps.jar 'META-INF/native/*' | grep -q "libnetty_tcnative_linux_$ARCH.so" || { echo "ERROR: no netty tcnative $ARCH native survived the trim"; exit 1; }; \
    if unzip -l deps.jar 'META-INF/native/*' | grep -q "quiche"; then echo "ERROR: a quiche native is present - this image builds from the DEFAULT jar, which must not carry QUIC natives"; exit 1; fi; \
    unzip -l deps.jar | grep -q "${KEEP_ZSTD}libzstd-jni" || { echo "ERROR: no zstd native for $TARGETARCH survived the trim"; exit 1; }; \
    unzip -l deps.jar | grep -q "${KEEP_SNAPPY}libsnappyjava" || { echo "ERROR: no snappy native for $TARGETARCH survived the trim"; exit 1; }; \
    unzip -l deps.jar | grep -q "${KEEP_JNA}libjnidispatch" || { echo "ERROR: no JNA native for $TARGETARCH survived the trim"; exit 1; }; \
    unzip -l deps.jar | grep -q "${KEEP_LZ4}liblz4-java" || { echo "ERROR: no lz4 native for $TARGETARCH survived the trim"; exit 1; }

FROM eclipse-temurin:26-jdk-noble AS appcds-build

# Split artifacts (see the jarprep stage): own classes and third-party deps as two jars, both
# on the classpath. The AppCDS classpath is derived from this exact -cp, so the runtime ENTRYPOINT
# below MUST present the same two entries in the same order (with /libs/* appended) for the archive
# to map — see the training run and ENTRYPOINT.
COPY --from=jarprep /jarprep/own.jar  /mockserver.jar
COPY --from=jarprep /jarprep/deps.jar /mockserver-deps.jar

# Same module set validated end-to-end for the jlink binary bundle (scripts/build-binary-bundle.sh,
# docs/code/cli.md). From JDK 25 onwards jlink uses the --compress=zip-<level> form (zip-6 = the default ZIP
# level); the legacy numeric --compress=2 form was deprecated after JDK 17 and is removed from JDK 25 onwards.
# A jlink image does not carry the JDK's default CDS base archive, so -Xshare:dump regenerates it
# from the bundled lib/classlist — the dynamic AppCDS archive below is layered on that base, and the
# base is carried into the runtime image with /runtime.
RUN "$JAVA_HOME/bin/jlink" \
      --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.naming.dns,jdk.zipfs \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /runtime \
 && /runtime/bin/java -Xshare:dump

# Training run (dynamic AppCDS, JEP 350): start the server with -XX:ArchiveClassesAtExit, wait until
# the status endpoint answers via the bundled HealthCheck (which also drives the post-bind warmup),
# then stop cleanly so the JVM writes the archive at exit. The `ls` fails the build if the archive
# was not produced. tcnative is not on the training classpath (it is added only at runtime), so the
# archive covers the JDK-provider path; tcnative classes simply load normally at runtime.
RUN /runtime/bin/java -XX:ArchiveClassesAtExit=/mockserver.jsa \
      -cp /mockserver.jar:/mockserver-deps.jar org.mockserver.cli.Main -p 1080 & \
    SERVER_PID=$!; \
    for i in $(seq 1 240); do \
      /runtime/bin/java -cp /mockserver.jar:/mockserver-deps.jar org.mockserver.cli.HealthCheck && break; \
      sleep 0.5; \
    done; \
    kill -TERM "$SERVER_PID" && wait "$SERVER_PID" || true; \
    ls -l /mockserver.jsa

# runtime image — distroless base + trimmed JDK 26 runtime + jar + AppCDS archive + tcnative .so
# (digest-pinned per repo convention; update the digest together with the Temurin 25 build, since
# the baked AppCDS archive is specific to the JDK build it was trained with — same base+digest as
# docker/aot/Dockerfile)
FROM gcr.io/distroless/java-base-debian12:nonroot@sha256:a9930cad62d02853d7f3dede7281c4b916cbf74493c2d8d38564121aad92bf6c

# maintainer details
LABEL org.opencontainers.image.authors="James Bloom <jamesdbloom@gmail.com>"

# MCP registry ownership annotation — proves this image backs the com.mock-server/mockserver
# entry on the official MCP registry (https://registry.modelcontextprotocol.io). Required before the
# server.json OCI package can be published. The namespace is DNS-verified against mock-server.com so
# the release can publish non-interactively from CI. See docs/operations/mcp-registry-publishing.md.
LABEL io.modelcontextprotocol.server.name="com.mock-server/mockserver"

# expose ports.
EXPOSE 1080

# copy in trimmed runtime, jar, AppCDS archive, and (unlike docker/local) the netty-tcnative .so
# NOTE: the /usr/lib/jvm/temurin25-trimmed path name is HISTORICAL and does NOT track the JDK
# version - this image ships JDK 26. It is referenced by the ENTRYPOINT and HEALTHCHECK below
# and by sibling Dockerfiles, so it is left as-is rather than churned; read the FROM line above
# for the actual version.
COPY --from=appcds-build /runtime /usr/lib/jvm/temurin25-trimmed
# deps.jar is COPYed on its OWN layer, separately from own.jar, so that a version bump whose
# dependencies are unchanged re-pulls only own.jar (~11 MiB compressed) + the CDS archive rather
# than the whole ~57 MiB fat-jar layer; the deps layer is reused. See the jarprep stage.
COPY --from=appcds-build /mockserver-deps.jar /
COPY --from=appcds-build /mockserver.jar /
COPY --from=appcds-build /mockserver.jsa /
# Native-TLS fallback for read-only-rootfs deployments (docker run --read-only):
# normally /tmp is writable and Netty extracts the tcnative .so from the jar, so
# this copy is dormant; under --read-only that extraction fails and System.loadLibrary
# finding /usr/lib/libnetty_tcnative_linux_<arch>.so is the only thing keeping
# BoringSSL native TLS alive. Inert for the shaded no-dependencies jar (relocated
# tcnative symbols cannot bind this stock .so). Introduced by e4dd3a61b.
COPY --from=intermediate META-INF/native/libnetty_tcnative_linux_*.so /usr/lib/

# don't run MockServer as root
USER nonroot

# -XX:MaxRAMPercentage=60.0 caps the JVM heap at 60% of the container memory limit so the
# in-memory request/expectation rings size off a bounded heap. A committed heap costs ~1.48x its
# size in real RSS (Netty direct arenas, G1 metadata, thread stacks), so a 75% cap OOM-SIGKILLed a
# 2 GiB container under load; 60% keeps peak RSS near ~88% of the limit. Only applies when the user
# has NOT set an explicit heap; to change the cap set an explicit -Xmx (via JAVA_TOOL_OPTIONS) —
# that disables MaxRAMPercentage. A different MaxRAMPercentage via JAVA_TOOL_OPTIONS does NOT take
# effect: it is prepended before these args, so this entrypoint flag is applied last and wins.
# -XX:SharedArchiveFile loads the baked AppCDS archive. -Xshare:auto (the JVM default) means a
# missing or unusable archive (e.g. bind-mounted away, corrupt, or an arch mismatch) logs a warning
# and starts normally rather than failing.
ENTRYPOINT ["/usr/lib/jvm/temurin25-trimmed/bin/java", "-Dfile.encoding=UTF-8", "-XX:MaxRAMPercentage=60.0", "-XX:SharedArchiveFile=/mockserver.jsa", "-cp", "/mockserver.jar:/mockserver-deps.jar:/libs/*", "-Dmockserver.propertyFile=/config/mockserver.properties", "org.mockserver.cli.Main"]

ENV SERVER_PORT=1080

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
  CMD ["/usr/lib/jvm/temurin25-trimmed/bin/java", "-cp", "/mockserver.jar:/mockserver-deps.jar", "org.mockserver.cli.HealthCheck"]

CMD []
