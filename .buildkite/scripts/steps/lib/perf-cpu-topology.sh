#!/usr/bin/env bash
# Shared CPU-topology helpers for the performance harness.
#
# WHY THIS EXISTS. The physical-core DISJOINTNESS proof used to live inline in
# perf-test-run.sh, guarding only the main SUT / upstream / k6 pinning. The
# multi-process client rig (mockserver-performance-test/scripts/multi-process-sweep.sh)
# pins its own SUT and N client blocks by LOGICAL cpu id, and at higher process
# counts a client block can cross onto the hyperthread SIBLINGS of the server's
# cores — silently putting the load generator inside the system under test and
# reporting the result as a SERVER ceiling. That is exactly the defect the main
# guard was written to stop ("the load generator has been sharing the server's
# physical cores all along") and the whole justification for the hardware resize.
# So the proof is factored HERE, ONE implementation, sourced by BOTH callers, and
# generalised over an arbitrary list of role/spec pairs — the two callers cannot
# drift, and there is no second copy of the topology parsing to rot.
#
# This file only DEFINES functions (no side effects), so it is safe to `source`
# under `set -euo pipefail`.

# Expand a cpuset spec ("0-5" / "8,9" / "6") into a space-separated list of logical
# CPU ids, so each can be mapped to the PHYSICAL core it actually sits on.
expand_cpuset() {
  local spec="$1" part a b i out="" _xparts
  [ -z "$spec" ] && { echo ""; return; }
  IFS=',' read -ra _xparts <<< "$spec"
  for part in "${_xparts[@]}"; do
    if [[ "$part" == *-* ]]; then
      a="${part%%-*}"; b="${part##*-}"
      for ((i=a; i<=b; i++)); do out="$out $i"; done
    else
      out="$out $part"
    fi
  done
  echo "${out# }"
}

# Map a logical CPU id to a stable PHYSICAL core key ("<package>:<core>"). Two
# hyperthread siblings share one key, which is the whole point: cpusets that look
# disjoint in logical numbering can be the two threads of the same core. It resolves
# REAL topology from sysfs rather than assuming an enumeration, so it holds whatever
# the sibling mapping turns out to be. PERF_SYSFS_CPU_ROOT exists so the guard itself
# can be tested against a SIMULATED topology; it defaults to the real sysfs path.
phys_core_key() {
  local cpu="$1" base="${PERF_SYSFS_CPU_ROOT:-/sys/devices/system/cpu}/cpu$1/topology"
  [ -r "$base/core_id" ] || return 1
  printf '%s:%s' "$(cat "$base/physical_package_id" 2>/dev/null || echo 0)" "$(cat "$base/core_id")"
}

# THE GUARD, generalised over an arbitrary list of role/spec PAIRS:
#   cpusets_physically_disjoint server "0-5" upstream "6" k6 "8-19"
#   cpusets_physically_disjoint server "0-3" client0 "4-5" client1 "6-7"
#
# Proves the given cpusets occupy DISJOINT physical cores (not merely disjoint
# logical ids — two hyperthread siblings are ONE core). Returns:
#   0  disjoint; OR topology unreadable OFF-CI (a limitation, not a fault — the
#      caller still runs, the numbers are just labelled unverifiable).
#   1  (FAIL THE RUN) two cpusets share a physical core; OR a non-empty spec expands
#      to nothing (a reversed/malformed range that would otherwise narrow the check
#      silently); OR the args are not role/spec pairs; OR topology unreadable IN CI
#      (BUILDKITE=true) — an unprovable benchmark is the thing this guard stops.
# An EMPTY spec is skipped (a role may legitimately be unpinned), never treated as
# "no overlap proven".
#
# Two historical defects of the original inline guard are deliberately avoided here,
# both of the class it targets (a guard that fails for the wrong reason, or passes
# because it examined nothing):
#   * NO `local -A`. Associative arrays need bash 4; macOS ships bash 3.2, where the
#     array form is a syntax ERROR — the guard would die instead of checking. The
#     seen-cores set is a newline-separated "<core key> <role>" table scanned with awk.
#   * A non-empty spec that expands to NOTHING is FATAL, not skipped — otherwise a
#     reversed range ("13-8") would quietly drop a role from the check while the rest
#     reported disjoint.
cpusets_physically_disjoint() {
  # Args must be role/spec PAIRS. An odd count means the caller dropped a spec; an
  # unpaired role would otherwise be shifted past and silently ignored — the very
  # silent-skip failure this guard exists to prevent. Fail closed on misuse.
  if [ $(( $# % 2 )) -ne 0 ]; then
    echo "^^^ +++"
    echo ":x: cpusets_physically_disjoint requires role/spec PAIRS, got $# argument(s) — refusing to verify against a malformed argument list" >&2
    return 1
  fi

  local seen="" role spec cpu key dupe="" count=0 prev roles="" pairs=""
  while [ "$#" -ge 2 ]; do
    role="$1"; spec="$2"; shift 2
    roles="${roles:+$roles / }$role"
    pairs="${pairs:+$pairs }$role=${spec:-<unpinned>}"
    [ -z "$spec" ] && continue
    local _expanded; _expanded="$(expand_cpuset "$spec")"
    if [ -z "$_expanded" ]; then
      echo "^^^ +++"
      echo ":x: the $role cpuset '$spec' expands to no cpus (a reversed or malformed range?) — refusing to verify core isolation against an empty set" >&2
      return 1
    fi
    for cpu in $_expanded; do
      if ! key="$(phys_core_key "$cpu")"; then
        # Topology unreadable (no /sys — e.g. a local macOS run). Off-CI that is a
        # limitation; in CI it means we cannot PROVE the numbers are honest, and an
        # unprovable benchmark is the thing this guard exists to stop.
        if [ "${BUILDKITE:-}" = "true" ]; then
          echo "^^^ +++"
          echo ":x: cannot read CPU topology for cpu${cpu}; refusing to produce benchmark figures that cannot be shown to be contention-free" >&2
          return 1
        fi
        echo "--- WARNING: CPU topology unreadable — cannot verify the cpusets are physically disjoint (expected off-CI)"
        return 0
      fi
      prev="$(printf '%s\n' "$seen" | awk -v k="$key" '$1==k {print $2; exit}')"
      if [ -n "$prev" ]; then
        dupe="$dupe\n    physical core $key is used by BOTH $prev and $role (via cpu$cpu)"
      else
        seen="$seen$key $role"$'\n'
        count=$((count + 1))
      fi
    done
  done

  if [ -n "$dupe" ]; then
    echo "^^^ +++"
    echo ":x: the $roles cpusets OVERLAP on physical cores — the load generator would contend with the system under test, so any throughput figure from this run would measure the two of them fighting, not the server:" >&2
    printf '%b\n' "$dupe" >&2
    echo "    $pairs on $(nproc 2>/dev/null || echo '?') logical cpus" >&2
    echo "    Fix the cpusets or use a box with more physical cores." >&2
    return 1
  fi
  echo "--- verified: $roles occupy ${count} distinct physical cores, none shared"
  return 0
}

# Count the DISTINCT physical cores a cpuset spec occupies, resolved from real
# sysfs topology (two hyperthread siblings count ONCE). This is a REPORTING helper
# for perf-result.json so a published figure can state real cores rather than vCPUs
# — it never fails the run (cpusets_physically_disjoint above is the fail-closed
# guard). Echoes:
#   an integer  the number of distinct physical cores in the spec (0 for an empty spec);
#   "null"      topology could not be read for some cpu in the spec (e.g. off-CI on
#               macOS with no /sys) — the caller records null, not a guessed count.
# bash-3.2-safe: distinct keys are counted via sort -u, no `local -A`.
phys_core_count() {
  local spec="$1" cpu key keys=""
  [ -z "$spec" ] && { echo 0; return; }
  for cpu in $(expand_cpuset "$spec"); do
    if ! key="$(phys_core_key "$cpu")"; then echo "null"; return; fi
    keys="$keys$key"$'\n'
  done
  printf '%s' "$keys" | sort -u | sed '/^$/d' | wc -l | tr -d ' '
}
