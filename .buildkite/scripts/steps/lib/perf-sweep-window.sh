#!/usr/bin/env bash
# Shared sweep.js summary check (sourced by perf-test-run.sh, lib/perf-percore.sh and
# multi-process-sweep.sh; defines functions only). sweep.js assigns each request its
# latency window with a VU tag that k6 keeps across iterations of a scenario; if that
# stops holding, requests go untagged and the percentiles silently cover part of a rung.

# Returns 0 silently when every rung with sample_count > 0 has measured_sample_count +
# settle_excluded == sample_count and, when the run had a settle window and the rung
# dropped nothing, a non-empty settle and measured window. Otherwise prints the broken
# rungs and returns 1 (also on an unreadable summary).
sweep_window_mismatches() { # sweep_json_path
  local out
  if ! out="$(jq -r '
      ((.latency_window.settle_s // 0) > 0) as $settles
      | [ (.points // [])[] | select((.sample_count // 0) > 0)
          | (.measured_sample_count // 0) as $measured
          | (.settle_excluded // 0) as $settle
          | ($measured + $settle) as $windows
          | if $windows != .sample_count then
              "\(.offered_rps): measured_sample_count+settle_excluded=\($windows) != sample_count=\(.sample_count)"
            elif $settles and (.dropped_iterations // 0) == 0 and ($settle == 0 or $measured == 0) then
              "\(.offered_rps): a window is empty with settle on and no drops (settle_excluded=\($settle), measured_sample_count=\($measured))"
            else empty end ]
      | join("; ")' "$1" 2>/dev/null)"; then
    echo "unreadable sweep summary $1"
    return 1
  fi
  [ -z "$out" ] && return 0
  echo "$out"
  return 1
}
