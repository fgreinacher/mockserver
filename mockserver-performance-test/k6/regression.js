// Regression scenario — the periodic-pipeline workhorse. Measures response
// latency across the four core behaviours under a FIXED offered rate
// (constant-arrival-rate), so the recorded numbers are comparable across daily
// runs and feed the stored-history baseline comparison (perf-test-compare.sh).
//
// Behaviours (each its own scenario, tagged op:<name>):
//   match    — static mock match + response (data-plane hot path)
//   forward  — forward action to a DEDICATED upstream MockServer
//   template — Velocity response template (dynamic response generation)
//   large    — ~4 KB JSON body decode + match
//
// A warmup scenario (op:warmup) runs first so JIT/GC reach steady state; the
// measured scenarios start at K6_REG_WARMUP and only their op submetrics feed
// the result JSON. Run once over HTTP and once over HTTPS+H2 (BASE_URL scheme +
// PROTO label); perf-test-run.sh merges the two result files.
//
//   k6 run mockserver-performance-test/k6/regression.js
//   k6 run -e BASE_URL=https://localhost:1080 -e PROTO=https_h2 .../regression.js
//
import http from 'k6/http';
import { CONFIG, REGRESSION } from './lib/config.js';
import { seedRegression, resetMockServer, getSimple, getForward, getTemplated, postLargeBody } from './lib/expectations.js';

const OPS = ['match', 'forward', 'template', 'large'];

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

// Materialise per-op submetrics in the summary by declaring (always-passing)
// thresholds. The >=0 expressions never fail (notify-only) but force k6 to
// compute and expose p(50)/p(95)/p(99), the failed-rate, and the request count
// per op so handleSummary can read them.
function regressionThresholds(ops) {
  const t = {};
  for (const op of ops) {
    t[`http_req_duration{op:${op}}`] = ['p(50)>=0', 'p(95)>=0', 'p(99)>=0'];
    t[`http_req_failed{op:${op}}`] = ['rate>=0'];
    t[`http_reqs{op:${op}}`] = ['count>=0'];
    // throughput_rps = completed/duration is NOT pinned to the offered rate: k6
    // drops iterations when its VU pool cannot launch them on time, so a low
    // number is ambiguous — the server got slower OR the client ran out of VUs.
    // Materialise the per-scenario dropped_iterations counter (scenario name ==
    // the op) so handleSummary can report it alongside offered_rps and make that
    // ambiguity legible instead of hidden inside throughput_rps.
    t[`dropped_iterations{scenario:${op}}`] = ['count>=0'];
  }
  return t;
}

function measuredScenario(exec) {
  return {
    executor: 'constant-arrival-rate',
    exec,
    rate: REGRESSION.rate,
    timeUnit: '1s',
    duration: REGRESSION.duration,
    startTime: REGRESSION.warmup,
    preAllocatedVUs: REGRESSION.preAllocatedVUs,
    maxVUs: REGRESSION.maxVUs,
  };
}

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  // handleSummary reads these stats off each submetric's `values`; p(50)/p(99)
  // are NOT in k6's default set, so declare them or they come back null.
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    // Low-rate warmup across all paths; op:warmup keeps it out of the measured
    // submetrics. Runs during [0, K6_REG_WARMUP].
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'warmupOp',
      rate: Math.max(10, Math.round(REGRESSION.rate / 4)),
      timeUnit: '1s',
      duration: REGRESSION.warmup,
      preAllocatedVUs: 10,
      maxVUs: 100,
    },
    match: measuredScenario('matchOp'),
    forward: measuredScenario('forwardOp'),
    template: measuredScenario('templateOp'),
    large: measuredScenario('largeOp'),
  },
  thresholds: regressionThresholds(OPS),
};

export function setup() {
  seedRegression();
}

export function warmupOp() {
  // Touch every measured path so each is JIT-warmed before measurement.
  http.get(`${CONFIG.baseUrl}/simple`, { headers: CONFIG.keepAliveHeaders, tags: { op: 'warmup' } });
  http.get(`${CONFIG.baseUrl}/template`, { headers: CONFIG.keepAliveHeaders, tags: { op: 'warmup' } });
  http.get(`${CONFIG.baseUrl}/forward`, { headers: CONFIG.keepAliveHeaders, tags: { op: 'warmup' } });
}

export function matchOp() {
  getSimple();
}

export function forwardOp() {
  getForward();
}

export function templateOp() {
  getTemplated();
}

export function largeOp() {
  postLargeBody();
}

export function teardown() {
  resetMockServer();
}

// Emit the machine-readable result consumed by perf-test-compare.sh. Throughput
// is computed from the request count over the KNOWN measured window (not k6's
// whole-test rate, which would include the warmup window).
export function handleSummary(data) {
  const proto = REGRESSION.proto;
  const durationSec = toSeconds(REGRESSION.duration);
  const behaviours = {};
  for (const op of OPS) {
    const dur = data.metrics[`http_req_duration{op:${op}}`];
    const failed = data.metrics[`http_req_failed{op:${op}}`];
    const reqs = data.metrics[`http_reqs{op:${op}}`];
    if (!dur || !dur.values) {
      continue;
    }
    const count = reqs && reqs.values ? reqs.values.count : 0;
    const dropped = data.metrics[`dropped_iterations{scenario:${op}}`];
    const droppedCount = dropped && dropped.values ? dropped.values.count : 0;
    const throughput = round(durationSec > 0 ? count / durationSec : 0);
    behaviours[`${op}_${proto}`] = {
      p50_ms: round(dur.values['p(50)'] !== undefined ? dur.values['p(50)'] : dur.values.med),
      p95_ms: round(dur.values['p(95)']),
      p99_ms: round(dur.values['p(99)']),
      throughput_rps: throughput,
      // offered_rps + dropped_iterations make throughput_rps legible: a shortfall
      // of throughput below offered with dropped_iterations > 0 is a CLIENT (VU)
      // limit, not a server regression. delivery_ratio = achieved/offered is the
      // at-a-glance figure surfaced in the annotation. throughput_rps is recorded
      // but NOT budgeted (perf-test-compare.sh) until the shortfall is understood.
      offered_rps: REGRESSION.rate,
      dropped_iterations: round(droppedCount, 0),
      delivery_ratio: round(REGRESSION.rate > 0 ? throughput / REGRESSION.rate : null, 4),
      error_rate: failed && failed.values ? round(failed.values.rate, 5) : 0,
    };
  }
  const out = { proto, behaviours };
  const json = JSON.stringify(out, null, 2);
  const result = {};
  result[REGRESSION.resultPath] = json;
  result.stdout = `\nregression result (${proto}):\n${json}\n`;
  return result;
}
