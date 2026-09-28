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
# After the run it emits a compact annotation: allocation per request over the load
# window, peak direct-buffer memory, the last live-heap histogram, and the top allocation
# sites/classes from the SUT's load-window JFR dump (sut/load.jfr, a JDK `jfr view`
# sidecar). A recording below the duration/sample floors is reported INVALID instead of
# being summarised. Best-effort: it never changes the step's exit code.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

RUN_NAME="allocprofile"
ARTIFACT_PREFIX="${RUN_NAME}-"
ANNOTATE_CONTEXT="perf-${RUN_NAME}"
JDK_IMAGE="${PERF_HISTO_JDK_IMAGE:-eclipse-temurin:21-jdk}"
JFR_VIEW_DEADLINE_S="${PERF_ALLOCPROFILE_JFR_DEADLINE_S:-120}"
# Sanity floor for the recording the views are read from: a recording shorter than this, or
# with fewer allocation samples, cannot describe the load and is reported INVALID.
MIN_JFR_DURATION_S="${PERF_ALLOCPROFILE_MIN_JFR_DURATION_S:-60}"
MIN_ALLOC_SAMPLES="${PERF_ALLOCPROFILE_MIN_ALLOC_SAMPLES:-1000}"

annotate() { # style, body
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' "$2" | buildkite-agent annotate --style "$1" --context "$ANNOTATE_CONTEXT" || true
  fi
  printf '\n%s\n' "$2"
}

jfr_tool() { # work_dir, jfr args... (paths under /w)
  local w="$1"; shift
  timeout "$JFR_VIEW_DEADLINE_S" docker run --rm -v "$w:/w" "$JDK_IMAGE" jfr "$@" 2>/dev/null
}

# Assemble the readable chunks of a JFR repository into /w/assembled.jfr. The chunk a live (or
# hard-killed) JVM was still writing is unreadable and would make the whole assembly unreadable.
assemble_finished_chunks() { # work_dir, repository dir under it
  local w="$1" rel="${2#"$1"/}"
  # shellcheck disable=SC2016
  # As the agent's uid, so the step's own cleanup can remove what the sidecar writes into $w.
  timeout "$JFR_VIEW_DEADLINE_S" docker run --rm --user "$(id -u):$(id -g)" -v "$w:/w" "$JDK_IMAGE" sh -c '
    mkdir -p /w/finished-chunks
    for f in "$1"/*.jfr; do jfr summary "$f" >/dev/null 2>&1 && cp "$f" /w/finished-chunks/; done
    ls /w/finished-chunks/*.jfr >/dev/null 2>&1 && jfr assemble /w/finished-chunks /w/assembled.jfr' _ "/w/$rel" >/dev/null 2>&1 || true
}

largest_repo_dir() { # jfr repository root -> the per-JVM subdirectory holding the most chunk data
  local d best="" best_kb=-1 kb
  for d in "$1"/*/; do
    [ -d "$d" ] || continue
    kb="$(du -sk "$d" | cut -f1)"
    [ "$kb" -gt "$best_kb" ] && { best="${d%/}"; best_kb="$kb"; }
  done
  printf '%s' "$best"
}

# Allocation per request and peak direct-buffer memory over the recorded load window, from
# diag-samples.csv (columns located by header name, so older bundles degrade to "unavailable").
load_window_figures() { # work_dir
  local w="$1" start end
  if [ ! -s "$w/load-window.json" ] || [ ! -s "$w/diag-samples.csv" ]; then
    echo "- allocation per request: unavailable (no recorded load window or resource samples)"
    return 0
  fi
  start="$(jq -r '.start_epoch // empty' "$w/load-window.json" 2>/dev/null || true)"
  end="$(jq -r '.end_epoch // empty' "$w/load-window.json" 2>/dev/null || true)"
  awk -F, -v s="${start:-0}" -v e="${end:-0}" '
    NR==1 { for (i=1;i<=NF;i++) col[$i]=i; next }
    !("jvm_allocated_bytes" in col) || !("req_dur_count" in col) { next }
    $1+0 >= s && $1+0 <= e && $col["jvm_allocated_bytes"] != "" && $col["req_dur_count"] != "" {
      a=$col["jvm_allocated_bytes"]+0; r=$col["req_dur_count"]+0
      if (!n++) { a0=a; r0=r; t0=$1 } a1=a; r1=r; t1=$1
    }
    ("direct_buffer_used_bytes" in col) && $1+0 >= s && $1+0 <= e && $col["direct_buffer_used_bytes"] != "" {
      d=$col["direct_buffer_used_bytes"]+0; if (d>dmax) dmax=d; dn++
    }
    ("netty_direct_used_bytes" in col) && $1+0 >= s && $1+0 <= e && $col["netty_direct_used_bytes"] != "" {
      d=$col["netty_direct_used_bytes"]+0; if (d>nmax) nmax=d; nn++
    }
    END {
      if (n>1 && r1>r0) printf "- allocation per request: **%.1f KB** (%.2f GB allocated over %d requests, %d s load window; includes JFR and scrape overhead)\n", (a1-a0)/(r1-r0)/1000, (a1-a0)/1e9, r1-r0, t1-t0
      else print "- allocation per request: unavailable (no allocation/request samples inside the load window)"
      if (dn>0 || nn>0) {
        printf "- direct memory peak during the load window: NIO direct pool **%s**, Netty-tracked **%s**\n", \
          (dn>0 ? sprintf("%.1f MiB", dmax/1048576) : "n/a"), (nn>0 ? sprintf("%.1f MiB", nmax/1048576) : "n/a (Netty not tracking)")
      } else print "- direct memory: unavailable (the SUT image predates jvm_buffer_pool_used_bytes)"
    }' "$w/diag-samples.csv"
}

# Post the load-window figures, the retained-heap histogram, and the top allocation sites/classes
# from the SUT's load-window JFR dump inside the diagnostics bundle. Never fails the step.
emit_allocation_annotation() {
  local tgz="$REPO_ROOT/${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz"
  if [ ! -f "$tgz" ]; then
    annotate "warning" ":microscope: **Allocation profile ran (deep JFR), not baselined.** No \`${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz\` was produced this run, so no allocation summary could be extracted (see the step log for the packaging error)."
    return 0
  fi
  local work; work="$(mktemp -d "${TMPDIR:-/tmp}/allocprofile.XXXXXX")" || return 0
  # shellcheck disable=SC2064
  trap "rm -rf '$work'" RETURN
  tar xzf "$tgz" -C "$work" 2>/dev/null || true

  local out="$work/alloc.md" style="info" jfr="" source="" verdict="" repo
  {
    echo "### load window"
    load_window_figures "$work"
    echo
    echo "### live heap — last sample (what the heap RETAINS)"
    echo '```'
    if [ -s "$work/sut/live-heap-histogram.txt" ]; then
      awk '/^===== elapsed_s=/{buf=""} {buf = buf $0 "\n"} END{printf "%s", buf}' "$work/sut/live-heap-histogram.txt"
    else
      echo "(no live-heap histogram — the jcmd sidecar could not attach; see the step log)"
    fi
    echo '```'
    echo
  } > "$out"

  # The load-window dump is the only recording that describes the load. Without it (e.g. the SUT
  # died first) the repository chunks are assembled instead; the floor below judges either.
  if [ "$(jq -r '.jfr // empty' "$work/load-window.json" 2>/dev/null)" = "sut/load.jfr" ] && [ -s "$work/sut/load.jfr" ]; then
    jfr="sut/load.jfr"; source="load-window dump"
  elif command -v docker >/dev/null 2>&1 && repo="$(largest_repo_dir "$work/sut/jfr-repo")" && [ -n "$repo" ]; then
    assemble_finished_chunks "$work" "$repo"
    [ -s "$work/assembled.jfr" ] && { jfr="assembled.jfr"; source="finished repository chunks (no load-window dump)"; }
  fi

  if [ -z "$jfr" ]; then
    style="warning"
    verdict="No SUT JFR recording in the bundle$(jq -r '.error // empty | " (" + . + ")"' "$work/load-window.json" 2>/dev/null) — allocation sites unavailable."
  elif ! command -v docker >/dev/null 2>&1; then
    verdict="Docker is unavailable on this agent, so \`jfr\` could not run. Run \`jfr view allocation-by-site $jfr\` on the bundle."
  else
    local summary duration samples
    summary="$(jfr_tool "$work" summary "/w/$jfr" || true)"
    duration="$(printf '%s\n' "$summary" | awk '/^ *Duration:/{print $2; exit}')"
    samples="$(printf '%s\n' "$summary" | awk '$1=="jdk.ObjectAllocationSample"{print $2; exit}')"
    case "$duration" in ''|*[!0-9]*) duration=0 ;; esac
    case "$samples" in ''|*[!0-9]*) samples=0 ;; esac
    if [ "$duration" -lt "$MIN_JFR_DURATION_S" ] || [ "$samples" -lt "$MIN_ALLOC_SAMPLES" ]; then
      style="warning"
      verdict="**Recording INVALID — not summarised.** The ${source} covers ${duration} s with ${samples} allocation samples (floor: ${MIN_JFR_DURATION_S} s and ${MIN_ALLOC_SAMPLES} samples), so allocation-by-site would describe too little of the load to be meaningful."
    else
      verdict="Allocation sites from the ${source}: ${duration} s, ${samples} allocation samples."
      local view
      for view in allocation-by-site allocation-by-class; do
        {
          echo "### ${view}"
          echo '```'
          jfr_tool "$work" view --width 120 "$view" "/w/$jfr" | head -24 || true
          echo '```'
          echo
        } >> "$out"
      done
    fi
  fi

  annotate "$style" ":microscope: **Allocation profile — deep JFR run (NOT baselined).** Throughput this run is deliberately depressed by JFR/NMT/GC-logging, so its figures are excluded from the baseline. ${verdict} Bundle: \`${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz\`.

$(cat "$out")"
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
