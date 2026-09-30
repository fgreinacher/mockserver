// Resource-growth scenario — surfaces "X increases over time" regressions that a
// fixed-window latency run catches only by luck. Validated against issue #2329
// (CircularConcurrentLinkedDeque O(n) eviction once the request log fills to
// maxLogEntries — CPU/latency climb as the log fills, never recovering).
//
// Shape: a sustained `load` scenario on the match hot path runs for the whole
// duration at a rate high enough to fill the DEFAULT ~115.5k log early; two
// low-rate latency probes (window:first at the start, window:last at the end)
// measure the latency slope. perf-test-run.sh samples the CPU/heap trajectory in
// parallel; perf-test-compare.sh pairs them and flags a slope (end/start ratio)
// above the baseline or an absolute floor.
//
//   k6 run mockserver-performance-test/k6/growth.js
//   k6 run -e K6_GROWTH_DURATION=2m -e K6_GROWTH_RATE=800 .../growth.js   # quick local
//
import { CONFIG, GROWTH } from './lib/config.js';
import { seedRegression, resetMockServer, getSimple } from './lib/expectations.js';

function toSeconds(d) {
  const str = String(d).trim();
  const tokenRe = /(\d+)(ms|s|m|h)/g;
  let total = 0;
  let matched = false;
  let token;
  while ((token = tokenRe.exec(str)) !== null) {
    matched = true;
    total += Number(token[1]) * { ms: 0.001, s: 1, m: 60, h: 3600 }[token[2]];
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

const DURATION_S = toSeconds(GROWTH.duration);
const PROBE_S = toSeconds(GROWTH.probeWindow);
const LAST_PROBE_START = Math.max(PROBE_S, DURATION_S - PROBE_S);

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  scenarios: {
    // Sustained fill load for the whole run — this is what fills the log.
    load: {
      executor: 'constant-arrival-rate',
      exec: 'fill',
      rate: GROWTH.rate,
      timeUnit: '1s',
      duration: `${DURATION_S}s`,
      preAllocatedVUs: GROWTH.preAllocatedVUs,
      maxVUs: GROWTH.maxVUs,
    },
    // Latency probe over the first window (log not yet full → fast baseline).
    probeFirst: {
      executor: 'constant-arrival-rate',
      exec: 'probeFirst',
      rate: GROWTH.probeRate,
      timeUnit: '1s',
      duration: `${PROBE_S}s`,
      startTime: '0s',
      preAllocatedVUs: 10,
      maxVUs: 50,
    },
    // Latency probe over the last window (log full → degraded if the bug exists).
    probeLast: {
      executor: 'constant-arrival-rate',
      exec: 'probeLast',
      rate: GROWTH.probeRate,
      timeUnit: '1s',
      duration: `${PROBE_S}s`,
      startTime: `${LAST_PROBE_START}s`,
      preAllocatedVUs: 10,
      maxVUs: 50,
    },
  },
  // Materialise the per-window submetrics (always-passing; notify-only).
  thresholds: {
    'http_req_duration{window:first}': ['p(95)>=0'],
    'http_req_duration{window:last}': ['p(95)>=0'],
  },
};

export function setup() {
  seedRegression();
}

export function fill() {
  getSimple();
}

export function probeFirst() {
  getSimple({ window: 'first' });
}

export function probeLast() {
  getSimple({ window: 'last' });
}

export function teardown() {
  resetMockServer();
}

export function handleSummary(data) {
  const first = data.metrics['http_req_duration{window:first}'];
  const last = data.metrics['http_req_duration{window:last}'];
  const firstP95 = first && first.values ? first.values['p(95)'] : null;
  const lastP95 = last && last.values ? last.values['p(95)'] : null;
  const ratio = firstP95 && lastP95 && firstP95 > 0 ? lastP95 / firstP95 : null;
  const out = {
    duration_s: DURATION_S,
    p95_ms: {
      first_window: round(firstP95),
      last_window: round(lastP95),
      ratio: round(ratio),
    },
  };
  const json = JSON.stringify(out, null, 2);
  const result = {};
  result[GROWTH.resultPath] = json;
  result.stdout = `\ngrowth result (latency slope):\n${json}\n`;
  return result;
}
