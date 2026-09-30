# perf-website-figures.jq
# ---------------------------------------------------------------------------
# Transform ONE self-describing perf run (schema_version >= 2, produced by
# .buildkite/scripts/steps/perf-test-run.sh) into the PUBLISHABLE figures the
# website renders from jekyll-www.mock-server.com/_data/perf_figures.json.
#
# This filter decides WHAT reaches the public page. It deliberately publishes
# ONLY the two families the performance programme has cleared as honest,
# absolute, customer-facing claims:
#
#   * the throughput/latency knee curve, with healthy_ceiling_rps as the
#     headline and peak_achieved_rps beside it EXPLICITLY labelled degraded
#     (docs/plans/performance-programme.md, Finding 1); and
#   * per-behaviour percentiles from the FIXED regression.js (Finding 3,
#     resolved 2026-09-16) — emitted only when the run actually carries them.
#
# It NEVER emits the internal-only regression detectors the plan marks
# "gated internally, never published": the JMH absolute backstops, growth/soak
# live-set slope and absolute, event-log verification cost, forward-pool guard,
# AppCDS boolean, leak gate, the streaming match-A/B ratio (an A/B tripwire
# measured against a DELIBERATELY constrained 1-CPU SUT — misleading as an
# absolute public figure), the startup median-of-9, and the baseline-freshness
# assertion. Those metrics exist to move under regression, not to be quoted.
#
# healthy_ceiling_rps is computed here to Finding 1's definition — the highest
# offered rung where achieved stayed within (1 - keep) of offered, errors were
# zero, AND p50 stayed within lat_mult x the flat-region p50 — rather than
# trusting the run's own saturation_rps, so a reader can see exactly which rung
# it is and that latency was still flat there.
#
# Args (all via --argjson / --arg):
#   $now       ISO-8601 timestamp to stamp as published_utc
#   $lat_mult  latency multiple defining "still flat" (default caller: 3)
#   $keep      achieved/offered floor for a healthy rung (default caller: 0.95)
#
# The caller (perf-website-publish.sh) is responsible for REFUSING to publish a
# run whose sweep is missing/invalid (headline null); this filter only shapes a
# run it is given. behaviours is null when the run carries none — the page
# renders the behaviours section only when it is present.

def round2: (. * 100 | round) / 100;
def round3: (. * 1000 | round) / 1000;
# Thousands separators for the display strings the page renders (Jekyll has no
# number-delimiter filter). Integer part only — latencies are small and unformatted.
def commafy: (. // 0 | floor | tostring) | gsub("(?<=\\d)(?=(\\d{3})+$)"; ",");

# Rungs the RUN ITSELF judged rig-valid, keyed by offered_rps. derive_saturation excludes
# a rung that was client-limited (k6 CPU, or short while the SUT had CPU headroom), that
# dropped iterations with an idle VU pool, or that returned errors, so it states no server
# figure the run stands behind. Everything below, headline and peak included, is computed
# over rig-valid rungs only. An artifact with no saturation.ladder (an older producer)
# carries no rig-validity to filter on, so all rungs are kept.
(.sweep.points // []) as $pts
| ((.saturation.ladder // []) | map(select(.rig_valid == true) | .offered_rps)) as $rv_offered
| (((.saturation.ladder // []) | length) == 0) as $no_rig_info
| ($pts | map(select(type == "object" and (.offered_rps != null) and (.achieved_rps != null)))
        | map(select($no_rig_info or (.offered_rps | IN($rv_offered[]))))
        | sort_by(.offered_rps)) as $s
# flat-region p50 = median p50 of the lowest (up to) four rungs, the part of the
# curve before any knee. Used only to decide where latency stops being flat.
| ([ $s[0:4][] | .p50_ms ] | map(select(. != null)) | sort) as $flat
| (($flat | length) as $n
   | if $n == 0 then null
     elif ($n % 2) == 1 then $flat[($n / 2 | floor)]
     else (($flat[$n/2 - 1] + $flat[$n/2]) / 2) end) as $flat_p50
| (if $flat_p50 == null then null else ($flat_p50 * $lat_mult) end) as $lat_thresh
# healthy rungs: kept up, no errors, latency still flat.
| [ $s[]
    | select(.offered_rps > 0
             and (.error_rate // 0) == 0
             and .achieved_rps >= ($keep * .offered_rps)
             and ($lat_thresh != null) and (.p50_ms != null) and (.p50_ms <= $lat_thresh)) ] as $healthy
| ($healthy | max_by(.offered_rps)) as $hc
# No rig-valid rung above the ceiling (or none at all) means no overload was measured, so
# no peak_* is published. It is a lower bound when, in addition, the next rung up was
# client-limited: the rig (or a non-CPU limit) stopped the ladder, not a measured overload.
| (if $hc == null then [] else [ (.saturation.ladder // [])[] | select(.offered_rps > $hc.offered_rps) ]
   | sort_by(.offered_rps) end) as $above
| ($above | all(.rig_valid != true)) as $no_measured_overload
| ($no_measured_overload and ($above | length) > 0 and ($above[0].client_limited == true)) as $lower_bound
# peak ACHIEVED rung across the whole ladder — the top of the overload curve.
| ($s | max_by(.achieved_rps)) as $pk
| .config as $c
| .agent as $a
# POST-FIX gate for per-behaviour percentiles. schema_version (item 0) and the
# regression.js tail fix (Finding 3, 2026-09-16) are INDEPENDENT axes — a run can
# be schema>=2 yet pre-fix (perf-test-run.sh notes the same), and a pre-fix run's
# p95 is a NON-NULL ~1014 ms client-side rig artefact. So a p95-presence check is
# NOT a fix check. Publish behaviours only when the run is provably post-fix:
#   * an explicit producer stamp `config.regression_js_fixed == true` (preferred,
#     forward-compatible for when the producer stamps it), OR
#   * a date floor on run_timestamp_utc >= $fix_date (the fix's merge date), which
#     is a sound floor because the stored timestamp is same-format ISO-8601 UTC so
#     lexicographic >= is chronological >=. Chosen because stamping the producer is
#     out of this unit's lane; the stamp path is honoured first for when it lands.
# A bare lexicographic >= fails OPEN on a malformed timestamp: "today" beats
#     "2026-..." because "t" > "2". Anchor the format first so an un-dateable run
#     is withheld rather than published.
| (($c.regression_js_fixed == true)
   or (((.timestamp_utc // "") | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}"))
       and ((.timestamp_utc // "") >= $fix_date))) as $postfix
| ((.behaviours // {}) | to_entries | map(select(.value.p95_ms != null))) as $beh_present
# ---- hardware matrix (item 27): throughput by cores x container memory ------
# Published from .serving_hw_matrix only when at least one point was measured with a
# healthy ceiling; null otherwise, and perf-website-publish.sh keeps the committed block.
# Ceilings are ladder rungs, so comparisons below are made in rungs, not percentages.
| ((.serving_hw_matrix.points // []) | sort_by(.cores, .memory_limit_bytes)) as $hwp
| (.serving_hw_matrix.other_containers_paused == true) as $hw_paused
| ((.serving_hw_matrix.sweep.rates // "") | tostring) as $hw_all_rates
| def memdisp: if . == null then null
    elif test("^[0-9]+[gG]$") then (sub("[gG]$"; "") + " GB")
    elif test("^[0-9]+[mM]$") then (sub("[mM]$"; "") + " MB")
    else . end;
  def mib: if . == null then null else (. / 1048576 | round) end;
  # the rungs this point was offered: its ladder, else its rate list, else the matrix ladder.
  def rates: . as $p
    | ([ ($p.ladder // [])[] | .offered_rps ]
       | if length > 0 then . else (($p.sweep_rates // $hw_all_rates) | split(",") | map(select(. != "") | tonumber)) end
       | sort);
  def rungidx($r): if $r == null then null else (rates | index([$r])) end;
  def reason($r): ((.lower_bound_reasons // []) | index($r)) != null;
  def hwnote: [
      (if .control == true then "control: same cores, more memory" else empty end),
      (if .status == "oom_killed" then "ran out of memory (container killed)\(if .died_at_offered_rps != null then " at about \(.died_at_offered_rps | commafy) req/s" elif .died_before_sweep == true then " before the test load started" else "" end)"
       elif .status == "sut_died" then "server stopped during the test"
       elif .status == "java_out_of_memory" then "Java heap ran out during the test"
       elif .status == "no_healthy_ceiling" then "no stable rate found"
       else empty end),
      (if reason("client_cpu_limited") then "may have been limited by the load generator — lower bound"
       elif reason("ladder_top_reached") then "above the highest rate tested — lower bound"
       elif reason("server_cpu_not_saturated") then "server CPU not saturated, may have been limited by the load generator — lower bound"
       elif reason("cpu_unverified") then "CPU use not measured, so the limit is unconfirmed — lower bound"
       else empty end) ] | join("; ");
  def history: {
      entry_cap: (.resolved.max_log_entries // null),
      entry_cap_display: (if (.resolved.max_log_entries // null) == null then null else (.resolved.max_log_entries | commafy) end),
      log_cap_mb: (.resolved.max_event_log_bytes | mib),
      entries_peak: (.event_log_retained_entries_peak // null),
      entries_peak_display: (if (.event_log_retained_entries_peak // null) == null then null else (.event_log_retained_entries_peak | commafy) end),
      filled: (.event_log_filled == true),
      binding_bound: (.event_log_binding_bound // null),
      body_bytes: (.retention.body_bytes // null) };
  ([ $hwp[] | select(.status == "measured" and .healthy_ceiling_rps != null) ]) as $hwok
| ([ $hwok[] | select((.lower_bound | not) and (.control | not)) | {cores, rpc: (.healthy_ceiling_rps / .cores)} ]
   | sort_by(.cores)) as $pcpts
| ([ $pcpts[] | .rpc ]) as $percore
| ([ $hwp[] | select(.control == true) ] | first) as $ctl
| (if $ctl == null then null
   else ([ $hwp[] | select((.control | not) and .cores == $ctl.cores
                           and (.memory_limit_bytes // 0) < ($ctl.memory_limit_bytes // 0)) ] | last) end) as $base
| (if ($hwok | length) == 0 then null else {
    source: {
      run_timestamp_utc: .timestamp_utc,
      build_number: (.build_number // null),
      build_url: (.build_url // null),
      commit: ((.commit // "") | .[0:10]),
      mockserver_version: ($c.mockserver_version // null),
      instance_type: ($a.instance_type // null),
      gc: ($c.gc // null),
      jdk: ($c.jdk // null),
      log_level: (.serving_hw_matrix.log_level // null),
      sweep_latency_settle_s: (.serving_hw_matrix.sweep.latency_settle_s // null),
      cpus_physically_verified: ([ $hwp[] | .cpus_physically_verified == true ] | all),
      other_containers_paused: $hw_paused,
      note: ("Each row is a fresh MockServer container pinned to that many CPU cores"
             + (if ([ $hwp[] | .cpus_physically_verified == true ] | all) and $hw_paused
                then " (each on its own physical core, with no other test container on it)" else "" end)
             + " and limited to that much memory, with the image default heap sizing (no -Xmx). The load generator runs on other cores. Logging is reduced below the shipped INFO default for measurement.")
    },
    points: [ $hwp[] | {
        key: (.key // ((.cores | tostring) + "c")),
        cores: .cores,
        memory_limit: .memory_limit,
        memory_display: (.memory_limit | memdisp),
        control: (.control == true),
        status: .status,
        max_heap_mib: (.resolved.max_heap_bytes // null | if . == null then null else (. / 1048576 | floor) end),
        history: history,
        memory_peak_pct: (if .container_memory_peak_frac_of_limit == null then null else (.container_memory_peak_frac_of_limit * 100 | round) end),
        healthy_ceiling_rps: .healthy_ceiling_rps,
        healthy_ceiling_rps_display: (if .healthy_ceiling_rps == null then null else (.healthy_ceiling_rps | commafy) end),
        healthy_ceiling_p50_ms: (if .healthy_ceiling_p50_ms == null then null else (.healthy_ceiling_p50_ms | round3) end),
        healthy_ceiling_p95_ms: (if .healthy_ceiling_p95_ms == null then null else (.healthy_ceiling_p95_ms | round3) end),
        rps_per_core: .rps_per_core,
        lower_bound: (.lower_bound == true),
        lower_bound_reasons: (.lower_bound_reasons // []),
        oom_killed: (.oom_killed == true),
        died_at_offered_rps: (.died_at_offered_rps // null),
        note: hwnote
      } ],
    skipped: [ (.serving_hw_matrix.skipped // []) | sort_by(.cores, (.memory_limit_bytes // 0)) | .[] | {
        key: (.key // ((.cores | tostring) + "c")), cores: .cores,
        memory_display: (.memory_limit | memdisp), type: .type, reason: .reason,
        oom_killed: (.oom_killed == true) } ],
    # From points that are neither controls nor lower bounds. scaling: "linear" when the
    # per-core rate stays within 20%; otherwise "falling"/"rising" only when it moves one
    # way across every size, else "mixed"; "single" with one core count.
    per_core: (if ($percore | length) == 0 then null else
      ($percore | length) as $n
      | ([ range(1; $n) | $percore[.] <= $percore[. - 1] ] | all) as $down
      | ([ range(1; $n) | $percore[.] >= $percore[. - 1] ] | all) as $up
      | {
        points_used: $n,
        min_display: ($percore | min | commafy), max_display: ($percore | max | commafy),
        smallest_cores: $pcpts[0].cores, largest_cores: $pcpts[-1].cores,
        smallest_display: ($pcpts[0].rpc | commafy), largest_display: ($pcpts[-1].rpc | commafy),
        scaling: (if ([ $pcpts[] | .cores ] | unique | length) < 2 then "single"
                  elif (($percore | min) / ($percore | max)) >= 0.8 then "linear"
                  elif $down and ($percore[-1] < $percore[0]) then "falling"
                  elif $up and ($percore[-1] > $percore[0]) then "rising"
                  else "mixed" end) } end),
    per_core_unavailable: (if ($percore | length) > 0 then null
                           elif ([ $hwok[] | select(.control | not) ] | length) > 0 then "lower_bound"
                           else "only_control" end),
    # The control against the same-core point with less memory, compared in ladder rungs.
    # Each ceiling can land one rung either way between runs, so a gap of up to two rungs
    # is within noise: only 0 rungs ("no_difference") or >= 3 up ("memory_helps") are stated.
    memory_effect: (if $ctl == null or $base == null then null else
      ($base | rungidx($base.healthy_ceiling_rps)) as $ib
      | ($base | rungidx($ctl.healthy_ceiling_rps)) as $ic
      | (if $ib == null or $ic == null then null else $ic - $ib end) as $drung
      | ($base | rates) as $lad
      | (if $ib == null or ($ib + 1) >= ($lad | length) then null
         else ((($lad[$ib + 1] - $lad[$ib]) / $lad[$ib]) * 100 | round) end) as $step
      | ([$base, $ctl] | map(.status == "measured" and .healthy_ceiling_rps != null) | all) as $both_measured
      | ([$base, $ctl] | map(.lower_bound == true) | any) as $any_lb
      | ($base | history) as $bh | ($ctl | history) as $ch
      | {
          cores: $ctl.cores,
          base_memory_display: ($base.memory_limit | memdisp),
          control_memory_display: ($ctl.memory_limit | memdisp),
          base_rps_display: (if $base.healthy_ceiling_rps == null then null else ($base.healthy_ceiling_rps | commafy) end),
          control_rps_display: (if $ctl.healthy_ceiling_rps == null then null else ($ctl.healthy_ceiling_rps | commafy) end),
          rung_difference: $drung,
          ladder_step_pct: $step,
          base_history: $bh, control_history: $ch,
          history_both_filled: ($bh.filled and $ch.filled),
          verdict: (if ($both_measured | not) or $any_lb or $drung == null then "inconclusive"
                    elif $drung == 0 then "no_difference"
                    elif $drung >= 3 then "memory_helps"
                    else "inconclusive" end),
          inconclusive_reason: (if ($both_measured | not) then "not_measured"
                                elif $any_lb then "lower_bound"
                                elif $drung == null then "unresolved"
                                elif $drung == 0 or $drung >= 3 then null
                                elif ($drung | fabs) <= 2 then "within_ladder_resolution"
                                elif $drung <= -3 then "slower_with_more_memory"
                                else null end)
        } end)
  } end) as $hw_matrix
| {
    # ---- provenance: every published figure carries what it was measured on --
    source: {
      published_utc: $now,
      run_timestamp_utc: .timestamp_utc,
      build_number: (.build_number // null),
      build_url: (.build_url // null),
      commit: ((.commit // "") | .[0:10]),
      mockserver_version: ($c.mockserver_version // null),
      instance_type: ($a.instance_type // null),
      server_cpus: ($a.server_cpus // null),
      gc: ($c.gc // null),
      heap_max_mib: (if ($c.heap_max_bytes // null) == null then null
                     else (($c.heap_max_bytes) / 1048576 | floor) end),
      jdk: ($c.jdk // null),
      log_level: ($c.log_level // null),
      disable_system_out: ($c.disable_system_out // null),
      image_digest: ($c.image_digest // null),
      # A self-describing run resolves these from the running JVM (item 0), so a
      # published provenance line is checkable rather than asserted.
      config_recorded: (($c // null) != null),
      # Seconds at the start of each rung excluded from its latency percentiles;
      # null = a run from before the onset exclusion (onset included).
      sweep_latency_settle_s: (.sweep.latency_window.settle_s // null),
      # The CI perf profile reduces logging below the shipped INFO default; the
      # page must say so rather than imply default-configuration figures.
      note: "Measured on the pinned CI perf host; the load generator runs on separate cores. Logging is reduced below the shipped INFO default for measurement (see log_level)."
    },
    # ---- headline: healthy ceiling FIRST, degraded peak clearly labelled ------
    headline: (if $hc == null then null else {
      healthy_ceiling_rps: ($hc.offered_rps),
      healthy_ceiling_rps_display: ($hc.offered_rps | commafy),
      healthy_ceiling_achieved_rps: ($hc.achieved_rps | round2),
      healthy_ceiling_p50_ms: ($hc.p50_ms | round3),
      healthy_ceiling_p95_ms: ($hc.p95_ms | round3),
      # No degraded peak to publish when no overload above the ceiling was measured.
      peak_achieved_rps: (if $pk == null or $no_measured_overload then null else ($pk.achieved_rps | round2) end),
      peak_achieved_rps_display: (if $pk == null or $no_measured_overload then null else ($pk.achieved_rps | commafy) end),
      peak_offered_rps: (if $pk == null or $no_measured_overload then null else ($pk.offered_rps) end),
      peak_p50_ms: (if $pk == null or $no_measured_overload then null else ($pk.p50_ms | round3) end),
      peak_p95_ms: (if $pk == null or $no_measured_overload then null else ($pk.p95_ms | round3) end),
      server_cores: ($a.server_cpus // null),
      no_measured_overload: $no_measured_overload,
      lower_bound: $lower_bound,
      lower_bound_reason: (if $lower_bound | not then null
        else "no rate above it was measured validly: from \($above[0].offered_rps | commafy) req/s offered the load generator, or a limit other than MockServer's CPU, cut delivery short, so a single instance may sustain more" end)
    } end),
    # ---- the full ladder, each rung flagged degraded past the healthy ceiling -
    throughput_ladder: [ $s[]
      | {
          offered_rps: .offered_rps,
          offered_display: (.offered_rps | commafy),
          achieved_rps: (.achieved_rps | round2),
          achieved_display: (.achieved_rps | commafy),
          p50_ms: (.p50_ms | round3),
          p95_ms: (.p95_ms | round3),
          p99_ms: (.p99_ms | round3),
          error_rate: (.error_rate // 0),
          error_pct: ((.error_rate // 0) * 100 | round),
          degraded: (($hc != null) and (.offered_rps > $hc.offered_rps))
        } ],
    # ---- per-behaviour percentiles — published ONLY from a post-fix run --------
    # null unless $postfix (see the gate above). A p95-presence check alone would
    # publish the pre-fix ~1014 ms artefact; the gate is the fix marker, not p95.
    behaviours: (if ($postfix and (($beh_present | length) > 0))
      then ($beh_present | map({
              key: .key,
              op: (.key | sub("_(https_h2|http)$"; "")),
              proto: (if (.key | endswith("_https_h2")) then "HTTPS + HTTP/2" else "HTTP/1.1" end),
              p50_ms: (.value.p50_ms | round3),
              p95_ms: (.value.p95_ms | round3),
              p99_ms: (.value.p99_ms | round3),
              throughput_rps: (.value.throughput_rps | round2)
            }))
      else null end),
    # ---- throughput by hardware size (null unless the run carries a matrix) ----
    hw_matrix: $hw_matrix,
    # Documentary keys the page and reviewers rely on — EMITTED here so an
    # auto-refresh never silently drops what the seed data file carries.
    behaviours_status: (if ($postfix | not)
      then "withheld: this run predates the regression.js tail fix (performance programme Finding 3, resolved 2026-09-16), so its per-behaviour p95/p99 are a client-side rig artefact, not server latency. Per-behaviour percentiles are published only from a post-fix run."
      elif (($beh_present | length) == 0)
      then "not measured this run (no behaviour arm carried a p95)"
      else "published from a post-fix regression.js run" end),
    withheld_internal: [
      "JMH allocation/time backstops (regression detectors, not absolute claims)",
      "growth/soak live-set slope and absolute",
      "event-log verification cost",
      "forward-pool guard",
      "AppCDS boolean",
      "ByteBuf leak gate",
      "streaming match-A/B ratio (measured against a deliberately constrained 1-CPU SUT — an internal tripwire, misleading as an absolute public figure)",
      "startup median-of-9",
      "baseline-freshness assertion"
    ]
  }
