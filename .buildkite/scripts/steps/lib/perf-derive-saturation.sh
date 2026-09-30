#!/usr/bin/env bash
# Rig validity per sweep rung (derive_saturation). Sourced by perf-test-run.sh and
# scripts/rw-multi-k6-sweep.sh so both methods share one rule; defines functions only.
# Reads caller globals: SWEEP_RATES, STEP_S, GAP_S, SETTLE_S, K6_PIN_PCT, K6_CORES,
# SWEEP_ERR_EPS, SWEEP_DROP_TOL, SWEEP_OCC_KNEE, and optionally K6_PHYS_CORES (null = no
# hyperthread adjustment) and SERVER_PIN_PCT (0 = no server-headroom test).

# --- derive saturation_rps from a sweep: judge each rung's rig validity ---
# Per rung, mean k6 CPU (and SUT CPU from the optional server_cpu_log, which must sample the
# SUT the sweep targeted) over the steady window from k6's start_epoch_ms, else t0's schedule,
# with drops, pool occupancy and errors. Echoes the SATURATION_JSON object on stdout. Rules
# and their limits: docs/code/performance-measurement.md#sweepjs--throughput-vs-latency-knee
derive_saturation() { # sweep_json_host_path  cpu_log_host_path  t0_epoch  [server_cpu_log]
  local sweep_json="$1" cpu_log="$2" t0="$3" srv_log="${4:-}"
  local cpu_map="{}" srv_map="{}" i r ws we cpustats cpumean cpumax srvstats rs
  local -a rate_arr
  IFS=',' read -ra rate_arr <<< "$SWEEP_RATES"
  for i in "${!rate_arr[@]}"; do
    r="${rate_arr[$i]}"
    # Both CPU windows start at k6's own rung start when the sweep records it, else at t0's
    # schedule, so the k6 and server tests judge the same seconds.
    rs="$(jq -r --argjson i "$i" '.points[$i].start_epoch_ms // empty | floor / 1000 | floor' "$sweep_json" 2>/dev/null || true)"
    [ -n "$rs" ] || rs=$(( t0 + i * (STEP_S + GAP_S) ))
    ws=$(( rs + SETTLE_S ))
    we=$(( rs + STEP_S ))
    cpustats="$(awk -F',' -v a="$ws" -v b="$we" 'NR>1 && $1>=a && $1<b { s+=$2+0; n++; if($2+0>m) m=$2+0 } END{ if(n>0) printf "%.1f %.1f", s/n, m+0; else printf "0 0" }' "$cpu_log" 2>/dev/null || echo "0 0")"
    cpumean="${cpustats%% *}"; cpumax="${cpustats##* }"
    cpu_map="$(jq -c --arg k "$r" --argjson mean "${cpumean:-0}" --argjson max "${cpumax:-0}" '. + {($k): {mean:$mean, max:$max}}' <<<"$cpu_map")"
    if [ -n "$srv_log" ] && [ -s "$srv_log" ]; then
      # Fewer than two samples leaves the rung unmeasured (null), not judged on one reading.
      srvstats="$(awk -F',' -v a="$ws" -v b="$we" 'NR==1 { for (k=1; k<=NF; k++) if ($k=="cpu_pct") c=k; next }
        c && $1>=a && $1<b && $c!="" { s+=$c; n++ } END{ if(n>=2) printf "%.1f %d", s/n, n; else printf "null %d", n }' "$srv_log" 2>/dev/null || echo "null 0")"
      srv_map="$(jq -c --arg k "$r" --argjson mean "${srvstats%% *}" --argjson n "${srvstats##* }" '. + {($k): {mean:$mean, n:$n}}' <<<"$srv_map")"
    fi
  done
  # RIG-VALID: not client-limited (below), drops forgiven only with a pinned pool or under
  # $drop_tol, and errors under $err_eps. rig_valid_peak_achieved_rps (budgeted) is the max
  # achieved over rig-valid rungs, so it is ladder-quantised and capped at the client knee;
  # saturation_rps (highest clean rung) is descriptive only. perf-website-figures.jq
  # publishes rig-valid rungs only and nulls peak_* when no overload above was measured.
  #
  # CLIENT-LIMITED (rig-invalid): k6 mean CPU above 85% of its capacity (a hyperthread
  # sibling counts as $ht_yield of a core), OR the rung dropped over $drop_tol while the SUT
  # was under 85% of its pin and k6 was at least half its ceiling, or a lower rung already
  # qualified and k6 is still at a quarter of it (k6 CPU falls past its own knee). A non-CPU
  # server limit also passes the second test; see performance-measurement.md.
  jq -n \
    --slurpfile sweep "$sweep_json" \
    --argjson cpu "$cpu_map" \
    --argjson srv "$srv_map" \
    --argjson pin "$K6_PIN_PCT" \
    --argjson cores "$K6_CORES" \
    --argjson phys "${K6_PHYS_CORES:-null}" \
    --argjson srv_pin "${SERVER_PIN_PCT:-0}" \
    --argjson ht_yield 0.25 \
    --argjson err_eps "$SWEEP_ERR_EPS" \
    --argjson drop_tol "$SWEEP_DROP_TOL" \
    --argjson occ_knee "$SWEEP_OCC_KNEE" '
    (if ($phys | type) == "number" and $phys > 0 and $phys < $cores
     then ((100 * $phys) + ($ht_yield * 100 * ($cores - $phys))) as $cap
          | if (0.85 * $cap) <= (100 * $phys) then 0.85 * $cap
            else (100 * $phys) + ((0.85 * $cap) - (100 * $phys)) / $ht_yield end
     else $pin * 0.85 end) as $cpu_ceiling
    | (($sweep[0].points) // []) as $points
    | (($sweep[0].vus_diagnostics.pool_per_rung) // {}) as $pools
    | ($srv_pin * 0.85) as $srv_ceiling
    | def srv_cpu: $srv[(.offered_rps|tostring)].mean;
      def dfrac: (.dropped_iterations // 0) as $d | (.sample_count // 0) as $n
                 | if ($d + $n) > 0 then $d / ($d + $n) else 0 end;
      def k6cpu: $cpu[(.offered_rps|tostring)].mean;
      def srv_headroom: ($srv_pin > 0) and (srv_cpu != null) and (srv_cpu < $srv_ceiling);
      def short: dfrac > $drop_tol;
      def k6_saturated: ($cores > 0) and (k6cpu != null) and (k6cpu > $cpu_ceiling);
      def client_short: short and srv_headroom;
    ([ $points[] | select(k6_saturated or (client_short and ($cores > 0) and (k6cpu != null)
                                            and (k6cpu >= 0.5 * $cpu_ceiling)))
       | .offered_rps ] | min) as $client_limit_from
    | [ $points[]
        | ($cpu[(.offered_rps|tostring)]) as $cobj
        # Gate on the MEAN k6 CPU over the steady window, record the max alongside.
        # A single docker-stats spike (or the container-startup cold read) must not
        # read as sustained client saturation - see the sampler + awk above.
        | ($cobj.mean) as $c
        | ($cobj.max)  as $cmax
        | (.dropped_iterations // 0) as $drops
        | (.sample_count // 0) as $completed
        | (.error_rate // 0) as $err
        | (.offered_rps) as $off | (.achieved_rps // 0) as $ach
        | (.vus_active_max) as $vmax
        | (.vus_active_p95) as $vp95
        | ($pools[($off|tostring)]) as $pool
        | (k6_saturated | not) as $cpu_headroom
        | (srv_cpu) as $sc
        | (client_short and ($client_limit_from != null) and ($off >= $client_limit_from)
           and ($c != null) and ($c >= 0.25 * $cpu_ceiling)) as $client_short_limited
        | ($cpu_headroom and ($client_short_limited | not)) as $headroom
        # Drop fraction = drops / (drops + completed). A dropped iteration is one the
        # constant-arrival-rate executor could not launch because no VU was free, so
        # (drops + completed) is the intended iteration count and this is the exact
        # fraction of the offered load the client failed to deliver.
        | (if ($drops + $completed) > 0 then ($drops / ($drops + $completed)) else 0 end) as $drop_frac
        # VU-pool OCCUPANCY = p95 active VUs / pool. p95 (not max) because vus_active_max
        # is RIGHT-CENSORED at the pool: a single stall pileup touching the ceiling makes
        # any rung look pool-bound however idle it was for the bulk of the window (build
        # build 419 with p95=9 against pool 640). Falls back to max only when p95 is absent (older
        # artifact); null when neither p95/max nor a pool is available.
        | (if ($vp95 != null) and ($pool != null) and ($pool > 0) then ($vp95 / $pool)
           elif ($vmax != null) and ($pool != null) and ($pool > 0) then ($vmax / $pool)
           else null end) as $occ
        # Pool PINNED = occupancy at/above the knee threshold: the pool was the binding
        # constraint, so VUs were blocked waiting on SERVER responses. Drops there are the
        # server saturating, not the client failing to schedule.
        | (($occ != null) and ($occ >= $occ_knee)) as $pool_pinned
        # $no_drops (rig-validity drop clause). A dropped iteration is a client failure to
        # SCHEDULE only when the pool was NOT the constraint. So: no drops -> fine; drops
        # with the pool pinned -> SERVER-limited, keep (the knee this sweep exists to find);
        # drops with pool headroom -> forgive only a small FRACTION (a blip); no occupancy
        # signal (older artifact, no pool) -> STRICT zero-drop, never lenient on drops we
        # cannot corroborate.
        | (if $drops <= 0 then true
           elif $pool_pinned then true
           elif ($occ != null) then ($drop_frac <= $drop_tol)
           else false end) as $no_drops
        # knee = a KEPT rung whose drops were server-limited (pool pinned). Purely a label.
        | (($drops > 0) and $pool_pinned) as $knee
        | ($err <= $err_eps) as $low_err
        # rig_valid: the measurement itself is trustworthy (says nothing about the
        # server verdict). clean: rig_valid AND the server actually kept up (knee).
        | ($headroom and $no_drops and $low_err) as $rig_valid
        | ($rig_valid and ($off > 0) and ($ach >= 0.95 * $off)) as $clean
        | { offered_rps:$off, achieved_rps:$ach, k6_cpu_pct:$c, k6_cpu_pct_max:$cmax,
            server_cpu_pct:$sc, server_cpu_samples:($srv[($off|tostring)].n // 0), client_limited:($headroom|not),
            dropped_iterations:$drops, dropped_fraction:($drop_frac|.*100000|round/100000),
            vus_active_max:$vmax, vus_active_p95:$vp95, pool_per_rung:$pool,
            vu_occupancy:(if $occ == null then null else ($occ*100000|round/100000) end),
            error_rate:$err, rig_valid:$rig_valid, clean:$clean, knee:($rig_valid and $knee),
            exclude_reason:(
              if $rig_valid then null
              elif ($cpu_headroom|not) then "k6 client CPU mean \($c)% (max \($cmax)%) > its \($cpu_ceiling|round)% ceiling (85% of capacity on a \($pin)% pin; client bottleneck)"
              elif ($headroom|not) then "short with server CPU headroom; client or non-CPU limit (unattributed): dropped \(($drop_frac*1000|round)/10)% of offered, server CPU \($sc)% of its \($srv_pin)% pin (< 85%), k6 CPU \($c)% of \($pin)%, from the \($client_limit_from) rung up"
              elif ($no_drops|not) then
                "k6 dropped \($drops) iterations = \(($drop_frac*1000|round)/10)% of offered"
                + (if ($occ != null) then " with VU pool only \(($occ*1000|round)/10)% occupied at p95 (< \(($occ_knee*100))% knee) but above the \(($drop_tol*100))% blip tolerance - client could not schedule, not the server saturating"
                   else " (no VU-pool diagnostics to corroborate a server-side knee; strict zero-drop applied)" end)
              else "server error_rate \($err) > \($err_eps) (fast errors inflate achieved)" end) } ]
    | . as $rungs
    | ([ $rungs[] | select(.rig_valid) | .achieved_rps ] | max // 0) as $peak
    | ([ $rungs[] | select(.clean) | .offered_rps ] | max // 0) as $sat
    | ([ $rungs[] | select(.rig_valid) ] | length) as $rig_valid_rungs
    | { rig_valid_peak_achieved_rps:$peak, saturation_rps:$sat,
        rig_valid_rungs:$rig_valid_rungs,
        client_pin_pct:$pin, client_cores:$cores,
        client_cpu_ceiling_pct:($cpu_ceiling*10|round/10), server_pin_pct:$srv_pin,
        client_limited_from_rps:$client_limit_from,
        server_headroom_test:(if ($srv_pin <= 0) or ($srv | length) == 0 then "off"
          elif all($points[]; (srv_cpu) != null) then "active" else "partial" end),
        ladder:$rungs,
        excluded:[ $rungs[] | select(.rig_valid|not)
                   | {offered_rps, achieved_rps, k6_cpu_pct, k6_cpu_pct_max, server_cpu_pct, dropped_iterations, dropped_fraction, vus_active_max, vus_active_p95, pool_per_rung, vu_occupancy, error_rate, reason:.exclude_reason} ] }'
}
