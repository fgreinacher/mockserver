#!/usr/bin/env bash

set -euo pipefail

log_debug() {
    echo "[$(date -u +"%Y-%m-%d %H:%M:%S UTC")] $*"
}

log_debug "=== BUILD START ==="
log_debug "User: $(whoami)"
log_debug "Memory: $(free -h 2>/dev/null | grep Mem || echo 'free command not available')"
log_debug "Disk: $(df -h /build/mockserver 2>/dev/null | tail -1 || echo 'df command not available')"

cd mockserver

echo
java -version
echo
./mvnw -version
echo
export MAVEN_OPTS="${MAVEN_OPTS:-} -Xms2048m -Xmx6144m"

if test "${BUILDKITE_BRANCH:-}" = "master"; then
    echo "BRANCH: MASTER"
else
    echo "BRANCH: ${CURRENT_BRANCH:-}"
fi

log_debug "Starting Maven build (foreground)..."
set +e
# -Djava.security.egd is supplied via .mvn/maven.config (file:/dev/./urandom)
# -B --no-transfer-progress: CI runs on a non-TTY log; without batch mode Maven's
# interactive transfer-progress monitor emits one dot per line, flooding the build
# log. These flags are applied here (CI-scoped) rather than in .mvn/maven.config so
# local developer `./mvnw` keeps its live download progress.
./mvnw -B --no-transfer-progress -T 1C clean install ${1:-} -Dmockserver.testOutput=quiet -DredirectTestOutputToFile=true -Dmockserver.testLogLevel=INFO "-Dmockserver.testArgLine=-Dmockserver.maxLogEntries=10000 -Dmockserver.maxExpectations=5000"
MVN_EXIT=$?
log_debug "Maven exited with code=$MVN_EXIT"

# ──────────────────────────────────────────────────────────────────────
# Whole-reactor configuration-reachability guard (ConfigurationCallSiteGuardTest).
#
# The guard scans compiled .class output across EVERY reactor module to prove no
# enforcement site reads a configuration value only from the static
# ConfigurationProperties store (such a read is unreachable from
# PUT /mockserver/configuration even though the value round-trips through the DTO).
# Because it scans built classes, its coverage is only complete once the WHOLE
# reactor has been compiled — and under `-T 1C` netty's own test phase runs long
# before the modules downstream of it (junit-rule, junit-jupiter, spring, async,
# blob-*, state-infinispan, testcontainers, k8s-webhook) are built. Running it in
# netty's test phase therefore silently scanned only a subset; it is excluded from
# that phase (mockserver-netty/pom.xml) and run HERE instead, after the reactor
# `clean install` above has populated every module's target/classes in this same
# container. The test itself asserts every module it SHOULD cover was actually
# scanned, so an incomplete tree fails loudly rather than narrowing scope silently.
#
# Only run it when the reactor build passed (a failed build is already red), and
# fold its exit into MVN_EXIT so a guard violation turns the whole build red.
# (still inside the `set +e` region opened before the reactor build above, so a
# guard failure is captured in GUARD_EXIT rather than aborting the script.)
if [ "$MVN_EXIT" -eq 0 ]; then
    log_debug "Running whole-reactor configuration-callsite guard..."
    # jacoco:prepare-agent is REQUIRED, not decorative: surefire's <argLine> here is the
    # late-evaluated `@{argLine}` form, and nothing else in this standalone invocation defines
    # argLine. Without prepare-agent the literal string `@{argLine}` is handed to the JVM and the
    # fork dies with `Error: could not open '{argLine}'` before a single test runs — which reads
    # as a build failure, not as a guard verdict. (Seen for real on build 2297.)
    ./mvnw -B --no-transfer-progress -pl mockserver-netty jacoco:prepare-agent surefire:test@configuration-callsite-guard \
        -Dmockserver.testOutput=quiet -DredirectTestOutputToFile=true -Dmockserver.testLogLevel=INFO
    GUARD_EXIT=$?
    log_debug "configuration-callsite guard exited with code=$GUARD_EXIT"
    if [ "$GUARD_EXIT" -ne 0 ]; then
        MVN_EXIT=$GUARD_EXIT
    fi
fi

# ──────────────────────────────────────────────────────────────────────
# Build the relocated examples/ suite standalone.
#
# examples/java was removed as a `<module>` of the mockserver reactor
# (mockserver/pom.xml) so that /mockserver is a self-contained Maven directory:
# a module path that escaped Dependabot's directory:"/mockserver" scope made
# Dependabot abort EVERY grouped core update with "No pom.xml!". The examples
# must still stay compiled AND tested, so they are built here — in the SAME
# container, immediately after the reactor `install` that populated ~/.m2 with
# the SNAPSHOT artifacts they depend on (mockserver-netty-no-dependencies,
# mockserver-client-java-no-dependencies, mockserver-testing) and the parent POM
# they inherit (../../mockserver/pom.xml). This ordering guarantee is exactly why
# the invocation lives here rather than in a separate Buildkite step, which would
# not share this container's freshly-installed local repo.
#
# Only build the examples when the reactor build itself passed, and fold the
# examples exit code into MVN_EXIT so an examples compile/test break turns the
# whole build red (the silent-stop this guards against). Mirrors the reactor's
# test-output flags for a consistent, quiet CI log.
if [ "$MVN_EXIT" -eq 0 ]; then
    log_debug "Building relocated examples/ suite standalone (mvn -f ../examples/java/pom.xml)..."
    ./mvnw -B --no-transfer-progress -f ../examples/java/pom.xml clean install ${1:-} -Dmockserver.testOutput=quiet -DredirectTestOutputToFile=true -Dmockserver.testLogLevel=INFO "-Dmockserver.testArgLine=-Dmockserver.maxLogEntries=10000 -Dmockserver.maxExpectations=5000"
    EXAMPLES_EXIT=$?
    log_debug "examples/ build exited with code=$EXAMPLES_EXIT"
    if [ "$EXAMPLES_EXIT" -ne 0 ]; then
        MVN_EXIT=$EXAMPLES_EXIT
    fi
fi
set -e

trap - SIGTERM SIGINT

# Bundle the per-class jacoco HTML reports into a single tarball so Buildkite's
# artifact_paths can upload one file per build instead of ~28000 small HTML
# pages (which trips the 5000-artifact-per-job cap). The XML data files are
# uploaded separately for downstream tooling.
log_debug "Bundling jacoco HTML reports..."
cd /build/mockserver 2>/dev/null || cd "$(dirname "$0")/../mockserver"
find . -type d \( -name jacoco -o -name jacoco-it \) -path '*/target/site/*' > /tmp/jacoco-dirs.txt 2>/dev/null || true
if [[ -s /tmp/jacoco-dirs.txt ]]; then
    tar czf jacoco-html-reports.tar.gz -T /tmp/jacoco-dirs.txt 2>/dev/null \
      && log_debug "  jacoco-html-reports.tar.gz: $(du -h jacoco-html-reports.tar.gz | cut -f1)" \
      || log_debug "  tar failed - skipping HTML bundle"
fi
rm -f /tmp/jacoco-dirs.txt

log_debug "=== BUILD END (exit $MVN_EXIT) ==="
exit $MVN_EXIT
