// Steady-state latency probe — performance-programme.md §1 (the unattributed tail).
//
// ONE constant-arrival-rate scenario at a SINGLE rate (K6_STEADY_RATE), no ladder,
// no per-rung ramp. A warm-up prefix (K6_STEADY_WARMUP) is EXCLUDED from the
// reported statistics by tagging every request phase:warmup|measure and reading
// only the phase:measure submetrics — so the rung-onset transient the sweep sees in
// its first wall-time bucket cannot land in the tail. Reports p50/p95/p99(.9), the
// per-phase breakdown (waiting/sending/blocked…) and the stall time-buckets over the
// MEASURED window, so the client tail can be set against the server histogram
// perf-test-run.sh scrapes for the same window.
import { Trend, Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { CONFIG } from './lib/config.js';
import { seedExpectations, resetMockServer, getSimple } from './lib/expectations.js';

function toSeconds(d) {
  const m = /^(\d+)(ms|s|m|h)?$/.exec(String(d).trim());
  if (!m) {
    throw new Error(`steady.js: cannot parse duration "${d}"`);
  }
  return Number(m[1]) * { ms: 0.001, s: 1, m: 60, h: 3600 }[m[2] || 's'];
}

function round(v, dp = 3) {
  if (v === undefined || v === null || Number.isNaN(v)) {
    return null;
  }
  const f = 10 ** dp;
  return Math.round(v * f) / f;
}

const RATE = Number(__ENV.K6_STEADY_RATE);
if (!Number.isFinite(RATE) || RATE <= 0) {
  throw new Error(`steady.js: K6_STEADY_RATE must be a positive number, got "${__ENV.K6_STEADY_RATE}"`);
}
const WARMUP_S = toSeconds(__ENV.K6_STEADY_WARMUP || '30s');
const MEASURE_S = toSeconds(__ENV.K6_STEADY_DURATION || '5m');
const WARMUP_MS = WARMUP_S * 1000;
// VU pool sized off the offered rate, same clamp as sweep.js poolForRate; fixed
// (preAllocatedVUs == maxVUs) so no connection ramp can add its own onset transient.
const POOL = Math.min(
  Number(__ENV.K6_STEADY_VU_CEILING || 4096),
  Math.max(Number(__ENV.K6_STEADY_VU_FLOOR || 96), Math.ceil(RATE * (Number(__ENV.K6_STEADY_VUS_PER_KRPS || 80) / 1000))),
);
const STALL_MS = Number(__ENV.K6_STEADY_STALL_MS || 5);
const TIME_BUCKETS = Math.max(1, Math.trunc(Number(__ENV.K6_STEADY_TIME_BUCKETS || 6)) || 6);
const BUCKET_WIDTH_MS = (MEASURE_S * 1000) / TIME_BUCKETS;

const PHASES = [
  ['http_req_blocked', 'blocked'],
  ['http_req_connecting', 'connecting'],
  ['http_req_tls_handshaking', 'tls'],
  ['http_req_sending', 'sending'],
  ['http_req_waiting', 'waiting'],
  ['http_req_receiving', 'receiving'],
];

const vusActiveTrend = new Trend('steady_vus_active');
const stallCounter = new Counter('steady_stalls');
const stallBucketCounter = new Counter('steady_stalls_bucketed');

function steadyThresholds() {
  const t = {};
  // Only the MEASURED window carries statistics (warm-up is materialised but never read).
  t['http_req_duration{phase:measure}'] = ['p(50)>=0', 'p(90)>=0', 'p(95)>=0', 'p(99)>=0', 'p(99.9)>=0'];
  for (const [metric] of PHASES) {
    t[`${metric}{phase:measure}`] = ['p(50)>=0', 'p(95)>=0', 'p(99)>=0'];
  }
  t['http_req_failed{phase:measure}'] = ['rate>=0'];
  t['http_reqs{phase:measure}'] = ['count>=0'];
  t['steady_vus_active{phase:measure}'] = ['max>=0', 'p(95)>=0'];
  t['steady_stalls{phase:measure}'] = ['count>=0'];
  for (let b = 0; b < TIME_BUCKETS; b += 1) {
    t[`steady_stalls_bucketed{slot:${b}}`] = ['count>=0'];
  }
  return t;
}

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)', 'max'],
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      exec: 'probe',
      rate: RATE,
      timeUnit: '1s',
      duration: `${Math.round(WARMUP_S + MEASURE_S)}s`,
      preAllocatedVUs: POOL,
      maxVUs: POOL,
    },
  },
  thresholds: steadyThresholds(),
};

export function setup() {
  seedExpectations();
}

export function probe() {
  const elapsedMs = exec.instance.currentTestRunDuration;
  const measuring = elapsedMs >= WARMUP_MS;
  const phaseTag = { phase: measuring ? 'measure' : 'warmup' };
  vusActiveTrend.add(exec.instance.vusActive, phaseTag);
  const res = getSimple(phaseTag);
  if (!measuring) {
    return;
  }
  const duration = res && res.timings ? res.timings.duration : 0;
  if (duration > STALL_MS) {
    stallCounter.add(1, phaseTag);
    let bucket = Math.floor((elapsedMs - WARMUP_MS) / BUCKET_WIDTH_MS);
    if (bucket < 0) {
      bucket = 0;
    } else if (bucket >= TIME_BUCKETS) {
      bucket = TIME_BUCKETS - 1;
    }
    stallBucketCounter.add(1, { slot: String(bucket) });
  }
}

export function teardown() {
  resetMockServer();
}

export function handleSummary(data) {
  const dur = data.metrics['http_req_duration{phase:measure}'];
  const failed = data.metrics['http_req_failed{phase:measure}'];
  const reqs = data.metrics['http_reqs{phase:measure}'];
  const dropped = data.metrics['dropped_iterations{scenario:steady}'] || data.metrics.dropped_iterations;
  const v = dur && dur.values ? dur.values : {};
  const count = reqs && reqs.values ? reqs.values.count : 0;

  const phaseMs = {};
  for (const [metric, key] of PHASES) {
    const pm = data.metrics[`${metric}{phase:measure}`];
    const pv = pm && pm.values ? pm.values : {};
    phaseMs[key] = { p50_ms: round(pv['p(50)'] !== undefined ? pv['p(50)'] : pv.med), p95_ms: round(pv['p(95)']), p99_ms: round(pv['p(99)']) };
  }

  const va = data.metrics['steady_vus_active{phase:measure}'];
  const vaV = va && va.values ? va.values : {};
  const stalls = data.metrics['steady_stalls{phase:measure}'];
  const stallBuckets = [];
  for (let b = 0; b < TIME_BUCKETS; b += 1) {
    const bm = data.metrics[`steady_stalls_bucketed{slot:${b}}`];
    stallBuckets.push(bm && bm.values ? round(bm.values.count, 0) : 0);
  }

  const out = {
    offered_rps: RATE,
    warmup_s: WARMUP_S,
    measure_s: MEASURE_S,
    pool: POOL,
    achieved_rps: round(MEASURE_S > 0 ? count / MEASURE_S : 0, 1),
    sample_count: round(count, 0),
    p50_ms: round(v['p(50)'] !== undefined ? v['p(50)'] : v.med),
    p90_ms: round(v['p(90)']),
    p95_ms: round(v['p(95)']),
    p99_ms: round(v['p(99)']),
    p999_ms: round(v['p(99.9)']),
    // The tail owner the experiment is about: waiting is time-to-first-byte (server).
    waiting_p99_ms: phaseMs.waiting ? phaseMs.waiting.p99_ms : null,
    phase_ms: phaseMs,
    error_rate: failed && failed.values ? round(failed.values.rate, 5) : 0,
    dropped_iterations: dropped && dropped.values ? round(dropped.values.count, 0) : 0,
    vus_active_max: round(vaV.max, 1),
    vus_active_p95: round(vaV['p(95)'], 1),
    stalls: stalls && stalls.values ? round(stalls.values.count, 0) : 0,
    stall_ms_threshold: STALL_MS,
    time_buckets: TIME_BUCKETS,
    // Stalls per equal wall-time slice of the MEASURED window (earliest first): a
    // burst in bucket 0 with a warm-up excluded means the onset transient survived
    // the warm-up; a flat spread means a steady limit.
    stall_time_buckets: stallBuckets,
  };
  const json = JSON.stringify(out, null, 2);
  const result = {};
  result[__ENV.K6_STEADY_RESULT_PATH || 'steady-result.json'] = json;
  result.stdout = `\nsteady result (rate=${RATE}, warmup=${WARMUP_S}s, measure=${MEASURE_S}s):\n${json}\n`;
  return result;
}
