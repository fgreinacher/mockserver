#!/usr/bin/env bash
# Rig validity per sweep rung (derive_saturation). Sourced by perf-test-run.sh and
# scripts/rw-multi-k6-sweep.sh so both methods share one rule; defines functions only.
# Reads caller globals: SWEEP_RATES, STEP_S, GAP_S, SETTLE_S, K6_PIN_PCT, K6_CORES,
# SWEEP_ERR_EPS, SWEEP_DROP_TOL, SWEEP_OCC_KNEE.

# --- derive saturation_rps from a sweep (item: prove the client had headroom) ---
# Per rung: attribute the MEAN k6-container CPU seen during that rung's hold window
# (from the sampler log + the known ladder schedule anchored at $t0), pair it with
# k6's per-rung dropped_iterations, and mark the rung CLEAN iff achieved >=
# 0.95*offered AND the client had CPU headroom (mean < 85% of its pin) AND k6's
# drops were not client-limited (see $no_drops). The gate is the MEAN, not the max:
# a single inflated docker-stats sample (or the container-startup cold read) must
# not read as sustained client saturation. The per-rung max is recorded ALONGSIDE
# so a reader can see the spread. The highest CLEAN rung's offered rate is
# saturation_rps. If nothing is rig-valid, the client was the bottleneck everywhere
# (the caller flags the run invalid). Echoes the SATURATION_JSON object on stdout.
derive_saturation() { # sweep_json_host_path  cpu_log_host_path  t0_epoch
  local sweep_json="$1" cpu_log="$2" t0="$3"
  local cpu_map="{}" i r ws we cpustats cpumean cpumax
  local -a rate_arr
  IFS=',' read -ra rate_arr <<< "$SWEEP_RATES"
  for i in "${!rate_arr[@]}"; do
    r="${rate_arr[$i]}"
    ws=$(( t0 + i * (STEP_S + GAP_S) + SETTLE_S ))
    we=$(( t0 + i * (STEP_S + GAP_S) + STEP_S ))
    cpustats="$(awk -F',' -v a="$ws" -v b="$we" 'NR>1 && $1>=a && $1<=b { s+=$2+0; n++; if($2+0>m) m=$2+0 } END{ if(n>0) printf "%.1f %.1f", s/n, m+0; else printf "0 0" }' "$cpu_log" 2>/dev/null || echo "0 0")"
    cpumean="${cpustats%% *}"; cpumax="${cpustats##* }"
    cpu_map="$(jq -c --arg k "$r" --argjson mean "${cpumean:-0}" --argjson max "${cpumax:-0}" '. + {($k): {mean:$mean, max:$max}}' <<<"$cpu_map")"
  done
  # A rung is RIG-VALID when the measurement itself is trustworthy: the k6 client
  # had CPU headroom (MEAN k6 CPU below its pin), its drops were not a client-side
  # scheduling failure (either the pool was pinned — a server-side knee — or the
  # drops were a small tolerated FRACTION with pool headroom; see $no_drops), and the
  # server was not returning fast errors (a rung "achieves" its offered rate even
  # while erroring, so error_rate is part of validity, not just throughput).
  # The BUDGETED metric is rig_valid_peak_achieved_rps = max achieved over rig-valid rungs.
  #
  # rig_valid_peak_achieved_rps CAN now track the server ceiling. It could not before: the
  # old criteria excluded a rung the moment the k6 CPU MAX touched its pin (a startup/
  # docker-stats artifact) or the moment drops appeared with the VU pool merely non-empty,
  # so the peak was structurally capped at the first client blip (~2,000 on this rig) even
  # while the server served far more. Gating CPU on the MEAN and forgiving drops that occur
  # with the VU pool PINNED removes that cap, so this field rises with the highest rung the
  # server actually served. It is still a rig-valid PEAK, not a promise the server was clean
  # at that rung (see saturation_rps for the clean knee).
  #
  # BEWARE: two quantities share this name. THIS one is the rig-valid peak.
  # lib/perf-website-figures.jq independently recomputes max achieved over ALL rungs from
  # the raw sweep points, and that is what is published to the website. Same name, different
  # subject, different consumer — do not reconcile one against the other.
  #
  # saturation_rps is ladder-QUANTISED (only ever a rung's offered value) and is kept as a
  # DESCRIPTIVE figure only (the highest CLEAN knee), not budgeted.
  jq -n \
    --slurpfile sweep "$sweep_json" \
    --argjson cpu "$cpu_map" \
    --argjson pin "$K6_PIN_PCT" \
    --argjson cores "$K6_CORES" \
    --argjson err_eps "$SWEEP_ERR_EPS" \
    --argjson drop_tol "$SWEEP_DROP_TOL" \
    --argjson occ_knee "$SWEEP_OCC_KNEE" '
    ($pin * 0.85) as $cpu_ceiling
    | (($sweep[0].points) // []) as $points
    | (($sweep[0].vus_diagnostics.pool_per_rung) // {}) as $pools
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
        | (($cores <= 0) or ($c == null) or ($c <= $cpu_ceiling)) as $headroom
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
            dropped_iterations:$drops, dropped_fraction:($drop_frac|.*100000|round/100000),
            vus_active_max:$vmax, vus_active_p95:$vp95, pool_per_rung:$pool,
            vu_occupancy:(if $occ == null then null else ($occ*100000|round/100000) end),
            error_rate:$err, rig_valid:$rig_valid, clean:$clean, knee:($rig_valid and $knee),
            exclude_reason:(
              if $rig_valid then null
              elif ($headroom|not) then "k6 client CPU mean \($c)% (max \($cmax)%) >= 85% of \($pin)% pin (client bottleneck)"
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
        ladder:$rungs,
        excluded:[ $rungs[] | select(.rig_valid|not)
                   | {offered_rps, achieved_rps, k6_cpu_pct, k6_cpu_pct_max, dropped_iterations, dropped_fraction, vus_active_max, vus_active_p95, pool_per_rung, vu_occupancy, error_rate, reason:.exclude_reason} ] }'
}
