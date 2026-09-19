// Throughput-vs-latency sweep — measures the "knee" of MockServer's load curve.
//
// Offers load at an ascending LADDER of fixed arrival rates (K6_SWEEP_RATES) and
// records, per rate step, the ACHIEVED throughput, latency percentiles
// (p50/p90/p95/p99/p99.9), and error rate. This series is what we plot as a
// load-vs-latency knee curve on the documentation site, so the JSON output shape
// is a hard contract (see handleSummary).
//
// Each rate is one constant-arrival-rate scenario, staggered after the previous
// (startTime = sum of prior step+gap durations) with a short quiet gap between
// steps so one step's tail latency does not bleed into the next step's
// percentiles. Every request is tagged rate:<offered> so the per-step
// http_req_duration / http_req_failed / http_reqs submetrics are computed in the
// summary. There are deliberately NO aborting thresholds — at the top of the
// ladder k6 may drop iterations (VU-starved) and latency/errors may degrade
// sharply; observing that degradation IS the point.
//
//   k6 run mockserver-performance-test/k6/sweep.js
//   k6 run -e K6_SWEEP_RATES=200,500,1000 -e K6_SWEEP_STEP=8s \
//     -e K6_SWEEP_RESULT_PATH=/tmp/sweep-result.json .../sweep.js
//
// ---------------------------------------------------------------------------
// VU-POOL DIAGNOSTICS (performance-programme item 18 open question).
// The per-core serving curve pins flat because at the 8,000-rps rung the server
// delivers ~0.93-0.94 of offered — just under the 0.95 ceiling rule — everywhere,
// and the shortfall is `dropped_iterations` (k6 arrival-rate iterations that never
// started because no VU was free). Little's law says the VU demand at the lower
// rungs is tiny (one or two VUs out of a 200-VU pool), yet those rungs STILL drop
// iterations. A pool shortage is arithmetically impossible there, so the drops are
// unexplained; the standing hypothesis is a transient server stall (the same rungs
// show p99.9 of 66-90 ms against a 0.18 ms p50) that blocks the in-flight VUs while
// the arrival rate keeps producing iterations.
//
// To let the NEXT run settle it WITHOUT changing what is measured, each rung also
// records (all additive fields; existing consumers ignore unknown keys):
//   * vus_active_*        — the CONCURRENCY the rung actually used (were the other
//                           198 VUs really idle?). Sampled from k6's execution API
//                           at the start of every iteration, tagged by rung. If a
//                           rung's peak stays below preAllocatedVUs, its pool was
//                           never the constraint (idle VUs), so a pool shortage
//                           cannot explain its drops.
//   * vus_diagnostics     — a WHOLE-RUN block (not per rung): did the initialized
//                           VU pool ever grow past its baseline, and how much
//                           concurrency did the whole ladder ever demand? Pool
//                           growth is a whole-test property here because k6
//                           pre-initializes EVERY staggered arrival-rate scenario's
//                           preAllocatedVUs up front, so the global initialized
//                           count is (rung count x preAllocatedVUs) from the start;
//                           growth is any excess above that baseline.
//   * stalls / stall_*    — count of deep-tail requests (> K6_SWEEP_STALL_MS) and
//                           the VU concurrency at those moments — the stall
//                           hypothesis's own signature.
//   * stall_time_buckets  — WHEN within the rung the stalls fell (clustered => a
//                           transient stall; uniform => a steady limit). This is
//                           a proxy for drop timing: k6 CANNOT timestamp a dropped
//                           iteration (a drop never runs VU code, by definition),
//                           but if drops are stall-driven they cluster when stalls
//                           cluster, and stalls DO run VU code.
// DELIBERATELY NOT CHANGED: preAllocatedVUs / maxVUs. Measure before changing — if
// the pool never actually grows, equalising it would "fix" a mechanism that is not
// firing. (regression.js / clustered_crossing.js keep preAllocatedVUs==maxVUs on
// Finding-3 grounds; that equalisation is a SEPARATE unit, justified on invariant
// grounds, not on anything this harness has yet shown.)
// ---------------------------------------------------------------------------
import { Trend, Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { CONFIG, SWEEP } from './lib/config.js';
import { seedExpectations, resetMockServer, getSimple } from './lib/expectations.js';

// Sum a k6 duration string to seconds (supports compound forms like "1m30s").
function toSeconds(d) {
  const str = String(d).trim();
  const tokenRe = /(\d+)(ms|s|m|h)/g;
  let total = 0;
  let matched = false;
  let token;
  while ((token = tokenRe.exec(str)) !== null) {
    matched = true;
    const value = Number(token[1]);
    total += value * { ms: 0.001, s: 1, m: 60, h: 3600 }[token[2]];
  }
  if (!matched) {
    throw new Error(`toSeconds: cannot parse duration "${d}"`);
  }
  return total;
}

function round(v, dp = 3) {
  if (v === undefined || v === null || Number.isNaN(v)) {
    return null;
  }
  const f = 10 ** dp;
  return Math.round(v * f) / f;
}

const RATES = SWEEP.rates;
const STEP_SECONDS = toSeconds(SWEEP.step);
const GAP_SECONDS = toSeconds(SWEEP.gap);

// --- VU-pool diagnostics tunables --------------------------------------------
// A request slower than STALL_MS is counted as a "stall" — deep-tail latency well
// above the ~0.18 ms p50, the observable signature of the hypothesised transient
// server stall. Diagnostic only; never gates.
const STALL_MS = Number(__ENV.K6_SWEEP_STALL_MS || 5);
// Each rung is divided into this many equal WALL-TIME buckets so stall timing
// WITHIN a rung is legible (clustered vs uniform). Kept small — cardinality is
// rungs x buckets.
const TIME_BUCKETS = Math.max(1, Math.trunc(Number(__ENV.K6_SWEEP_TIME_BUCKETS || 6)) || 6);
const BUCKET_WIDTH_MS = TIME_BUCKETS > 0 ? (STEP_SECONDS * 1000) / TIME_BUCKETS : STEP_SECONDS * 1000;
// offered-rate -> ladder index, to recover a rung's scheduled start offset so the
// stall time-bucket can be computed from the test clock.
const RATE_INDEX = new Map(RATES.map((r, i) => [r, i]));

// Custom metrics. Trend/Counter samples carry their OWN tags (2nd arg to .add),
// independent of request tags, so tagging these by rung does NOT perturb the
// http_req_* submetrics the knee chart depends on.
const vusActiveTrend = new Trend('sweep_vus_active'); // active VUs at iteration start
const stallCounter = new Counter('sweep_stalls'); // requests slower than STALL_MS
const stallConcurrencyTrend = new Trend('sweep_stall_concurrency'); // active VUs at a stall
const stallBucketCounter = new Counter('sweep_stalls_bucketed'); // stalls by time-in-rung bucket

// Build one constant-arrival-rate scenario per ladder rung, staggered so they run
// back-to-back (step + gap) rather than concurrently. The rate-tagged submetrics
// let handleSummary compute per-step percentiles.
function buildScenarios() {
  const scenarios = {};
  RATES.forEach((rate, i) => {
    const startTime = i * (STEP_SECONDS + GAP_SECONDS);
    scenarios[`rate_${rate}`] = {
      executor: 'constant-arrival-rate',
      exec: 'matchAt',
      rate,
      timeUnit: '1s',
      duration: `${STEP_SECONDS}s`,
      startTime: `${startTime}s`,
      preAllocatedVUs: SWEEP.preAllocatedVUs,
      maxVUs: SWEEP.maxVUs,
      // Pass the offered rate to the exec fn via env-free scenario tag is not
      // possible, so each scenario gets its own exec wrapper via the tag below.
      tags: { rate: String(rate) },
      env: { SWEEP_RATE: String(rate) },
    };
  });
  return scenarios;
}

// Materialise the per-step submetrics in the summary by declaring (always-true,
// non-aborting) thresholds. The >=0 expressions never fail (notify-only) but
// force k6 to compute and expose p(50)/p(90)/p(95)/p(99)/p(99.9), the failed
// rate, and the request count per rate tag so handleSummary can read them.
function sweepThresholds() {
  const t = {};
  for (const rate of RATES) {
    t[`http_req_duration{rate:${rate}}`] = ['p(50)>=0', 'p(90)>=0', 'p(95)>=0', 'p(99)>=0', 'p(99.9)>=0'];
    t[`http_req_failed{rate:${rate}}`] = ['rate>=0'];
    t[`http_reqs{rate:${rate}}`] = ['count>=0'];
    // dropped_iterations is k6's own "the client could not launch this iteration
    // on time" counter (VU-starved / arrival-rate not met). Materialise it per
    // rung (scenario tag rate_<rate>) so handleSummary can report whether the
    // CLIENT fell behind at each offered rate — a rung with drops is one where
    // k6, not MockServer, ran out of headroom, so its achieved throughput is a
    // client ceiling and must be EXCLUDED from the derived saturation point.
    t[`dropped_iterations{scenario:rate_${rate}}`] = ['count>=0'];
    // VU-pool diagnostics submetrics (item 18 open question). Same materialise-
    // via-threshold trick; all notify-only.
    t[`sweep_vus_active{rate:${rate}}`] = ['max>=0', 'avg>=0', 'p(95)>=0', 'med>=0'];
    t[`sweep_stalls{rate:${rate}}`] = ['count>=0'];
    t[`sweep_stall_concurrency{rate:${rate}}`] = ['max>=0', 'avg>=0'];
    // One submetric per (rung, time-bucket). A single combined `slot` tag
    // ("<rate>_<bucket>") is used deliberately instead of two tags so the
    // threshold key matches the read-back key verbatim regardless of how k6
    // orders multi-tag submetric names.
    for (let b = 0; b < TIME_BUCKETS; b += 1) {
      t[`sweep_stalls_bucketed{slot:${rate}_${b}}`] = ['count>=0'];
    }
  }
  return t;
}

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  // handleSummary reads these stats off each submetric's `values`; p(50)/p(99)/
  // p(99.9) are NOT in k6's default trend set, so declare them or they come back
  // null.
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)', 'max'],
  scenarios: buildScenarios(),
  thresholds: sweepThresholds(),
};

export function setup() {
  seedExpectations();
}

// Single exec fn for every rung; the scenario's env.SWEEP_RATE supplies the
// offered-rate tag so the request lands in this step's submetric.
export function matchAt() {
  const rate = __ENV.SWEEP_RATE;
  const rateTag = { rate };
  // Sample active VUs at iteration START. `vusActive` is the process-global count
  // of VUs currently running iterations; because the rungs are staggered with quiet
  // gaps, during a rung only THIS scenario is active, so the sample reflects this
  // rung's own concurrency. Sampling per-iteration (rather than reading k6's 1 Hz
  // `vus` gauge) is deliberate: a stall-driven pile-up lasts only a few ms and the
  // 1 Hz gauge misses it, so this is the sensitive instrument for the stall
  // question. (Pool-SIZE growth is a whole-test property — see vus_diagnostics in
  // handleSummary — not something a per-rung sample can isolate, because k6
  // pre-initializes every staggered scenario's pool up front.)
  const active = exec.instance.vusActive;
  vusActiveTrend.add(active, rateTag);

  const res = getSimple({ rate });

  // Stall accounting: a request slower than STALL_MS is deep-tail latency — the
  // observable signature of the hypothesised transient stall. Record its count,
  // the VU concurrency observed AT the stall (does a stall coincide with many VUs
  // piled up?), and WHEN within the rung it fell (clustered vs uniform).
  const duration = res && res.timings ? res.timings.duration : 0;
  if (duration > STALL_MS) {
    stallCounter.add(1, rateTag);
    // Re-read vusActive HERE rather than reusing the iteration-start `active`. A
    // pile-up builds DURING the slow request, so the iteration-start reading
    // understates it — and a field named stall_concurrency must measure
    // concurrency at the stall, not concurrency at the start of the iteration
    // that later stalled. Only costs a read on the (rare) stall path.
    stallConcurrencyTrend.add(exec.instance.vusActive, rateTag);
    const idx = RATE_INDEX.has(Number(rate)) ? RATE_INDEX.get(Number(rate)) : 0;
    const offsetMs = idx * (STEP_SECONDS + GAP_SECONDS) * 1000;
    let bucket = Math.floor((exec.instance.currentTestRunDuration - offsetMs) / BUCKET_WIDTH_MS);
    if (bucket < 0) {
      bucket = 0;
    } else if (bucket >= TIME_BUCKETS) {
      bucket = TIME_BUCKETS - 1;
    }
    stallBucketCounter.add(1, { slot: `${rate}_${bucket}` });
  }
}

export function teardown() {
  resetMockServer();
}

// Emit the machine-readable result consumed by the knee-curve chart. Per rung:
//   offered_rps  — the configured arrival rate for the step
//   achieved_rps — completed requests for that step / step duration (seconds)
//   p*_ms        — latency percentiles from that step's tagged submetric
//   error_rate   — failed-request fraction for that step (0..1)
// Also returns the standard k6 text summary on stdout.
export function handleSummary(data) {
  const points = [];
  for (const rate of RATES) {
    const dur = data.metrics[`http_req_duration{rate:${rate}}`];
    const failed = data.metrics[`http_req_failed{rate:${rate}}`];
    const reqs = data.metrics[`http_reqs{rate:${rate}}`];
    const dropped = data.metrics[`dropped_iterations{scenario:rate_${rate}}`];
    const count = reqs && reqs.values ? reqs.values.count : 0;
    const v = dur && dur.values ? dur.values : {};

    // VU-pool diagnostics for this rung.
    const va = data.metrics[`sweep_vus_active{rate:${rate}}`];
    const vaV = va && va.values ? va.values : {};
    const stalls = data.metrics[`sweep_stalls{rate:${rate}}`];
    const sc = data.metrics[`sweep_stall_concurrency{rate:${rate}}`];
    const scV = sc && sc.values ? sc.values : {};
    const stallBuckets = [];
    for (let b = 0; b < TIME_BUCKETS; b += 1) {
      const bm = data.metrics[`sweep_stalls_bucketed{slot:${rate}_${b}}`];
      stallBuckets.push(bm && bm.values ? round(bm.values.count, 0) : 0);
    }

    points.push({
      offered_rps: rate,
      achieved_rps: round(STEP_SECONDS > 0 ? count / STEP_SECONDS : 0, 1),
      p50_ms: round(v['p(50)'] !== undefined ? v['p(50)'] : v.med),
      p90_ms: round(v['p(90)']),
      p95_ms: round(v['p(95)']),
      p99_ms: round(v['p(99)']),
      p999_ms: round(v['p(99.9)']),
      // Completed-request count for THIS rung. Additive field (older/other
      // consumers ignore unknown keys; the website renderer's key-presence check
      // does not include it). It lets a downstream aggregator apply the repo's
      // MIN_TAIL_SAMPLES rule (regression.js/proxy.js/streaming.js) and suppress a
      // tail percentile a rung's own sample count cannot support — the per-core
      // serving curve (perf-percore.sh, item 18) needs this because its low-C, low
      // arrival-rate rungs can dip below that floor. The percentiles above are left
      // UNSUPPRESSED here so the published knee/percentile charts keep their exact
      // contract; suppression is applied by the consumer that needs it.
      sample_count: round(count, 0),
      error_rate: failed && failed.values ? round(failed.values.rate, 5) : 0,
      // Client-side drop count for this rung: > 0 means k6 could not keep up
      // with the offered arrival rate (VU starvation), so achieved_rps is bounded
      // by the CLIENT and this rung is not a valid server-ceiling candidate.
      dropped_iterations: dropped && dropped.values ? round(dropped.values.count, 0) : 0,
      // --- VU-pool diagnostics (item 18). All additive; see the header block. ---
      // vus_active_* : the CONCURRENCY this rung actually used, sampled at the
      //   start of every iteration in this rung. `max` is the peak number of VUs
      //   simultaneously in flight; if it stays ~1-2 while preAllocatedVUs is 200,
      //   the other ~198 VUs were genuinely idle and a pool shortage CANNOT explain
      //   the rung's drops. Conversely, a max ABOVE preAllocatedVUs is the only
      //   per-rung proof that THIS rung's executor grew its pool. (Possible off-by-one:
      //   whether vusActive counts the sampling VU itself has NOT been verified
      //   here. It does not matter for the question this field exists to answer —
      //   1-2 against a pool of 200 reads the same either way — so it is recorded
      //   as unknown rather than asserted in one direction.)
      vus_active_max: round(vaV.max, 1),
      vus_active_p95: round(vaV['p(95)'], 1),
      vus_active_avg: round(vaV.avg, 1),
      // stalls : requests in this rung slower than stall_ms_threshold. stall_*
      //   concurrency is the active-VU count AT those moments (a stall coinciding
      //   with high concurrency = VUs piling up behind a slow response, the drop
      //   mechanism). stall_time_buckets splits the rung into equal wall-time
      //   windows (earliest first) and counts stalls per window: a burst in one
      //   window = a transient stall; a flat spread = a steady limit. This is the
      //   discriminating signal, and a PROXY for drop timing (drops themselves
      //   cannot be timestamped in-script — a dropped iteration never runs code).
      stalls: stalls && stalls.values ? round(stalls.values.count, 0) : 0,
      stall_ms_threshold: STALL_MS,
      stall_concurrency_max: round(scV.max, 1),
      stall_concurrency_avg: round(scV.avg, 1),
      stall_time_buckets: stallBuckets,
    });
  }
  // Whole-run VU gauges straight from k6's built-ins. vus_max = k6's own max
  // INITIALIZED (allocated) VU count for the entire run, summed across every
  // scenario's pool; vus = max concurrently-active VUs for the entire run, but
  // sampled at only 1 Hz so it UNDERSTATES the brief stall pile-ups the per-rung
  // vus_active_max (per-iteration) catches — kept only as a coarse cross-check.
  const vusMaxG = data.metrics.vus_max;
  const vusG = data.metrics.vus;
  const vusInitGlobalMax = vusMaxG && vusMaxG.values ? round(vusMaxG.values.max, 0) : null;
  // k6 pre-initializes EVERY staggered arrival-rate scenario's preAllocatedVUs at
  // test start, so with no growth the global initialized count sits at exactly
  // (rung count x preAllocatedVUs). Anything above that baseline is real pool
  // growth (some rung's executor allocated past preAllocatedVUs mid-run).
  const initBaseline = points.length * SWEEP.preAllocatedVUs;
  const out = {
    proto: SWEEP.proto,
    points,
    // Additive, namespaced, WHOLE-RUN block (deliberately not per rung — pool
    // growth cannot be attributed to a single rung here; see the note). Lets a
    // reader see at a glance whether the VU pool grew past its baseline and how
    // much concurrency the whole ladder demanded, before drilling into per-rung
    // vus_active_* and stall_time_buckets.
    vus_diagnostics: {
      preallocated_vus: SWEEP.preAllocatedVUs,
      max_vus: SWEEP.maxVUs,
      rung_count: points.length,
      // The no-growth expectation for the global initialized count.
      vus_initialized_baseline: initBaseline,
      // The actual global initialized high-water for the whole run.
      vus_initialized_global_max: vusInitGlobalMax,
      // The bottom-line answer to "did the pool ever grow?": true iff the global
      // initialized count exceeded (rung count x preAllocatedVUs). When false, no
      // executor ever allocated beyond preAllocatedVUs, so the preAllocatedVUs <
      // maxVUs ramp the config permits NEVER fired, and equalising the two would
      // fix a mechanism that is not the cause of the drops.
      vus_pool_grew: vusInitGlobalMax === null ? null : vusInitGlobalMax > initBaseline,
      // Coarse (1 Hz) whole-run peak active VUs; per-rung vus_active_max is the
      // sensitive figure — expect this to be LOWER when stalls are brief.
      vus_concurrent_overall_max: vusG && vusG.values ? round(vusG.values.max, 0) : null,
      stall_ms_threshold: STALL_MS,
      time_buckets: TIME_BUCKETS,
      step_seconds: STEP_SECONDS,
      bucket_width_ms: round(BUCKET_WIDTH_MS, 0),
      note:
        'Item 18 open question. Per rung: is vus_active_max below preAllocatedVUs (VUs idle -> pool shortage cannot explain the drops) and do stall_time_buckets cluster (transient stall) or spread evenly (steady limit)? Whole-run: vus_pool_grew says whether the pool ever grew past rung_count x preAllocatedVUs. preAllocatedVUs/maxVUs deliberately unchanged so this run measures before any fix.',
    },
  };
  const json = JSON.stringify(out, null, 2);
  const result = {};
  result[SWEEP.resultPath] = json;
  result.stdout = `\nsweep result (${SWEEP.proto}):\n${json}\n`;
  return result;
}
