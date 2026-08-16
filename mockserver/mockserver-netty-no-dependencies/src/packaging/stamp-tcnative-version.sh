#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Stamp the RESOLVED netty-tcnative version into the shaded server jar.
#
# WHY
# ---
# netty-tcnative is a JNI library: a pure-Java "classes" jar (shaded into the
# server uber-jar) plus a per-platform native .so that MUST be the exact same
# version, or TLS fails at runtime (UnsatisfiedLinkError / handshake failure).
#
# The uber-jar deliberately ships NO natives — they cannot be relocated by the
# shade plugin and would clash with a user's own Netty when the jar is embedded
# (see #1778 / mockserver-netty-no-dependencies shade filter). So the Docker
# images fetch the matching native .so separately at build time.
#
# For that download to be the SAME version as the shaded classes — with no
# human keeping two literals in sync — the Dockerfiles read the version from
# THIS stamped file inside the jar they already have. This script writes it,
# taking the version straight from the Maven-resolved dependency (governed by
# the imported netty-bom), so it can never drift from the classes.
#
# USAGE
#   stamp-tcnative-version.sh <resolved-tcnative-jar-path> <output-dir>
#
#   <resolved-tcnative-jar-path>  the local-repo path of the resolved
#     io.netty:netty-tcnative-boringssl-static jar, e.g. exposed by
#     maven-dependency-plugin's `properties` goal as the property
#     `io.netty:netty-tcnative-boringssl-static:jar`. The Maven local-repo
#     layout is `.../<artifactId>/<version>/<file>.jar`, so the version is the
#     name of the jar's parent directory.
#   <output-dir>  the module output directory (target/classes); the stamp is
#     written to <output-dir>/META-INF/mockserver-tcnative.version and shaded in.
#
# Fails closed: a missing/blank path, a path that does not resolve to a
# plausible version, or an unwritable output dir aborts the build rather than
# producing a jar that Docker cannot pair with a native.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

JAR_PATH="${1:-}"
OUTPUT_DIR="${2:-}"

if [ -z "$JAR_PATH" ] || [ -z "$OUTPUT_DIR" ]; then
  echo "stamp-tcnative-version: usage: $0 <resolved-tcnative-jar-path> <output-dir>" >&2
  exit 1
fi

if [ ! -f "$JAR_PATH" ]; then
  echo "stamp-tcnative-version: resolved tcnative jar does not exist: '$JAR_PATH' — cannot derive version, failing closed" >&2
  exit 1
fi

# .../netty-tcnative-boringssl-static/<version>/netty-...jar  ->  <version>
VERSION="$(basename "$(dirname "$JAR_PATH")")"

# Sanity: a Netty/tcnative version looks like 2.0.81.Final (digits/dots then a
# qualifier). Refuse anything that does not, so a layout surprise can never
# stamp a bogus value that Docker would then try to download.
if ! printf '%s' "$VERSION" | grep -qE '^[0-9]+(\.[0-9]+)+\.(Final|RELEASE|GA)$'; then
  echo "stamp-tcnative-version: derived version '$VERSION' from '$JAR_PATH' does not look like a tcnative version — failing closed" >&2
  exit 1
fi

DEST_DIR="$OUTPUT_DIR/META-INF"
mkdir -p "$DEST_DIR"
printf '%s\n' "$VERSION" > "$DEST_DIR/mockserver-tcnative.version"

echo "stamp-tcnative-version: stamped netty-tcnative version '$VERSION' -> $DEST_DIR/mockserver-tcnative.version"
