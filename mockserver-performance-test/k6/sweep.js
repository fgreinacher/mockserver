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
  getSimple({ rate: __ENV.SWEEP_RATE });
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
    });
  }
  const out = { proto: SWEEP.proto, points };
  const json = JSON.stringify(out, null, 2);
  const result = {};
  result[SWEEP.resultPath] = json;
  result.stdout = `\nsweep result (${SWEEP.proto}):\n${json}\n`;
  return result;
}
