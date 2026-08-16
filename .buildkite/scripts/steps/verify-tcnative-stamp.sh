#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Fail closed when a Docker-consumed server jar is missing the netty-tcnative
# version stamp the Dockerfiles derive their native .so download from.
#
# WHY
# ---
# netty-tcnative is a JNI library: pure-Java "classes" (shaded into the server
# uber-jar) plus a per-platform native .so that MUST be the EXACT same version or
# TLS fails at runtime. The uber-jar ships no natives, so the Docker images fetch
# the matching .so at build time, deriving the version from a stamp baked into the
# jar itself — `META-INF/mockserver-tcnative.version` (see
# mockserver-netty-no-dependencies/src/packaging/stamp-tcnative-version.sh).
#
# TWO jars carry that stamp, each feeding a different Docker `source` mode:
#   * the maven-shade  mockserver-netty-no-dependencies jar         (source=copy)
#   * the maven-assembly mockserver-netty-<ver>-jar-with-dependencies.jar
#                                                                   (source=download, the default)
# The stamp was once added to only ONE of the two — silently breaking the default
# download image for every release. Nothing built either image on that change, so
# only human review caught it. This guard is the automated backstop: it proves,
# over the REAL built artifacts, that BOTH stamps exist, are well-formed, agree
# with each other, and agree with the Maven-resolved tcnative version.
#
# WHAT IT ASSERTS (fails closed — exit 1 — on any):
#   * each stamped jar is present exactly once (a missing build output is a
#     failure, never a silent skip);
#   * each jar actually contains META-INF/mockserver-tcnative.version;
#   * each stamp is non-empty and matches a tcnative version (e.g. 2.0.81.Final);
#   * the two stamps are identical to each other;
#   * both equal the version Maven resolves for
#     io.netty:netty-tcnative-boringssl-static (the netty-bom governed truth the
#     stamp itself is derived from).
#
# HOW IT OBTAINS THE JARS
#   It does NOT build them. It consumes the two stamped jars that a normal reactor
#   `clean install` produces under mockserver/mockserver-netty*/target (the
#   assembly jar-with-dependencies is built by assembly:single at the `package`
#   phase; skipAssembly defaults to false), so it runs in seconds and cannot be
#   starved by agent capacity. If a jar is absent the guard FAILS — that means the
#   build did not produce it, which is exactly the condition worth failing on.
#
# WHERE IT ACTUALLY RUNS (keep this accurate)
#   * The developer pre-commit gate — .opencode/rules/commit-workflow.md runs it as
#     the fast inner tcnative-stamp check before a commit.
#   * As the inner gate of docker-build-verify.sh (which runs it, then builds the
#     image and proves the native .so loads at runtime).
#   It is NOT currently wired into any Buildkite pipeline: it is absent from
#   pipeline-java.yml, and the docker-build-verify.sh CI step was removed from
#   pipeline-container-tests.yml (see the note there). Re-landing CI stamp coverage
#   is tracked in that pipeline file. If you wire it into pipeline-java.yml, it must
#   run AFTER the install that produces the jars (in-job, after `:maven: build`, so
#   the jars and a warm ~/.m2 are on the same agent — a separate Buildkite step does
#   NOT share the build agent's filesystem) — update this note when you do.
#
# SELF-TEST
#   Point STAMP_ASSEMBLY_JAR (or STAMP_SHADED_JAR) at a jar with no stamp — or an
#   empty/garbage file — and the guard goes red, exactly like the certificate
#   guard's CERT_EXPIRY_HARD_FAIL_DAYS override. STAMP_EXPECTED_VERSION overrides
#   the Maven resolution (self-test / environments without the reactor).
#
# Requires: unzip, grep, sed, sort, and (unless STAMP_EXPECTED_VERSION is set) the
# Maven wrapper under mockserver/. Runs directly on the agent (no Docker).
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

REACTOR_DIR="$REPO_ROOT/mockserver"

# A well-formed tcnative version, e.g. 2.0.81.Final — mirror the regex the stamp
# script itself enforces so a value it would refuse to write can never pass here.
VERSION_RE='^[0-9]+(\.[0-9]+)+\.(Final|RELEASE|GA)$'

errors=0

echo "--- :lock: verifying netty-tcnative version stamp in the Docker-consumed server jars"

# ── Locate exactly one jar for a glob, excluding -sources / original- siblings ──
# Prints the resolved path on success; on 0 or >1 matches prints nothing and
# returns non-zero so the caller fails closed.
locate_one_jar() {
  local glob="$1" f matches=()
  shopt -s nullglob
  for f in $glob; do
    case "$f" in
      *-sources.jar) continue ;;
      */original-*) continue ;;
    esac
    matches+=( "$f" )
  done
  shopt -u nullglob
  if [ "${#matches[@]}" -ne 1 ]; then
    return 1
  fi
  printf '%s\n' "${matches[0]}"
}

# Report which Maven execution path this environment provides, so a failure can be
# diagnosed precisely instead of guessed:
#   host   — a JDK ('java') AND the mockserver mvnw are present on the host
#   docker — no host JDK, but the `docker` CLI is available for the
#            mockserver/mockserver:maven image fallback (run-in-docker.sh)
#   none   — neither: Maven cannot be run at all
mvn_mode() {
  if command -v java >/dev/null 2>&1 && [ -x "$REACTOR_DIR/mvnw" ]; then
    echo host
  elif command -v docker >/dev/null 2>&1; then
    echo docker
  else
    echo none
  fi
}

# Run `mvn dependency:list` for the tcnative artifact and echo its raw stdout.
# Uses host mvnw when a JDK is present; otherwise runs inside the pinned
# mockserver/mockserver:maven image via run-in-docker.sh (CI agents run Maven in
# that image, not on the raw agent). Output is captured from stdout — no
# -DoutputFile — so it works identically whether or not the temp path is mounted
# into a container. stderr is deliberately NOT swallowed here: the caller redirects
# it to a temp file so a Maven failure can be reported VERBATIM (exit code + real
# stderr) rather than misdiagnosed as a missing image.
mvn_tcnative_list() {
  local mvn=(-B --no-transfer-progress -pl mockserver-netty
             dependency:list -DincludeArtifactIds=netty-tcnative-boringssl-static)
  if command -v java >/dev/null 2>&1 && [ -x "$REACTOR_DIR/mvnw" ]; then
    ( cd "$REACTOR_DIR" && ./mvnw "${mvn[@]}" )
  else
    "$REPO_ROOT/.buildkite/scripts/run-in-docker.sh" \
      -i mockserver/mockserver:maven -w /build/mockserver --cache maven \
      -- ./mvnw "${mvn[@]}"
  fi
}

# ── Resolve the authoritative tcnative version via Maven (netty-bom governed) ──
resolve_expected_version() {
  if [ -n "${STAMP_EXPECTED_VERSION:-}" ]; then
    printf '%s\n' "$STAMP_EXPECTED_VERSION"
    return 0
  fi

  # Distinguish the genuinely-different failure modes instead of blaming the image
  # for all of them (which sent a past investigation down the wrong path):
  #   1. no way to run Maven at all (no host JDK AND no Docker fallback);
  #   2. Maven RAN and FAILED — report its real exit code + stderr verbatim;
  #   3. Maven ran and succeeded but produced no parseable version.
  local mode
  mode="$(mvn_mode)"
  if [ "$mode" = "none" ]; then
    echo "+++ :bangbang: cannot resolve the authoritative netty-tcnative version: no host JDK ('java' + mockserver/mvnw) AND no 'docker' for the mockserver/mockserver:maven fallback — there is no way to run Maven in this environment. (Set STAMP_EXPECTED_VERSION to bypass Maven resolution.) Failing closed" >&2
    return 1
  fi

  local out err rc=0
  err="$(mktemp)"
  if out="$(mvn_tcnative_list 2>"$err")"; then
    rm -f "$err"
  else
    rc=$?
    {
      echo "+++ :bangbang: Maven ran via the '${mode}' path but 'mvn dependency:list' FAILED (exit ${rc}) while resolving netty-tcnative — this is a Maven/reactor failure, NOT a missing image or missing jar. Maven's own stderr (last 20 lines):"
      sed 's/^/    | /' "$err" | tail -20
    } >&2
    rm -f "$err"
    return 1
  fi

  # Strip ANSI, keep tcnative lines, pull the version token, dedupe. The base
  # artifact and every per-platform classifier share one bom-governed version, so
  # a healthy resolution yields exactly one distinct value.
  local version
  version="$(printf '%s\n' "$out" \
    | sed -E 's/\x1b\[[0-9;]*m//g' \
    | grep 'netty-tcnative-boringssl-static' \
    | grep -oE '[0-9]+(\.[0-9]+)+\.(Final|RELEASE|GA)' \
    | sort -u)"
  if [ -z "$version" ]; then
    echo "+++ :bangbang: Maven ran via the '${mode}' path and succeeded, but no netty-tcnative version could be parsed from its 'dependency:list' output — failing closed" >&2
    return 1
  fi
  if [ "$(printf '%s\n' "$version" | wc -l | tr -d ' ')" != "1" ]; then
    echo "+++ :bangbang: Maven resolved MULTIPLE netty-tcnative versions ($(printf '%s' "$version" | tr '\n' ' ')) — the netty-bom governance is broken, failing closed" >&2
    return 1
  fi
  printf '%s\n' "$version"
}

# ── Extract + validate the stamp inside one jar; echoes the stamp on success ────
read_stamp() {
  local label="$1" jar="$2" stamp
  if [ ! -f "$jar" ]; then
    echo "+++ :bangbang: ${label}: jar not found at '${jar}' — the build produced no artifact to check, failing closed" >&2
    return 1
  fi
  if ! stamp="$(unzip -p "$jar" META-INF/mockserver-tcnative.version 2>/dev/null)"; then
    echo "+++ :bangbang: ${label}: '${jar}' has no META-INF/mockserver-tcnative.version entry — the stamp is missing, failing closed" >&2
    return 1
  fi
  stamp="$(printf '%s' "$stamp" | tr -d '[:space:]')"
  if [ -z "$stamp" ]; then
    echo "+++ :bangbang: ${label}: META-INF/mockserver-tcnative.version is EMPTY in '${jar}' — Docker would derive a blank version, failing closed" >&2
    return 1
  fi
  if ! printf '%s' "$stamp" | grep -qE "$VERSION_RE"; then
    echo "+++ :bangbang: ${label}: stamp '${stamp}' in '${jar}' is not a well-formed tcnative version — failing closed" >&2
    return 1
  fi
  printf '%s\n' "$stamp"
}

# ── Resolve the authoritative version ──────────────────────────────────────────
EXPECTED=""
if EXPECTED="$(resolve_expected_version)"; then
  echo "    :white_check_mark: Maven-resolved netty-tcnative version: ${EXPECTED}"
else
  errors=$(( errors + 1 ))
fi

# ── Locate the two stamped jars ────────────────────────────────────────────────
ASSEMBLY_JAR="${STAMP_ASSEMBLY_JAR:-}"
if [ -z "$ASSEMBLY_JAR" ]; then
  if ! ASSEMBLY_JAR="$(locate_one_jar 'mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar')"; then
    echo "+++ :bangbang: could not locate exactly one mockserver-netty-*-jar-with-dependencies.jar under mockserver/mockserver-netty/target — the assembly (source=download) artifact is missing or ambiguous, failing closed" >&2
    errors=$(( errors + 1 ))
  fi
fi

SHADED_JAR="${STAMP_SHADED_JAR:-}"
if [ -z "$SHADED_JAR" ]; then
  if ! SHADED_JAR="$(locate_one_jar 'mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar')"; then
    echo "+++ :bangbang: could not locate exactly one mockserver-netty-no-dependencies-*.jar under mockserver/mockserver-netty-no-dependencies/target — the shaded (source=copy) artifact is missing or ambiguous, failing closed" >&2
    errors=$(( errors + 1 ))
  fi
fi

# ── Read + validate each stamp ─────────────────────────────────────────────────
ASSEMBLY_STAMP=""
if [ -n "$ASSEMBLY_JAR" ]; then
  if ASSEMBLY_STAMP="$(read_stamp 'assembly jar-with-dependencies (source=download)' "$ASSEMBLY_JAR")"; then
    echo "    :white_check_mark: assembly jar stamp: ${ASSEMBLY_STAMP}  (${ASSEMBLY_JAR#"$REPO_ROOT"/})"
  else
    errors=$(( errors + 1 ))
  fi
fi

SHADED_STAMP=""
if [ -n "$SHADED_JAR" ]; then
  if SHADED_STAMP="$(read_stamp 'shaded no-dependencies jar (source=copy)' "$SHADED_JAR")"; then
    echo "    :white_check_mark: shaded  jar stamp: ${SHADED_STAMP}  (${SHADED_JAR#"$REPO_ROOT"/})"
  else
    errors=$(( errors + 1 ))
  fi
fi

# ── Cross-checks: the two jars agree, and both match the resolved version ───────
if [ -n "$ASSEMBLY_STAMP" ] && [ -n "$SHADED_STAMP" ]; then
  if [ "$ASSEMBLY_STAMP" != "$SHADED_STAMP" ]; then
    echo "+++ :bangbang: the two stamped jars DISAGREE: assembly='${ASSEMBLY_STAMP}' vs shaded='${SHADED_STAMP}' — the source=download and source=copy images would fetch different natives, failing closed" >&2
    errors=$(( errors + 1 ))
  else
    echo "    :white_check_mark: both jars carry the same stamp"
  fi
fi

if [ -n "$EXPECTED" ]; then
  for pair in "assembly:${ASSEMBLY_STAMP}" "shaded:${SHADED_STAMP}"; do
    which="${pair%%:*}"
    got="${pair#*:}"
    [ -n "$got" ] || continue
    if [ "$got" != "$EXPECTED" ]; then
      echo "+++ :bangbang: ${which} jar stamp '${got}' != Maven-resolved '${EXPECTED}' — the baked stamp has drifted from the resolved tcnative version, failing closed" >&2
      errors=$(( errors + 1 ))
    fi
  done
  if [ "$ASSEMBLY_STAMP" = "$EXPECTED" ] && [ "$SHADED_STAMP" = "$EXPECTED" ]; then
    echo "    :white_check_mark: both stamps match the Maven-resolved version"
  fi
fi

echo "--- :bar_chart: tcnative stamp summary: expected='${EXPECTED:-?}' assembly='${ASSEMBLY_STAMP:-?}' shaded='${SHADED_STAMP:-?}' errors=${errors}"

if [ "$errors" -gt 0 ]; then
  echo "+++ :bangbang: netty-tcnative stamp guard FAILED with ${errors} error(s)" >&2
  exit 1
fi

echo "--- :white_check_mark: netty-tcnative stamp guard PASSED"
