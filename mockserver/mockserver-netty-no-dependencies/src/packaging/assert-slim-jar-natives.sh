#!/usr/bin/env bash
#
# Fail the build if a classified slim fat jar does not carry exactly the native
# libraries its architecture needs.
#
# WHY THIS EXISTS
# ---------------
# The slim assemblies keep natives by an "*<arch>.so" SUFFIX include. Maven's
# assembly plugin does NOT fail when an <include> matches nothing, so a future
# netty rename - or an edit narrowing that pattern - would silently produce a slim
# jar with NO native libraries. Nothing would look wrong: the jar builds, installs
# and starts. epoll would quietly fall back to NIO and tcnative to the JDK SSL
# provider - performance and behaviour regressions that announce themselves nowhere.
#
# docker/Dockerfile's jarprep stage already guards its equivalent trim with a
# grep-or-exit. This is the same guard for the PUBLISHED artifacts, so the two
# cannot drift apart.
#
# The suffix form is deliberate and must never be narrowed to "linux_<arch>": the
# epoll library is libnetty_transport_native_epoll_<arch>.so with NO "linux_" in
# its name, so a linux_-anchored filter drops precisely the one that matters most.
set -euo pipefail

JAR="${1:?usage: assert-slim-jar-natives.sh <jar> <arch>}"
ARCH="${2:?usage: assert-slim-jar-natives.sh <jar> <arch>}"

[ -f "$JAR" ] || { echo "ERROR: slim jar not found: $JAR" >&2; exit 1; }

NATIVES=$(unzip -Z1 "$JAR" 'META-INF/native/*' 2>/dev/null | grep -v '/$' | sed 's#.*/##' | sort)
COUNT=$(printf '%s\n' "$NATIVES" | grep -c . || true)

if [ "$COUNT" -ne 3 ]; then
  echo "ERROR: $(basename "$JAR") carries $COUNT native(s), expected 3" >&2
  echo "  found: ${NATIVES:-<none>}" >&2
  echo "  A slim jar with the wrong natives still starts and serves, but epoll degrades" >&2
  echo "  to NIO and tcnative to JDK SSL - silently. Check the <include> in the descriptor." >&2
  exit 1
fi

for required in tcnative quiche42 transport_native_epoll; do
  printf '%s\n' "$NATIVES" | grep -q "${required}.*${ARCH}\.so$" || {
    echo "ERROR: $(basename "$JAR") is missing the ${required} native for ${ARCH}" >&2
    echo "  found: $NATIVES" >&2
    exit 1
  }
done

printf '%s\n' "$NATIVES" | while read -r n; do
  case "$n" in
    *"${ARCH}".so) : ;;
    *) echo "ERROR: $(basename "$JAR") carries a foreign native: $n" >&2; exit 1 ;;
  esac
done

echo "OK: $(basename "$JAR") carries exactly the ${ARCH} natives:"
printf '  %s\n' $NATIVES
