#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Fail closed when a NEW "false-green" test-shape is introduced — a test or
# CI step that reports success while verifying nothing.
#
# WHY THIS EXISTS
#
# The 2026-07-21 coverage audit classified its findings by the *shape* of the
# false green — the recognisable pattern of a test or gate that passes having
# proven nothing. Those categories lived only in plan documents, so nothing
# stopped them recurring; the repository then produced roughly a dozen fresh
# instances in a single day (see docs/plans/test-coverage-gaps.md). This guard
# turns the highest-value, mechanically-checkable shapes into a standing control.
#
# It is deliberately NARROW. A noisy grep everyone learns to ignore is worse than
# nothing, so it enforces only patterns that can be pinned down precisely and that
# each caused a real, shipped false green. The heuristic categories from the audit
# (self-derived golden fixtures; a guard whose own precondition can silently fail)
# are NOT enforced here — they have no low-false-positive textual signature. See
# docs/operations/false-green-guards.md for the full rationale, the declined rules,
# and how to add an allow-list entry.
#
# RULES ENFORCED
#
#   Rule 1 — Docker-gated suite with no fail-closed CI assertion.
#     Every JUnit suite gated by
#       Assume.assumeTrue(DockerAvailability.isAvailable(...))
#     reports SKIPPED (Maven exits 0) when Docker is unusable, so a CI step that
#     only checks Maven's exit code goes green having tested nothing. AGENTS.md
#     already mandates pairing each such suite with assert-suite-ran.sh over its
#     surefire/failsafe reports. This asserts every Docker-gated suite's class is
#     named by some assert-suite-ran glob FOR THE SUITE'S OWN MAVEN MODULE — the
#     match is module-scoped, not class-name-only, so a gated suite in an unpaired
#     module cannot be reported covered merely because its class-name suffix is
#     shared by a glob belonging to a different module. It also asserts no glob is
#     left dangling (naming a suite that no longer exists).
#
#   Rule 2 — a step that mounts the Docker socket then deselects Docker tests.
#     A CI step that grants the Docker socket (run-in-docker.sh -s/--docker-socket)
#     but then runs the suite with the Docker-marked tests deselected (e.g.
#     `pytest -m "not docker"`) starts no container and passes green. This is the
#     exact defect that shipped for the python client. The contradiction — pay to
#     mount the socket, then skip everything that needs it — is the signal.
#
#   Rule 3 — a deferred-work skip that reads as green.
#     A container-integration skip helper (logTestSkip) invoked with deferral
#     language ("CI wiring is a follow-up", TODO, pending, ...) parks work that
#     was never done while the job stays green. The WAR case skipped this way for
#     months. This flags skips whose message defers work rather than declaring a
#     case genuinely N/A.
#
#   Rule 4 — a helm/k3d harness run that can silently revert to skipping.
#     The image-dependent k3d cases (helm_sidecar_injection, helm_clustered_
#     convergence, helm_jgroups_dns_ping) only fail closed on a missing image when
#     the step that runs the harness exports BOTH REQUIRE_CLUSTERED_IMAGE=true and
#     REQUIRE_WEBHOOK_IMAGE=true; delete those exports and CI silently records a
#     SKIP — green, testing nothing. This asserts every step that runs the harness
#     with helm tests active (invokes integration_tests.sh and does NOT set
#     SKIP_HELM_TESTS=true) exports both flags. Keyed on that BEHAVIOUR, not on the
#     filename helm-integration-test.sh, so a rename or a second helm-running step
#     is covered automatically and the docker-only harness step (which sets
#     SKIP_HELM_TESTS=true) is correctly exempt.
#
#   Rule 5 — a JVM-global logging side effect in mockserver-core's parallel phase.
#     LogManager.getLogManager().readConfiguration(...) performs a JVM-global
#     reset() that strips every handler from every logger; LogManager is not
#     classloader-isolated. A mockserver-core test that reaches that reset — by
#     forcing a fresh <clinit> of MockServerLogger/ConfigurationProperties via an
#     isolated classloader, or by calling the static logging setters — races the
#     log capture of any concurrently-running test, silently zeroing a capture (a
#     failing test) or falsely passing a silence assertion (a false green). Such a
#     class MUST be in the sequential-includes list in mockserver-core/pom.xml.
#     ParallelStaticStateGuardTest cannot catch this: it only checks classes
#     EXCLUDED from the parallel phase, and these are in neither list. This asserts
#     every core test source matching a (rare) global-logging signature is
#     sequential. See the signature list below for what it deliberately does NOT
#     catch (static-import call sites; other modules).
#
# ALLOW-LISTS
#
# Some Docker-gated files are legitimately NOT assert-suite-ran-paired (the probe's
# own unit test). Each allow-list entry is verified to STILL be the thing it claims
# (mirroring check-certificate-expiry.sh's expired-fixture assertion): an entry that
# names something that no longer exists, or that is no longer exempt for the reason
# claimed, FAILS the build so the allow-list can never quietly mask a regression.
#
# Requires: git, grep, sed. Runs directly on the agent (no Docker). Bash 3.2 safe.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

errors=0

# ══════════════════════════════════════════════════════════════════════
# Rule 1 — every Docker-gated JUnit suite must be assert-suite-ran-paired
# ══════════════════════════════════════════════════════════════════════

# Files that are Docker-gated but legitimately NOT paired with assert-suite-ran.
# Format: each entry is "<repo-relative .java path>". Justify every entry below.
# The rot check asserts each still exists, still references the probe, and is still
# NOT a real gated suite — so an entry can never silently mask a suite that needs
# coverage.
#
#   DockerAvailabilityTest — the probe's OWN unit test. It calls
#   DockerAvailability.isAvailable() with stubbed BooleanSuppliers to prove the
#   wrapper's try/catch behaviour; it never starts a container and carries no
#   Assume gate, so it runs in the ordinary build and needs no CI re-run assertion.
R1_ALLOWLIST=(
  "mockserver/mockserver-testing/src/test/java/org/mockserver/test/DockerAvailabilityTest.java"
)

r1_is_allowlisted() {
  local path="$1" entry
  for entry in "${R1_ALLOWLIST[@]}"; do
    [ "$path" = "$entry" ] && return 0
  done
  return 1
}

echo "--- :whale: Rule 1: Docker-gated suites must be assert-suite-ran-paired"

# Coverage patterns: the (module, class-name glob) pair from every assert-suite-ran
# report glob. assert-suite-ran arguments are single-quoted "<module-path>/target/
# <phase>-reports/TEST-<glob>.xml" tokens, each on its own line. Requiring the
# SINGLE-QUOTED "-reports/TEST-...xml" form (and dropping comment lines) is what
# distinguishes a real assertion from an example in a docstring or the failure-
# collector's own "TEST-*.xml" scan glob — extracting those would silently broaden
# coverage.
#
# The match MUST be module-scoped, not class-name-only. The <module-path> appears
# in two forms — "mockserver-blob-azure/target/..." (script runs from within
# mockserver/) and "mockserver/mockserver-netty/target/..." (from repo root) — so
# the correlation key is the Maven module DIRECTORY NAME: the last path segment
# before "/target/", which is identical in both forms. Each PATTERNS line is
# "<module>\t<glob>" (leading TEST- and trailing .xml removed from the glob) so a
# gated suite is only "covered" by a glob that belongs to the suite's own module.
PATTERNS=""
while IFS= read -r tok; do
  [ -n "$tok" ] || continue
  # Only accept the full "<module-path>/target/<phase>-reports/TEST-<glob>.xml" shape.
  case "$tok" in
    */target/*-reports/TEST-*.xml) ;;
    *) continue ;;
  esac
  modpath="${tok%%/target/*}"   # e.g. mockserver/mockserver-netty  OR  mockserver-blob-azure
  # Key on the module DIRECTORY BASENAME, not the full path: the same module is
  # written both ways above depending on whether the step runs from the repo root
  # or from within mockserver/, and the basename is the only part common to both.
  # ASSUMPTION: module directory basenames are globally unique in this repo (they
  # are today — every Java module is a flat, distinct mockserver/<module>). If a
  # nested module ever duplicates an existing basename, two distinct modules would
  # collapse to one key and a gated suite in one could be reported covered by the
  # other's glob — the same fail-open this module-scoping exists to close. Key on a
  # repo-root-relative module suffix if that ever becomes possible.
  module="${modpath##*/}"       # Maven module dir name, identical in both prefix forms
  glob="${tok##*/TEST-}"        # e.g. *BlobStoreContractTest.xml
  glob="${glob%.xml}"           # e.g. *BlobStoreContractTest
  [ -n "$module" ] || continue
  [ -n "$glob" ] || continue
  PATTERNS="${PATTERNS}${module}"$'\t'"${glob}"$'\n'
done < <(grep -rhE "'[^']*-reports/TEST-[A-Za-z0-9*._-]*\.xml'" .buildkite/scripts 2>/dev/null \
           | grep -vE '^[[:space:]]*#' \
           | grep -oE "'[^']*-reports/TEST-[A-Za-z0-9*._-]*\.xml'" \
           | sed "s/^'//; s/'\$//" \
           | sort -u)

if [ -z "${PATTERNS//[$'\n\t']/}" ]; then
  echo "+++ :bangbang: Rule 1: found no assert-suite-ran report globs at all — the sweep matched nothing, failing closed" >&2
  errors=$(( errors + 1 ))
fi

# Every git-tracked test class basename (for the dangling-pattern rot check).
ALL_TEST_CLASSES="$(git ls-files '*.java' | grep -E '/src/test/' | sed -E 's#.*/##; s#\.java$##' | sort -u)"

pattern_matches_a_class() {
  local pat="$1" cls
  while IFS= read -r cls; do
    [ -n "$cls" ] || continue
    # shellcheck disable=SC2254  # $pat is a deliberate shell glob
    case "$cls" in $pat) return 0 ;; esac
  done <<EOF
$ALL_TEST_CLASSES
EOF
  return 1
}

# A gated suite is covered only by a glob for its OWN module: both the module name
# and the class-name glob must match.
class_is_covered() {
  local module="$1" cls="$2" pmod pglob
  while IFS=$'\t' read -r pmod pglob; do
    [ -n "$pglob" ] || continue
    [ "$pmod" = "$module" ] || continue
    # shellcheck disable=SC2254  # $pglob is a deliberate shell glob
    case "$cls" in $pglob) return 0 ;; esac
  done <<EOF
$PATTERNS
EOF
  return 1
}

# Docker-gated test suites: test sources referencing the canonical probe.
DOCKER_GATED_FILES="$(git grep -l 'DockerAvailability.isAvailable(' -- '*.java' | grep -E '/src/test/' || true)"

covered=0
while IFS= read -r file; do
  [ -n "$file" ] || continue
  cls="$(basename "$file" .java)"
  if r1_is_allowlisted "$file"; then
    continue
  fi
  # Maven module dir name = last path segment before /src/test/ — the same key
  # used for the assert-suite-ran globs, so coverage is correlated per module.
  modpath="${file%%/src/test/*}"
  module="${modpath##*/}"
  if class_is_covered "$module" "$cls"; then
    echo "    :white_check_mark: ${cls}: covered by an assert-suite-ran glob in module ${module}"
    covered=$(( covered + 1 ))
  else
    echo "+++ :bangbang: ${file}: Docker-gated (DockerAvailability.isAvailable) but NO assert-suite-ran glob for module '${module}' names ${cls} — a broken Docker socket would SKIP it and the build would stay green. Pair it via .buildkite/scripts/steps/assert-suite-ran.sh with a glob under '${module}/target/...' (see AGENTS.md), or allow-list it with a justification. (A same-suffix glob in another module does NOT count.)" >&2
    errors=$(( errors + 1 ))
  fi
done <<EOF
$DOCKER_GATED_FILES
EOF
echo "    ${covered} Docker-gated suite(s) confirmed paired"

# Allow-list rot: each entry must still exist, still reference the probe, and still
# NOT be a real gated suite (no Assume gate) — else the exemption is masking a suite.
for entry in "${R1_ALLOWLIST[@]}"; do
  if [ ! -f "$entry" ]; then
    echo "+++ :bangbang: Rule 1 allow-list entry '${entry}' does not exist — remove it or fix the path (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
    continue
  fi
  if ! grep -q 'DockerAvailability.isAvailable(' "$entry"; then
    echo "+++ :bangbang: Rule 1 allow-list entry '${entry}' no longer references DockerAvailability.isAvailable — it is not in the swept set, so exempting it is meaningless; remove it" >&2
    errors=$(( errors + 1 ))
    continue
  fi
  if grep -qE 'Assume\.assume(True|That)' "$entry"; then
    echo "+++ :bangbang: Rule 1 allow-list entry '${entry}' now carries an Assume gate — it has become a real Docker-gated suite and must be assert-suite-ran-paired, not exempted" >&2
    errors=$(( errors + 1 ))
    continue
  fi
  echo "    :white_check_mark: allow-list ok: ${entry} (probe unit test, no Assume gate)"
done

# Dangling-glob rot: an assert-suite-ran pattern that matches no test class is a
# renamed/typo'd suite whose assertion can never bite. (Class-name-only here by
# design: a glob whose class exists but has moved modules is caught as a coverage
# miss above, not a dangling glob.)
while IFS=$'\t' read -r pmod pglob; do
  [ -n "$pglob" ] || continue
  if ! pattern_matches_a_class "$pglob"; then
    echo "+++ :bangbang: assert-suite-ran glob '${pmod}/target/.../TEST-${pglob}.xml' matches no git-tracked test class — the suite was renamed or removed and the assertion is dangling; fix the glob" >&2
    errors=$(( errors + 1 ))
  fi
done <<EOF
$PATTERNS
EOF

# ══════════════════════════════════════════════════════════════════════
# Rule 2 — a step that mounts the Docker socket then deselects Docker tests
# ══════════════════════════════════════════════════════════════════════
echo "--- :electric_plug: Rule 2: no step mounts the Docker socket then deselects Docker tests"

# See R1_ALLOWLIST for the format/justification contract. No legitimate case is
# known; add one here (path + reason) only if a step genuinely must do both.
R2_ALLOWLIST=()

r2_is_allowlisted() {
  local path="$1" entry
  # ${arr[@]+…} keeps an EMPTY array from tripping `set -u` on bash 3.2.
  for entry in ${R2_ALLOWLIST[@]+"${R2_ALLOWLIST[@]}"}; do
    [ "$path" = "$entry" ] && return 0
  done
  return 1
}

# Strip shell comments so the historical defect quoted in a comment does not
# self-trigger the guard — including TRAILING comments (e.g. `run  # was: -m "not
# docker"`), which a full-line-only stripper would miss. The stripper is quote-
# aware: it tracks single/double quotes and cuts only at an UNQUOTED `#` that
# begins a word (column 1, or preceded by whitespace — the shell's own comment
# rule). This never truncates a `#` inside a quoted string, so it cannot HIDE a
# real `-m "not docker"` that follows a literal `#`; in the ambiguous `;#` case it
# errs toward keeping text (a possible false positive), never toward hiding.
strip_comments() {
  awk '
  {
    out = ""; sq = 0; dq = 0; n = length($0);
    for (i = 1; i <= n; i++) {
      c = substr($0, i, 1);
      if (c == "\047" && dq == 0) { sq = 1 - sq; out = out c; continue }  # single quote
      if (c == "\042" && sq == 0) { dq = 1 - dq; out = out c; continue }  # double quote
      if (c == "#" && sq == 0 && dq == 0) {
        if (i == 1) break;
        prev = substr($0, i - 1, 1);
        if (prev == " " || prev == "\t") break;
      }
      out = out c;
    }
    print out;
  }' "$1"
}

R2_SEEN=$'\n'
r2_scanned=0
while IFS= read -r script; do
  [ -n "$script" ] || continue
  r2_scanned=$(( r2_scanned + 1 ))
  body="$(strip_comments "$script")"
  # Docker socket granted: run-in-docker.sh -s/--docker-socket, or a raw mount.
  mounts_socket=0
  if printf '%s\n' "$body" | grep -qE '(--docker-socket)|(/var/run/docker\.sock)|(^[[:space:]]*-s[[:space:]]*\\?[[:space:]]*$)'; then
    mounts_socket=1
  fi
  # Docker-marked tests deselected (pytest -m "not docker" and equivalents).
  deselects_docker=0
  if printf '%s\n' "$body" | grep -qE "[-]m[[:space:]]+['\"][^'\"]*not[[:space:]]+docker"; then
    deselects_docker=1
  fi
  if [ "$mounts_socket" -eq 1 ] && [ "$deselects_docker" -eq 1 ]; then
    if r2_is_allowlisted "$script"; then
      R2_SEEN="${R2_SEEN}${script}"$'\n'
      echo "    :white_check_mark: allow-list ok: ${script} (mounts socket + deselects docker, justified)"
    else
      echo "+++ :bangbang: ${script}: mounts the Docker socket AND deselects Docker-marked tests (e.g. -m \"not docker\") — it pays to mount the socket then starts no container, passing green. Run the Docker suite, or drop the socket." >&2
      errors=$(( errors + 1 ))
    fi
  fi
done < <(git ls-files '.buildkite/scripts/steps/*.sh')

# Fail closed if the sweep matched no step scripts at all (path moved/renamed):
# an empty loop would "pass" Rule 2 having inspected nothing.
if [ "$r2_scanned" -eq 0 ]; then
  echo "+++ :bangbang: Rule 2: swept zero step scripts under .buildkite/scripts/steps/*.sh — the sweep matched nothing, failing closed" >&2
  errors=$(( errors + 1 ))
fi

for entry in ${R2_ALLOWLIST[@]+"${R2_ALLOWLIST[@]}"}; do
  if ! printf '%s' "$R2_SEEN" | grep -Fxq "$entry"; then
    echo "+++ :bangbang: Rule 2 allow-list entry '${entry}' no longer both mounts the socket and deselects Docker tests — remove it (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
  fi
done

# ══════════════════════════════════════════════════════════════════════
# Rule 3 — a container-integration skip that defers work while reading green
# ══════════════════════════════════════════════════════════════════════
echo "--- :fast_forward: Rule 3: no container-integration skip parks deferred work as green"

# See R1_ALLOWLIST for the format/justification contract. Entry form: "<file>:<lineno>".
R3_ALLOWLIST=()

r3_is_allowlisted() {
  local loc="$1" entry
  # ${arr[@]+…} keeps an EMPTY array from tripping `set -u` on bash 3.2.
  for entry in ${R3_ALLOWLIST[@]+"${R3_ALLOWLIST[@]}"}; do
    [ "$loc" = "$entry" ] && return 0
  done
  return 1
}

# Deferral language: a skip that means "work not done yet", not "case genuinely N/A".
DEFERRAL_RE='(follow[- ]?up|TODO|FIXME|not yet|pending|\bWIP\b|coming soon|(to|will) be (wired|added|implemented|done|enabled))'

# Fail closed if Rule 3's search corpus is empty. Unlike Rules 1/2 the VIOLATION
# grep below is legitimately empty in the good case, so the lower bound is on the
# corpus: logTestSkip must appear somewhere under container_integration_tests/. If
# the helper was renamed or the tree moved, the violation grep would silently pass
# having scanned nothing the rule can ever fire on.
if [ -z "$(git grep -l 'logTestSkip' -- 'container_integration_tests/**' 2>/dev/null)" ]; then
  echo "+++ :bangbang: Rule 3: found no logTestSkip usages under container_integration_tests/ — the rule's search corpus is empty (helper renamed or tree moved?), so it can never fire; failing closed" >&2
  errors=$(( errors + 1 ))
fi

R3_SEEN=$'\n'
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  # `git grep -n` output: path:lineno:content
  file="${hit%%:*}"
  rest="${hit#*:}"
  lineno="${rest%%:*}"
  content="${rest#*:}"
  # Skip full-line comments and the helper's own definition.
  case "$content" in
    *[!\ ]*) ;;  # has non-space
    *) continue ;;
  esac
  trimmed="$(printf '%s' "$content" | sed -E 's/^[[:space:]]*//')"
  case "$trimmed" in
    \#*) continue ;;
    function\ logTestSkip*) continue ;;
  esac
  loc="${file}:${lineno}"
  if r3_is_allowlisted "$loc"; then
    R3_SEEN="${R3_SEEN}${loc}"$'\n'
    echo "    :white_check_mark: allow-list ok: ${loc} (justified deferral skip)"
  else
    echo "+++ :bangbang: ${loc}: logTestSkip with deferral language ('${trimmed}') — a skip that parks unfinished work reads as green forever. Wire the case up, or make the skip declare a genuine N/A reason (no follow-up/TODO/pending)." >&2
    errors=$(( errors + 1 ))
  fi
done < <(git grep -niE "logTestSkip.*${DEFERRAL_RE}" -- 'container_integration_tests/**' || true)

for entry in ${R3_ALLOWLIST[@]+"${R3_ALLOWLIST[@]}"}; do
  if ! printf '%s' "$R3_SEEN" | grep -Fxq "$entry"; then
    echo "+++ :bangbang: Rule 3 allow-list entry '${entry}' no longer matches a deferral skip — remove it (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
  fi
done

# ══════════════════════════════════════════════════════════════════════
# Rule 4 — a helm/k3d harness run that can silently revert to skipping
# ══════════════════════════════════════════════════════════════════════
echo "--- :helm: Rule 4: helm/k3d harness runs must require the built images"

# See R1_ALLOWLIST for the format/justification contract. Entry form: "<file>".
# A legitimate case would be a helm-running harness step that genuinely must NOT
# require the images (e.g. images pre-published out of band); none is known.
R4_ALLOWLIST=()

r4_is_allowlisted() {
  local path="$1" entry
  # ${arr[@]+…} keeps an EMPTY array from tripping `set -u` on bash 3.2.
  for entry in ${R4_ALLOWLIST[@]+"${R4_ALLOWLIST[@]}"}; do
    [ "$path" = "$entry" ] && return 0
  done
  return 1
}

# Detect, on the COMMENT-STRIPPED body (reusing Rule 2's quote-aware stripper), so
# a commented-out `export REQUIRE_*_IMAGE=true` can never satisfy the rule and a
# comment that merely mentions integration_tests.sh (e.g. docker-build-verify.sh)
# is not mistaken for a harness invocation.
r4_has() { printf '%s\n' "$1" | grep -qE "$2"; }

# Value must be literally true after expansion (the harness compares == "true");
# accept optional quoting, reject a longer identifier like `trueish`.
R4_TRUE='=['\''"]?true([^A-Za-z0-9_]|$)'

R4_SEEN=$'\n'
r4_helm_running=0
while IFS= read -r script; do
  [ -n "$script" ] || continue
  body="$(strip_comments "$script")"
  # Only steps that actually RUN the container-integration harness.
  r4_has "$body" 'integration_tests\.sh' || continue
  # …with the helm/k3d cases ACTIVE. A step that opts out via SKIP_HELM_TESTS=true
  # (the docker-compose-only run) does not build or need the images, so it is not
  # subject to this rule.
  if r4_has "$body" "SKIP_HELM_TESTS${R4_TRUE}"; then
    continue
  fi
  r4_helm_running=$(( r4_helm_running + 1 ))
  has_clustered=0; has_webhook=0
  r4_has "$body" "REQUIRE_CLUSTERED_IMAGE${R4_TRUE}" && has_clustered=1
  r4_has "$body" "REQUIRE_WEBHOOK_IMAGE${R4_TRUE}"   && has_webhook=1
  if [ "$has_clustered" -eq 1 ] && [ "$has_webhook" -eq 1 ]; then
    echo "    :white_check_mark: ${script}: runs the helm harness and exports both REQUIRE_*_IMAGE=true"
  elif r4_is_allowlisted "$script"; then
    R4_SEEN="${R4_SEEN}${script}"$'\n'
    echo "    :white_check_mark: allow-list ok: ${script} (helm harness without required images, justified)"
  else
    missing=""
    [ "$has_clustered" -eq 0 ] && missing="${missing} REQUIRE_CLUSTERED_IMAGE=true"
    [ "$has_webhook" -eq 0 ] && missing="${missing} REQUIRE_WEBHOOK_IMAGE=true"
    echo "+++ :bangbang: ${script}: runs the container-integration harness with helm/k3d tests active but does NOT export${missing} — a missing image would then record a SKIP instead of a FAILURE and the build would stay green having tested nothing. Export both flags (see helm-integration-test.sh), or set SKIP_HELM_TESTS=true if this step genuinely does not run the helm cases." >&2
    errors=$(( errors + 1 ))
  fi
done < <(git ls-files '.buildkite/scripts/steps/*.sh')

# Fail closed if NO step runs the helm harness at all: the rule would then be
# inspecting nothing (the step was renamed, moved, or the harness invocation
# changed) and could never fire — a silent false positive of its own.
if [ "$r4_helm_running" -eq 0 ]; then
  echo "+++ :bangbang: Rule 4: found no CI step that runs the container-integration harness with helm/k3d tests active — the sweep matched nothing (step renamed or harness invocation changed?), so the rule can never fire; failing closed" >&2
  errors=$(( errors + 1 ))
fi

for entry in ${R4_ALLOWLIST[@]+"${R4_ALLOWLIST[@]}"}; do
  if ! printf '%s' "$R4_SEEN" | grep -Fxq "$entry"; then
    echo "+++ :bangbang: Rule 4 allow-list entry '${entry}' no longer runs the helm harness while missing a REQUIRE_*_IMAGE=true export — remove it (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
  fi
done

# ══════════════════════════════════════════════════════════════════════
# Rule 5 — a JVM-global logging side effect in mockserver-core's parallel phase
# ══════════════════════════════════════════════════════════════════════
echo "--- :scroll: Rule 5: core tests with a global logging side effect must be sequential"

# See R1_ALLOWLIST for the format/justification contract. Entry form: "<repo-
# relative .java path>". A legitimate case would be a core test that matches a
# signature but provably cannot race the shared LogManager (e.g. it re-inits an
# org.mockserver class with no logging in its <clinit> chain); none is known.
R5_ALLOWLIST=()

r5_is_allowlisted() {
  local path="$1" entry
  # ${arr[@]+…} keeps an EMPTY array from tripping `set -u` on bash 3.2.
  for entry in ${R5_ALLOWLIST[@]+"${R5_ALLOWLIST[@]}"}; do
    [ "$path" = "$entry" ] && return 0
  done
  return 1
}

# Global-logging signatures, each rare in test sources and validated against the
# tree (today's core matches in parentheses). ALL four ultimately reach
# LogManager.getLogManager().readConfiguration(...) — a JVM-wide handler reset:
#
#   S1  LogManager.getLogManager().readConfiguration   — the reset itself (0)
#   S2  MockServerLogger.configureLogger(              — its only caller (0)
#   S3  ConfigurationProperties.{logLevel|disableSystemOut|disableLogging}(<arg>)
#       — the static setters, each of which calls configureLogger() (Configuration
#       Test, ExceptionHandlingTest)
#   S4  Class.forName(<expr>, true, <loader>)          — a fresh <clinit> forced via
#       an isolated classloader, which re-runs MockServerLogger's static reset
#       (ClassInitializationDeadlockTest, ConfigurationPropertiesInitializationTest)
#
# DELIBERATELY REJECTED as too noisy (would be muted by allow-list churn):
#   * bare `disableSystemOut(`/`disableLogging(`/`logLevel(` WITHOUT the
#     `ConfigurationProperties.` qualifier — these match the per-instance
#     `Configuration` builder (`configuration().logLevel(Level.INFO)
#     .disableSystemOut(false)`), which is NOT a global side effect; requiring the
#     static qualifier drops GrpcFailSafeLoggingTest, ConfigurationSerializerTest,
#     ConfigurationDTOTest and MockServerLoggerTest, all legitimately parallel.
#   * `Class.forName(` in any form — the 1-arg `Class.forName(className)` (already-
#     loaded classes) and `Class.forName('java.lang.Runtime')` inside a template
#     string are legitimately parallel; only the 3-arg initialize=true form forces
#     a fresh <clinit>.
#
# LIMITS (stated rather than implied — this rule does NOT catch):
#   * a call site reached via a static import (bare `logLevel("X")`) or reflection
#     — indistinguishable from the per-instance builder without semantic analysis,
#     so the qualified form is the low-false-positive choice;
#   * a global logging side effect in a module OTHER than mockserver-core — only
#     core has the parallel/sequential surefire split this rule keys on;
#   * S4 keys on the reflective SHAPE, not the loaded class name (one true call
#     site loads a variable), so a 3-arg initialize=true load of a non-logging
#     class would be flagged too — but forcing a fresh <clinit> in a chosen loader
#     is itself parallel-unsafe, and there are zero such cases today.
R5_SIGNATURES=(
  'LogManager\.getLogManager\(\)\.readConfiguration'
  'MockServerLogger\.configureLogger\('
  'ConfigurationProperties\.logLevel\([^)]'
  'ConfigurationProperties\.disableSystemOut\([^)]'
  'ConfigurationProperties\.disableLogging\([^)]'
  'Class\.forName\([^,)]+,[[:space:]]*true[[:space:]]*,'
)

# The sequential-includes list lives in the `sequential-tests` execution of
# mockserver-core/pom.xml. Extract ONLY that execution's <include> basenames — a
# second, unrelated <includes> block (the state/contract failsafe run) must not be
# read as "sequential". Track the execution by its <id> and close on </execution>.
CORE_POM="mockserver/mockserver-core/pom.xml"
SEQUENTIAL_INCLUDES="$(awk '
  /<id>sequential-tests<\/id>/ { inseq = 1 }
  inseq && /<include>/ {
    line = $0
    sub(/.*<include>\*\*\//, "", line)
    sub(/<\/include>.*/, "", line)
    sub(/\.java$/, "", line)
    print line
  }
  inseq && /<\/execution>/ { inseq = 0 }
' "$CORE_POM")"

r5_is_sequential() {
  local cls="$1" seq
  while IFS= read -r seq; do
    [ "$cls" = "$seq" ] && return 0
  done <<EOF
$SEQUENTIAL_INCLUDES
EOF
  return 1
}

# Collect offending files: every mockserver-core test source with a signature
# match on a NON-COMMENT line (a Java comment mentioning a setter or forName must
# not count). git grep -n gives path:lineno:content.
R5_OFFENDERS=""
r5_corpus=0
for sig in "${R5_SIGNATURES[@]}"; do
  while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    file="${hit%%:*}"
    rest="${hit#*:}"
    content="${rest#*:}"
    trimmed="$(printf '%s' "$content" | sed -E 's/^[[:space:]]*//')"
    # Drop Java comment lines (javadoc `*`, `//`, block `/*`/`*/`).
    case "$trimmed" in
      \**|//*|/\**) continue ;;
    esac
    r5_corpus=$(( r5_corpus + 1 ))
    R5_OFFENDERS="${R5_OFFENDERS}${file}"$'\n'
  done < <(git grep -nE "$sig" -- '*.java' \
             | grep '/mockserver-core/' \
             | grep '/src/test/' || true)
done

# Fail closed on an empty corpus: if NO signature matches any core test source,
# the signatures have rotted (methods renamed / tree moved) and the rule can never
# fire — a silent false positive. (Today S4 alone guarantees ≥2 matches.)
if [ "$r5_corpus" -eq 0 ]; then
  echo "+++ :bangbang: Rule 5: no core test source matches any JVM-global-logging signature — the signatures have rotted (methods renamed or tree moved?), so the rule can never fire; failing closed" >&2
  errors=$(( errors + 1 ))
fi

R5_SEEN=$'\n'
while IFS= read -r file; do
  [ -n "$file" ] || continue
  cls="$(basename "$file" .java)"
  if r5_is_sequential "$cls"; then
    echo "    :white_check_mark: ${cls}: performs a global logging side effect and is sequential"
  elif r5_is_allowlisted "$file"; then
    R5_SEEN="${R5_SEEN}${file}"$'\n'
    echo "    :white_check_mark: allow-list ok: ${file} (global logging side effect, justified as non-racing)"
  else
    echo "+++ :bangbang: ${file}: performs a JVM-global logging side effect (LogManager reset via a static logging setter or a forced fresh <clinit>) but is NOT in the sequential-includes list of ${CORE_POM} — in the parallel phase it races other tests' log capture, silently zeroing a capture or falsely passing a silence assertion. Add <include>**/${cls}.java</include> to the sequential-tests execution (and the matching parallel <exclude>), or allow-list it with a justification." >&2
    errors=$(( errors + 1 ))
  fi
done < <(printf '%s' "$R5_OFFENDERS" | sort -u)

for entry in ${R5_ALLOWLIST[@]+"${R5_ALLOWLIST[@]}"}; do
  if [ ! -f "$entry" ]; then
    echo "+++ :bangbang: Rule 5 allow-list entry '${entry}' does not exist — remove it or fix the path (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
    continue
  fi
  if ! printf '%s' "$R5_SEEN" | grep -Fxq "$entry"; then
    echo "+++ :bangbang: Rule 5 allow-list entry '${entry}' either no longer matches a global-logging signature or has since been added to the sequential list — the exemption is now meaningless; remove it" >&2
    errors=$(( errors + 1 ))
  fi
done

# ══════════════════════════════════════════════════════════════════════
echo "--- :bar_chart: false-green guard summary: ${errors} error(s)"
if [ "$errors" -gt 0 ]; then
  echo "+++ :bangbang: false-green guard FAILED with ${errors} error(s) — a new false-green shape was introduced (see docs/operations/false-green-guards.md)" >&2
  exit 1
fi
echo "--- :white_check_mark: false-green guard PASSED"
