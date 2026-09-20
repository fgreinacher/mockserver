#!/usr/bin/env bash
#
# Programme item 21 — the CONNECTION-SCALING CEILING: how many concurrent established connections a
# MockServer holds before request latency degrades. Starts a server, runs the ladder against it, stops it.
#
#   ./run-connection-ceiling.sh                 # measure, using the calibrated client ceiling below
#   ./run-connection-ceiling.sh calibrate       # find THIS box's client ceiling and what limits it
#
# -XX:-MaxFDLimit is applied ON macOS ONLY, and that asymmetry is deliberate because the flag does
# OPPOSITE things on the two platforms (both measured):
#   * macOS — the JDK sets its own RLIMIT_NOFILE to min(hard, OPEN_MAX=10240) however high `ulimit -n`
#     reads, so an un-flagged JVM (driver OR server) stops at ~9,977 connections and the "ceiling" you
#     measure is the JDK's. The flag leaves the inherited soft limit alone, so it must be paired with a
#     raised `ulimit -n`: the flag alone gives you whatever the shell had.
#   * Linux — the JDK already raises the soft limit to the HARD limit (1024 -> 1048576 measured). The
#     flag DISABLES that and leaves 1024, making the ceiling ~40x worse. Never pass it here.
# With the clamp lifted on macOS the wall moves to the ephemeral source-port range, measured on this
# laptop at 15,511 connections; spreading across four server ports reached 15,609 (ratio 1.01), so the
# source-port range is GLOBAL and more server ports do not raise the ceiling. Run `calibrate` on any new
# box rather than inheriting these numbers.
#
# Tunables (env): CEILING_MODE (h1|tls|calibrate), CEILING_LADDER, CEILING_PROBE_REQUESTS,
# CEILING_SETTLE_MS, CEILING_WARMUP_REQUESTS, CEILING_TIMEWAIT_DRAIN_MS, CEILING_CLIENT_CEILING,
# CEILING_SERVER_PORTS.
#
# The ladder must also fit TWO ADJACENT rungs in the port range: a rung's sockets sit in TIME_WAIT for
# 2*MSL (30 s on macOS) after it closes, so 8,000 followed immediately by 12,000 asks for 20,000 ports
# and fails part-way up. CEILING_TIMEWAIT_DRAIN_MS defaults to 45 s for that reason. If the rig does run
# out, the driver records the rung as `rig_valid:false` with `exhausted_by` rather than reporting a
# latency — a rig limit is a result about the rig, never a server ceiling.
#
# Requires mockserver-netty installed locally first:
#   (cd .. && ./mvnw -pl mockserver-netty -am install -DskipTests)
#
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${DIR}"

PORTS="${CEILING_SERVER_PORTS:-1080,1081,1082,1083}"
JAR="${DIR}/../mockserver-netty/target/mockserver-netty-$(cd .. && mvn -q -o help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null)-jar-with-dependencies.jar"
if [ ! -f "${JAR}" ]; then
    echo "ERROR: fat jar not found at ${JAR} — run (cd .. && ./mvnw -pl mockserver-netty -am package -DskipTests)" >&2
    exit 2
fi

mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Djacoco.skip=true
CP="target/classes:$(cat target/classpath.txt)"

# See the header: on macOS both JVMs need the flag or the server's own 10,240 descriptor cap is the
# ceiling this harness "finds"; on Linux the same flag would lower the limit instead of raising it.
FD_FLAG=()
if [ "$(uname -s)" = "Darwin" ]; then
    FD_FLAG=(-XX:-MaxFDLimit)
    ulimit -Sn 65536 2>/dev/null || true
fi

java "${FD_FLAG[@]}" -Xms1g -Xmx4g -jar "${JAR}" -serverPort "${PORTS}" -logLevel WARN > target/ceiling-server.log 2>&1 &
SERVER_PID=$!
trap 'kill "${SERVER_PID}" 2>/dev/null || true' EXIT

FIRST_PORT="${PORTS%%,*}"
for _ in $(seq 1 60); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "http://127.0.0.1:${FIRST_PORT}/mockserver/status" || true)" = "200" ]; then
        break
    fi
    sleep 1
done
# Readiness is the control-plane 200, never a listening port — MockServer accepts and then resets while
# it is still initialising, so a port probe reports ready before the server can serve.
if [ "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "http://127.0.0.1:${FIRST_PORT}/mockserver/status" || true)" != "200" ]; then
    echo "ERROR: server did not become ready on port ${FIRST_PORT}; see target/ceiling-server.log" >&2
    exit 2
fi

if [ "${1:-}" = "calibrate" ]; then
    export CEILING_MODE=calibrate
fi

# NOT `exec`: exec replaces this shell, so the EXIT trap above never runs and the server started on
# line 52 is left alive — which then makes the NEXT run fail to bind its ports, for a reason that
# looks nothing like its cause.
java "${FD_FLAG[@]}" -Xms1g -Xmx6g -cp "${CP}" \
    org.mockserver.benchmark.ConnectionCeilingBenchmark 127.0.0.1 "${PORTS}"
