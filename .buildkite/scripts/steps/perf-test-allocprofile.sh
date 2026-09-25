#!/usr/bin/env bash
set -euo pipefail

# Allocation-profile step (perf queue). Answers "what is actually allocating?" on
# every dispatched perf run, WITHOUT contaminating the publishable figures.
#
# It runs the SAME harness as the clean `perf-run` step (perf-test-run.sh) but with
# PERF_JVM_DIAGNOSTICS=deep — tier-2 instrumentation (GC file logging, NMT, and a
# JFR `profile` recording that samples allocation + CPU hotspots). That instrumentation
# depresses throughput BY DESIGN, which is exactly why this is a SEPARATE step and MUST
# NOT feed the baseline:
#
#   1. PERF_RUN_NAME=allocprofile prefixes EVERY artifact this run uploads
#      (allocprofile-perf-result.json, allocprofile-perf-jvm-diagnostics.tgz, ...).
#      perf-test-compare.sh downloads `perf-result.json` build-wide by EXACT name and
#      persists what it downloads to S3; a prefixed name cannot match, so the degraded
#      throughput can never enter the rolling baseline. (Belt and braces: a deep run
#      also stamps baseline_eligible:false, which compare honours — but compare never
#      even sees this run's result, because the name does not match.)
#   2. This step is NOT a dependency of perf-compare (see perf-test-guard.sh), so it
#      cannot be waited on, gated on, or baselined.
#   3. soft_fail:true at the pipeline level — a throughput number here never reds the
#      build; only a genuine harness fault shows (visibly) as a soft-fail.
#
# A short ladder + trimmed durations keep it cheap: this measures WHAT allocates, not
# how fast. The auxiliary profiles the daily run carries (INFO-log arm, laptop,
# streaming, proxy, clustered) are turned off — they add wall-clock without adding
# allocation-attribution value on the main serving paths (match / template / large
# bodies / event-log), which regression.js + growth.js + a 4-rung sweep already cover.
#
# After the run it emits a compact annotation of the top allocation sites, read cheaply
# from the JFR recording inside the uploaded diagnostics bundle (a JDK `jfr view`
# sidecar), so the value is visible without downloading artifacts. Best-effort: if the
# recording or a JDK sidecar is unavailable the annotation points at the bundle instead.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

RUN_NAME="allocprofile"
ARTIFACT_PREFIX="${RUN_NAME}-"
ANNOTATE_CONTEXT="perf-${RUN_NAME}"
JDK_IMAGE="${PERF_HISTO_JDK_IMAGE:-eclipse-temurin:21-jdk}"
JFR_VIEW_DEADLINE_S="${PERF_ALLOCPROFILE_JFR_DEADLINE_S:-120}"

annotate() { # style, body
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' "$2" | buildkite-agent annotate --style "$1" --context "$ANNOTATE_CONTEXT" || true
  fi
  printf '\n%s\n' "$2"
}

# Post the top allocation sites (and by-class) from the JFR recording inside the
# uploaded diagnostics bundle. Entirely best-effort — never fails the step.
emit_allocation_annotation() {
  local tgz="$REPO_ROOT/${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz"
  if [ ! -f "$tgz" ]; then
    annotate "info" ":microscope: **Allocation profile ran (deep JFR), not baselined.** No \`${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz\` was produced this run, so no allocation summary could be extracted. If the run reached load, the JFR recording is normally in that bundle."
    return 0
  fi
  local work; work="$(mktemp -d "${TMPDIR:-/tmp}/allocprofile.XXXXXX")" || return 0
  # shellcheck disable=SC2064
  trap "rm -rf '$work'" RETURN
  tar xzf "$tgz" -C "$work" 2>/dev/null || true

  # Prefer the finalised recording.jfr; fall back to the largest live repository chunk
  # (which survives even a hard JVM exit — the reason the harness keeps a jfr-repo).
  local jfr=""
  if [ -s "$work/sut/recording.jfr" ]; then
    jfr="$work/sut/recording.jfr"
  else
    jfr="$(ls -S "$work"/sut/jfr-repo/*.jfr 2>/dev/null | head -1 || true)"
  fi
  if [ -z "$jfr" ] || [ ! -s "$jfr" ]; then
    annotate "info" ":microscope: **Allocation profile ran (deep JFR), not baselined.** No JFR recording was found in \`${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz\` (the run may not have reached sustained load). The bundle still carries the GC log and NMT summary."
    return 0
  fi
  if ! command -v docker >/dev/null 2>&1; then
    annotate "info" ":microscope: **Allocation profile ran (deep JFR), not baselined.** Docker is unavailable on this agent, so \`jfr view\` could not run here. Download \`${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz\` and run \`jfr view allocation-by-site <recording.jfr>\`."
    return 0
  fi

  local jdir jfile out; jdir="$(dirname "$jfr")"; jfile="$(basename "$jfr")"; out="$work/alloc.txt"
  : > "$out"
  # Retained first — it is the question the allocation views cannot answer. JFR's own retention
  # views (object-statistics, memory-leaks-by-class) are EMPTY under ZGC, so do not add them here;
  # the live-set answer comes from the jcmd histogram the harness samples during the run.
  local histo="$work/sut/live-heap-histogram.txt"
  {
    echo "### live heap — last sample (what the heap RETAINS)"
    echo '```'
    if [ -s "$histo" ]; then
      awk '/^===== elapsed_s=/{buf=""} {buf = buf $0 "\n"} END{printf "%s", buf}' "$histo"
    else
      echo "(no live-heap histogram — the jcmd sidecar could not attach; see the step log)"
    fi
    echo '```'
    echo
  } >> "$out"
  local view
  for view in allocation-by-site allocation-by-class; do
    {
      echo "### ${view}"
      echo '```'
      timeout "$JFR_VIEW_DEADLINE_S" docker run --rm -v "$jdir:/j:ro" "$JDK_IMAGE" \
        jfr view --width 120 "$view" "/j/$jfile" 2>/dev/null | head -24 || true
      echo '```'
      echo
    } >> "$out"
  done

  if [ -s "$out" ] && grep -q '[A-Za-z]' "$out"; then
    annotate "info" ":microscope: **Top allocation sites — deep JFR profile (NOT baselined).** Throughput this run is deliberately depressed by JFR/NMT/GC-logging, so its figures are excluded from the baseline. Full recording: \`${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz\`.

$(cat "$out")"
  else
    annotate "info" ":microscope: **Allocation profile ran (deep JFR), not baselined.** \`jfr view\` produced no allocation rows (the \`profile\` settings may not have captured allocation samples on this JDK, or the chunk was empty). Download \`${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz\` for the raw recording."
  fi
}

echo "--- :microscope: allocation profile — deep JFR run (PERF_RUN_NAME=${RUN_NAME}); artifacts are prefixed and NOT baselined"

# Short + modest: a small sweep ladder and trimmed durations, deep diagnostics on, and
# the throughput-only auxiliary profiles off. Everything is overridable so a deeper
# investigation can widen the ladder without editing this file.
# The ladder's TOP rung must reach the load where the heap is genuinely full — a histogram
# of an idle heap attributes nothing. It brackets the measured healthy ceiling rather than
# sitting below it; this run's own throughput is irrelevant, only the heap composition is.
rc=0
PERF_RUN_NAME="$RUN_NAME" \
PERF_JVM_DIAGNOSTICS=deep \
PERF_SERVER_MEMORY="${PERF_SERVER_MEMORY:-4g}" \
PERF_CLUSTERED=false \
PERF_INFO_ARM="${PERF_INFO_ARM:-false}" \
PERF_LAPTOP_PROFILE="${PERF_LAPTOP_PROFILE:-false}" \
PERF_STREAMING="${PERF_STREAMING:-false}" \
PERF_PROXY_PROFILE="${PERF_PROXY_PROFILE:-false}" \
K6_SWEEP_RATES="${K6_SWEEP_RATES:-8000,24000,48000}" \
PERF_LIVE_HISTO_INTERVAL_S="${PERF_LIVE_HISTO_INTERVAL_S:-30}" \
K6_REG_DURATION="${K6_REG_DURATION:-45s}" \
K6_GROWTH_DURATION="${K6_GROWTH_DURATION:-2m}" \
  "$SCRIPT_DIR/perf-test-run.sh" || rc=$?

emit_allocation_annotation || true

# Preserve the harness exit code so a genuine fault is visible as a soft-fail. The
# step is soft_fail:true in perf-test-guard.sh, so a non-zero rc NEVER reds the build.
exit "$rc"
