#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Build the MockServer Docker image (source=copy) and prove it actually works —
# not merely that `docker build` exited 0.
#
# WHY
# ---
# The tcnative native .so the image downloads is versioned from a stamp baked into
# the server jar (META-INF/mockserver-tcnative.version). Nothing in CI ever built
# an image on a `docker/**`-only change, so a broken stamp or a Dockerfile that
# stopped pairing the .so with the classes could ship silently (only human review
# caught the last one). This is the build backstop: it runs the fast two-jar
# derive check FIRST (verify-tcnative-stamp.sh), then builds a real image and
# asserts the native actually loads at runtime.
#
# THE SAME SCRIPT RUNS IN CI AND LOCALLY — that is the point. It detects rather
# than assumes its environment (host arch, corporate CA bundle, buildkite-agent),
# so a developer on an arm64 Mac and a Buildkite agent run identical logic.
#
# WHAT IT ASSERTS (fails closed — non-zero exit — on any):
#   * the two-jar tcnative stamp derive check passes (delegated);
#   * the runtime image contains the arch-correct tcnative .so under /usr/lib/
#     (a wrong-arch .so — the classic-builder TARGETARCH=amd64 trap on arm64 —
#     is a failure, not a pass);
#   * the native TLS provider actually LOADS in the image's own JVM
#     (OpenSsl.isAvailable() == true — proving the image reproduces source=download's
#     real native-TLS behaviour. NOTE the assembly jar BUNDLES the per-platform
#     natives, so Netty loads the native from the jar, not from the /usr/lib .so this
#     image downloads — this probe does NOT isolate that download. Wrong/empty/missing
#     stamps are caught upstream by verify-tcnative-stamp.sh and the Dockerfile's own
#     empty-check, and assert_so_present below separately asserts the arch-correct
#     /usr/lib .so exists);
#   * native TLS STILL loads under `docker run --read-only` (no --tmpfs /tmp) — the
#     one check that actually exercises the /usr/lib .so's purpose: with a read-only
#     /tmp Netty cannot extract the jar-bundled native, so BoringSSL survives only
#     via System.loadLibrary finding /usr/lib/libnetty_tcnative_linux_*.so. This is
#     what guards the `COPY ... /usr/lib/` line against a silent removal;
#   * the container starts, answers its health check, and completes a real TLS
#     handshake on its port.
#
# ARCH TRAP
#   BuildKit auto-injects TARGETARCH, but the classic builder does NOT and the
#   Dockerfiles default `ARG TARGETARCH=amd64`. On an arm64 host that silently
#   fetches the x86_64 .so, which then fails to load. We DETECT the host arch and
#   pass --build-arg TARGETARCH explicitly so the build is correct under either
#   builder.
#
# CA STAGING
#   docker/ensure-ca-bundle.sh stages ca-bundle.pem into the build context (the
#   corporate root when MOCKSERVER_LOCAL_CA_BUNDLE / AWS_CA_BUNDLE point at one,
#   else an empty placeholder). We ALWAYS remove what we staged on exit — the
#   scratch bundle and the staged jar must never be left in the tree.
#
# SELF-TEST HOOKS (mirror check-certificate-expiry.sh's env overrides)
#   DOCKER_VERIFY_SKIP_BUILD=true   run the runtime assertions against an existing
#                                   image instead of building (fast red/green).
#   DOCKER_VERIFY_IMAGE=<ref>       assert against this image ref (point at a stock
#                                   image with no .so to prove the guards go red).
#   DOCKER_VERIFY_SO_OVERRIDE=<path-in-image>  force the expected .so path (point
#                                   at the wrong arch to prove the presence guard).
#
# Requires: docker, tar, and a jar (built on demand via mvnw if absent). Runs the
# probe compile in a throwaway temurin JDK container, so it needs no host JDK.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

# nullglob at SCRIPT scope so an unmatched jar glob expands to NOTHING rather than
# staying literal. The jar globs below are expanded by THIS shell before the
# helper functions run, so enabling nullglob only inside a helper is too late: the
# literal pattern would already have been passed in, defeating both the
# build-on-demand detection AND the post-build "no jar after build" guard (a
# literal, non-existent path tests as non-empty and sails through). See the guards
# in locate_or_build_jars().
shopt -s nullglob

STEPS_DIR="$REPO_ROOT/.buildkite/scripts/steps"
DOCKER_CONTEXT="$REPO_ROOT/docker"
REACTOR_DIR="$REPO_ROOT/mockserver"
IMAGE_TAG="${DOCKER_VERIFY_IMAGE:-mockserver/mockserver:docker-build-verify}"
PROBE_JDK_IMAGE="eclipse-temurin:25-jdk-noble"
IN_IMAGE_JAVA="/usr/lib/jvm/temurin25-trimmed/bin/java"
IN_IMAGE_JAR="/mockserver-netty-jar-with-dependencies.jar"

# ── Detect host arch → docker TARGETARCH + the .so the runtime must contain ─────
case "$(uname -m)" in
  x86_64|amd64)  TARGETARCH=amd64; SO_PATH='usr/lib/libnetty_tcnative_linux_x86_64.so' ;;
  arm64|aarch64) TARGETARCH=arm64; SO_PATH='usr/lib/libnetty_tcnative_linux_aarch_64.so' ;;
  *) echo "+++ :bangbang: unsupported host arch '$(uname -m)' — cannot map to a tcnative .so, failing closed" >&2; exit 1 ;;
esac
SO_PATH="${DOCKER_VERIFY_SO_OVERRIDE:-$SO_PATH}"

CLEANUP=()
cleanup() {
  local item
  for item in "${CLEANUP[@]}"; do
    case "$item" in
      container:*)  docker rm -f "${item#container:}"        >/dev/null 2>&1 || true ;;
      file:*)       rm -f "${item#file:}"                    || true ;;
      dir:*)        rm -rf "${item#dir:}"                    || true ;;
      image:*)      docker rmi -f "${item#image:}"           >/dev/null 2>&1 || true ;;
    esac
  done
}
trap cleanup EXIT

fail() { echo "+++ :bangbang: $*" >&2; exit 1; }

# ── Locate the assembly jar-with-dependencies, building it on demand if absent ──
# We stage the maven-assembly mockserver-netty-<ver>-jar-with-dependencies.jar —
# the SAME artifact the default source=download images pull from Sonatype, and the
# one integration_tests.sh stages for its source=copy build. Building source=copy
# from it is therefore a faithful local reproduction of the DEFAULT published
# image without needing a post-stamp release.
#
# WHY THIS JAR and not the shaded no-dependencies one: the assembly jar bundles the
# per-platform tcnative natives, so the native provider actually loads at runtime
# and the OpenSsl.isAvailable() assertion below is a real, green native-TLS check —
# which is exactly source=download's behaviour. (The shaded no-dependencies jar
# strips the natives; in the distroless base the standalone /usr/lib .so does not
# dlopen, so that path silently runs on the JDK provider — which is by design for
# docker/local, but is NOT the tcnative path this gate exists to protect.) The
# two-jar derive check still independently covers the shaded jar's stamp.
# nullglob is enabled at script scope (see top of file), so an unmatched caller
# glob arrives here as ZERO args and this returns non-zero — never the literal
# pattern. Do NOT toggle nullglob inside this function: the glob is expanded by the
# caller before we run, so a local shopt here would be too late to matter.
first_real_jar() {
  local f
  for f in "$@"; do
    case "$(basename "$f")" in
      *-sources.jar|*-javadoc.jar|original-*) continue ;;
    esac
    printf '%s\n' "$f"; return 0
  done
  return 1
}

# Build BOTH stamped jars (assembly + shaded no-dependencies) so the inner derive
# gate has both and the image build has the assembly jar. Uses host mvnw when a
# JDK is on the host (developer machine); otherwise builds inside the pinned
# mockserver/mockserver:maven image via run-in-docker.sh — the repo's sanctioned
# no-host-JDK path, since CI agents run Maven in that image rather than on the raw
# agent. Both write into the bind-mounted workspace, so the SAME script works in
# either environment.
build_jars() {
  # `install` (not `package`) so mockserver-core lands in the maven cache — the
  # inner derive gate's `dependency:list -pl mockserver-netty` resolves the sibling
  # SNAPSHOT modules from there. Mirrors ui-java-codegen-compile.sh.
  # shellcheck disable=SC2054  # the comma is inside the Maven -pl module list, not an array separator
  local mvn_args=(-q -B --no-transfer-progress
                  -pl mockserver-netty,mockserver-netty-no-dependencies -am
                  -DskipTests install)
  if command -v java >/dev/null 2>&1 && [ -x "$REACTOR_DIR/mvnw" ]; then
    echo "--- :maven: building server jars on host (mvnw ${mvn_args[*]})" >&2
    ( cd "$REACTOR_DIR" && ./mvnw "${mvn_args[@]}" ) >&2 \
      || fail "host mvnw build failed — cannot obtain the server jars, failing closed"
  else
    echo "--- :maven: no host JDK — building server jars inside mockserver/mockserver:maven" >&2
    "$REPO_ROOT/.buildkite/scripts/run-in-docker.sh" \
      -i mockserver/mockserver:maven -w /build/mockserver --cache maven \
      -- ./mvnw "${mvn_args[@]}" >&2 \
      || fail "in-image mvnw build failed — cannot obtain the server jars, failing closed"
  fi
}

# Ensure both jars exist (building on demand), then echo the assembly jar path.
locate_or_build_jars() {
  local asm shaded
  asm="$(first_real_jar "$REACTOR_DIR"/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar || true)"
  shaded="$(first_real_jar "$REACTOR_DIR"/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar || true)"
  if [ -z "$asm" ] || [ -z "$shaded" ]; then
    build_jars
    asm="$(first_real_jar "$REACTOR_DIR"/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar || true)"
  fi
  [ -n "$asm" ] || fail "no mockserver-netty-*-jar-with-dependencies.jar after build — failing closed"
  printf '%s\n' "$asm"
}

# ── Assert the runtime image contains the arch-correct tcnative .so ─────────────
assert_so_present() {
  echo "--- :mag: asserting arch-correct tcnative .so is baked into the image ($SO_PATH)"
  local cid found
  cid="$(docker create "$IMAGE_TAG")" || fail "docker create '$IMAGE_TAG' failed"
  CLEANUP+=( "container:$cid" )
  # Export the flattened rootfs and look for the exact arch .so.
  found="$(docker export "$cid" | tar -tf - 2>/dev/null | grep -E "^${SO_PATH}\$" || true)"
  docker rm -f "$cid" >/dev/null 2>&1 || true
  if [ -z "$found" ]; then
    echo "    :bangbang: image does NOT contain $SO_PATH" >&2
    # Diagnostic: list the natives that ARE present. Capture the cid so this
    # throwaway container is removed too (even if the export itself fails).
    local dcid present
    if dcid="$(docker create "$IMAGE_TAG" 2>/dev/null)"; then
      CLEANUP+=( "container:$dcid" )
      present="$(docker export "$dcid" | tar -tf - 2>/dev/null | grep -E 'usr/lib/libnetty_tcnative' | tr '\n' ' ' || true)"
      docker rm -f "$dcid" >/dev/null 2>&1 || true
    fi
    echo "    (present tcnative natives: ${present:-none})" >&2
    fail "arch-correct tcnative .so missing from image — a wrong-arch or absent native would break TLS, failing closed"
  fi
  echo "    :white_check_mark: found $found"
}

# ── Compile the OpenSsl.isAvailable probe once, into a throwaway dir ─────────────
# Reused by every provider assertion below (default and --read-only) so the compile
# runs a single time. Sets PROBE_DIR (registered for cleanup).
PROBE_DIR=""
ensure_probe_compiled() {
  [ -n "$PROBE_DIR" ] && return 0
  PROBE_DIR="$(mktemp -d)"
  CLEANUP+=( "dir:$PROBE_DIR" )
  cat > "$PROBE_DIR/TlsProviderProbe.java" <<'JAVA'
public class TlsProviderProbe {
    public static void main(String[] a) throws Exception {
        // Reflection so this compiles with only java.base and works whether netty
        // is relocated (shaded no-dependencies jar -> shaded_package.io.netty) or
        // not (assembly jar-with-dependencies -> io.netty).
        String[] candidates = {"io.netty.handler.ssl.OpenSsl", "shaded_package.io.netty.handler.ssl.OpenSsl"};
        Class<?> k = null;
        for (String n : candidates) { try { k = Class.forName(n); break; } catch (Throwable ignore) {} }
        if (k == null) { System.out.println("PROBE: OpenSsl class not found on classpath"); System.exit(2); }
        boolean ok = (Boolean) k.getMethod("isAvailable").invoke(null);
        System.out.println("PROBE: OpenSsl.class=" + k.getName());
        System.out.println("PROBE: OpenSsl.isAvailable=" + ok);
        if (!ok) {
            System.out.println("PROBE: OpenSsl.unavailabilityCause=" + k.getMethod("unavailabilityCause").invoke(null));
            System.exit(3);
        }
        System.out.println("PROBE: OpenSsl.versionString=" + k.getMethod("versionString").invoke(null));
    }
}
JAVA
  # Compile with a throwaway full JDK (the runtime image is trimmed and has no compiler).
  docker run --rm -v "$PROBE_DIR":/probe "$PROBE_JDK_IMAGE" javac /probe/TlsProviderProbe.java \
    || fail "probe compile failed"
  [ -f "$PROBE_DIR/TlsProviderProbe.class" ] || fail "probe class not produced — failing closed"
}

# Run the compiled probe in the IMAGE's own JVM. Any args passed to this function
# are inserted as `docker run` OPTIONS before the image ref (e.g. --read-only), so
# callers can vary the runtime sandbox without duplicating the invocation. Prints
# the probe output (indented) and returns non-zero unless it reports
# OpenSsl.isAvailable=true (a container that will not start also returns non-zero).
run_provider_probe() {
  ensure_probe_compiled
  local out rc=0
  out="$(docker run --rm "$@" --entrypoint "$IN_IMAGE_JAVA" -v "$PROBE_DIR":/probe:ro \
        "$IMAGE_TAG" -cp "$IN_IMAGE_JAR:/probe" TlsProviderProbe 2>&1)" || rc=$?
  echo "$out" | sed 's/^/    /'
  [ "$rc" -eq 0 ] || return 1
  echo "$out" | grep -q 'OpenSsl.isAvailable=true'
}

# ── Assert the native provider actually LOADS in the image's own JVM ────────────
# NOTE: the assembly jar bundles the per-platform tcnative natives, so Netty loads
# the native FROM THE JAR — this proves the image reproduces source=download's real
# native-TLS behaviour, but does NOT isolate the /usr/lib .so this image downloads
# (that would stay true even against a wrong-version standalone .so). The download
# is covered separately: verify-tcnative-stamp.sh + the Dockerfile's empty-check
# guard the stamp, and assert_so_present asserts the arch-correct .so is present.
# The /usr/lib copy's actual PURPOSE — the read-only-rootfs fallback — is exercised
# by assert_native_provider_readonly below, not here (with a writable /tmp Netty
# just extracts the native from the jar and never touches /usr/lib).
assert_native_provider() {
  echo "--- :lock: asserting the native TLS provider loads in the image JVM (OpenSsl.isAvailable)"
  run_provider_probe \
    || fail "native TLS provider did not load (OpenSsl.isAvailable=false) — the .so is missing, wrong-arch, or a version mismatch, failing closed"
  echo "    :white_check_mark: native TLS provider is available in the image JVM"
}

# ── Assert native TLS survives a READ-ONLY rootfs — the /usr/lib copy's whole job ─
# THIS is the guard that protects the `COPY ... /usr/lib/` line. Under `docker run
# --read-only` (deliberately WITHOUT `--tmpfs /tmp`), Netty's NativeLibraryLoader
# cannot extract the bundled native to a read-only /tmp, so BoringSSL native TLS
# survives ONLY because System.loadLibrary finds /usr/lib/libnetty_tcnative_linux_*.so
# on the default java.library.path. Remove or break that copy and this — and only
# this — goes red; assert_so_present (file existence) and assert_native_provider
# (jar-bundled native on a writable /tmp) both stay green, which is exactly how a
# refactor could silently downgrade --read-only deployments to the JDK provider.
# We add NO writable path: the probe needs none (the JVM tolerates a read-only /tmp
# for hsperfdata), and adding `--tmpfs /tmp` would re-enable jar extraction and
# defeat the test.
assert_native_provider_readonly() {
  echo "--- :lock: asserting native TLS still loads under a READ-ONLY rootfs (docker run --read-only, no --tmpfs /tmp) — this is what exercises the /usr/lib tcnative .so fallback"
  run_provider_probe --read-only \
    || fail "native TLS provider did NOT load under --read-only (OpenSsl.isAvailable=false) — the /usr/lib tcnative .so fallback is broken or missing; read-only-rootfs deployments would silently downgrade to the JDK provider, failing closed"
  echo "    :white_check_mark: native TLS provider is available under --read-only — the /usr/lib .so fallback works"
}

# ── Assert the container starts, is healthy, and serves a real TLS handshake ────
assert_starts_and_serves_tls() {
  echo "--- :whale: asserting the container starts, is healthy, and serves TLS"
  local cname port
  cname="docker-build-verify-$$"
  docker run -d --name "$cname" -p 0:1080 "$IMAGE_TAG" >/dev/null || fail "docker run failed"
  CLEANUP+=( "container:$cname" )
  # Readiness via the image's own HealthCheck (http status endpoint).
  local healthy=0 attempt
  for attempt in $(seq 1 60); do
    : "$attempt"
    if docker exec "$cname" "$IN_IMAGE_JAVA" -cp "$IN_IMAGE_JAR" org.mockserver.cli.HealthCheck >/dev/null 2>&1; then
      healthy=1; break
    fi
    sleep 1
  done
  [ "$healthy" -eq 1 ] || { docker logs "$cname" 2>&1 | tail -30 >&2; fail "container never became healthy — failing closed"; }
  echo "    :white_check_mark: container healthy (HealthCheck returned 200)"
  # Real TLS handshake on the mapped port (MockServer serves HTTP+HTTPS on one port).
  port="$(docker port "$cname" 1080/tcp | head -n1 | sed 's/.*://')"
  [ -n "$port" ] || fail "could not determine mapped port — failing closed"
  local code
  code="$(curl -sk -o /dev/null -w '%{http_code}' -X PUT "https://localhost:${port}/mockserver/status" --max-time 15 || echo 000)"
  if [ "$code" != "200" ]; then
    docker logs "$cname" 2>&1 | tail -30 >&2
    fail "TLS request to /mockserver/status returned HTTP '$code' (expected 200) — TLS did not serve, failing closed"
  fi
  echo "    :white_check_mark: TLS handshake + PUT /mockserver/status returned 200"
}

# ══ 1. Build the image (unless asserting against an existing one) ═══════════════
if [ "${DOCKER_VERIFY_SKIP_BUILD:-}" = "true" ]; then
  echo "--- :fast_forward: DOCKER_VERIFY_SKIP_BUILD=true — asserting against existing image '$IMAGE_TAG' (no build, no derive)"
else
  # Ensure both stamped jars exist, then run the fast inner gate over them.
  JAR="$(locate_or_build_jars)"

  echo "--- :zap: inner gate: two-jar tcnative stamp derive check"
  "$STEPS_DIR/verify-tcnative-stamp.sh"

  echo "--- :package: staging $(basename "$JAR") into the build context"
  cp "$JAR" "$DOCKER_CONTEXT/mockserver-netty-jar-with-dependencies.jar"
  CLEANUP+=( "file:$DOCKER_CONTEXT/mockserver-netty-jar-with-dependencies.jar" )

  ca_state="$("$REPO_ROOT/docker/ensure-ca-bundle.sh" "$DOCKER_CONTEXT")"
  if [ "$ca_state" = "created" ]; then
    CLEANUP+=( "file:$DOCKER_CONTEXT/ca-bundle.pem" )
  fi

  echo "--- :docker: building source=copy image (TARGETARCH=$TARGETARCH) → $IMAGE_TAG"
  docker build --no-cache \
    --build-arg source=copy \
    --build-arg "TARGETARCH=$TARGETARCH" \
    -t "$IMAGE_TAG" \
    "$DOCKER_CONTEXT" \
    || fail "docker build failed"
  # Remove the image we built on exit unless the caller wants to keep it.
  [ "${DOCKER_VERIFY_KEEP_IMAGE:-}" = "true" ] || CLEANUP+=( "image:$IMAGE_TAG" )
fi

# ══ 2. Runtime assertions ═══════════════════════════════════════════════════════
assert_so_present
assert_native_provider
assert_native_provider_readonly
assert_starts_and_serves_tls

echo "--- :white_check_mark: docker build verify PASSED (image builds, native tcnative loads incl. under --read-only, TLS serves)"
