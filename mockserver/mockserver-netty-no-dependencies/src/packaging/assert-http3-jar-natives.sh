#!/usr/bin/env bash
#
# Fail the build if the HTTP/3 classifier jar does not carry the QUIC natives.
#
# WHY THIS EXISTS
# ---------------
# This is the other half of assert-slim-jar-natives.sh. Since the QUIC natives were
# split into their own classifier, the default and slim assemblies EXCLUDE
# "META-INF/native/*quiche*" and only -jar-with-dependencies-http3 carries them.
# That makes the http3 jar the single artifact where HTTP/3 can actually work, and
# leaves its native payload unguarded: if the exclude were ever copied into the
# http3 descriptor, or the netty QUIC artifact were renamed or dropped from the
# dependency tree, the assembly would still build and publish. MockServer would
# start, accept the http3Port configuration, and then fail to load the QUIC
# provider at runtime - the exact silent-degradation class of bug the sibling
# guard exists to prevent, just on the other side of the split.
#
# Maven's assembly plugin does not fail when an <include> matches nothing, so
# nothing upstream of this script would notice.
#
# The expected set is every platform netty publishes a quiche binary for. It is
# spelled out rather than counted so that a DROPPED platform is named in the
# failure, not just totalled.
set -euo pipefail

JAR="${1:?usage: assert-http3-jar-natives.sh <jar>}"

[ -f "$JAR" ] || { echo "ERROR: http3 jar not found: $JAR" >&2; exit 1; }

NATIVES=$(unzip -Z1 "$JAR" 'META-INF/native/*' 2>/dev/null | grep -v '/$' | sed 's#.*/##' | sort)

EXPECTED="libnetty_quiche42_linux_aarch_64.so
libnetty_quiche42_linux_x86_64.so
libnetty_quiche42_osx_aarch_64.jnilib
libnetty_quiche42_osx_x86_64.jnilib
netty_quiche42_windows_x86_64.dll"

MISSING=""
while read -r expected; do
  [ -n "$expected" ] || continue
  printf '%s\n' "$NATIVES" | grep -qx "$expected" || MISSING="${MISSING}${expected}"$'\n'
done <<EOF
$EXPECTED
EOF

if [ -n "$MISSING" ]; then
  echo "ERROR: $(basename "$JAR") is missing QUIC native(s):" >&2
  printf '  %s\n' $MISSING >&2
  echo "  This jar is the ONLY artifact that carries the QUIC natives, so without them" >&2
  echo "  a server configured with http3Port starts and then fails to load the QUIC" >&2
  echo "  provider at runtime. Check the http3 assembly descriptor still has no" >&2
  echo "  META-INF/native exclude, and that netty's QUIC artifact is still a dependency." >&2
  echo "  found: ${NATIVES:-<none>}" >&2
  exit 1
fi

echo "OK: $(basename "$JAR") carries the QUIC natives for all supported platforms:"
printf '%s\n' "$NATIVES" | grep 'quiche' | sed 's/^/  /'
