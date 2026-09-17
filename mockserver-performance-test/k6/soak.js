// Soak test — sustained moderate load over a long duration (default 2h) to
// surface slow degradation: memory growth from the event log / expectation
// churn, GC pressure, file-descriptor or connection leaks, and — the reason a
// soak matters more than a short regression run — whether the DATA-PLANE p99
// DRIFTS upward as the in-memory event log fills and stays full. Pair with the
// docker-compose stack (Part D) so Grafana shows JVM heap/GC trends across the
// soak.
//
//   k6 run mockserver-performance-test/k6/soak.js
//   k6 run -e K6_SOAK_DURATION=2h -e K6_SOAK_RATE=200 .../soak.js
//
// TWO failure signals (thresholds, i.e. the pass/fail gate):
//   1. http_req_duration{op:match} p99 DRIFT — the data-plane hot path must stay
//      under LIMITS.p99 for the WHOLE soak, not just cold. If an O(n) event-log
//      eviction (issue #2329 class) or a slow leak degrades matching as the ring
//      fills, this trips.
//   2. http_req_failed{op:match} error rate — connection/fd leaks surface as
//      climbing data-plane errors over hours.
//
// Item 10b — EVENT-LOG VERIFICATION COST AS THE LOG FILLS. `verify` and
// `retrieveRecordedRequests` are issued at a LOW fixed rate throughout. Both
// scan the event log, so a query against a FULL 100k ring is where an O(n)
// regression bites hardest — and this is the central-deployment pattern
// (pipelines assert / retrieve recorded traffic). Their latency is MEASURED, not
// gated (notify-only until ~8 weekly runs of variance exist), and the soak step
// samples requests_received_count / heap alongside so the recorded verify /
// retrieve latency can be read AGAINST log occupancy. This is what finally
// DEMONSTRATES the ring-buffer bound under load rather than asserting it: the
// count-bounded ring stays pinned at maxLogEntries while requests_received climbs
// unboundedly, so query latency and live-set stay flat.
//
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { CONFIG, LOAD, LIMITS, num, env } from './lib/config.js';
import {
  seedExpectations,
  resetMockServer,
  getSimple,
  createSimpleExpectation,
} from './lib/expectations.js';

// --- item 10 / 10b tunables (local to soak.js — lib/config.js is owned by a
// concurrent unit and not edited here; all knobs are __ENV-driven via the shared
// num()/env() helpers so the same script runs locally, in compose, and in CI). --
const SOAK = {
  // Low fixed rates for the two event-log queries. Deliberately ~1 rps: this is a
  // COST probe against a filling log, not a load arm — it must not perturb the
  // data-plane p99 it runs beside. Over a 2h soak, 1 rps is ~7200 samples per
  // query type, ample for a p95/p99 against a long-full ring.
  verifyRate: num('K6_SOAK_VERIFY_RATE', 1),
  retrieveRate: num('K6_SOAK_RETRIEVE_RATE', 1),
  // Early-vs-late drift windows for the match hot path. The p99 of the EARLY
  // window vs the LATE window is the drift ratio reported in the result — the
  // genuine "does latency grow with occupancy" measurement, distinct from the
  // aggregate p99 gate. Default 5m; override to a few seconds for a short local
  // proof run.
  window: env('K6_SOAK_WINDOW', '5m'),
  // Lead-in BEFORE the early window so the JVM/JIT cold-start cohort is excluded
  // from the early baseline. Including warmup in "early" would inflate early p99
  // and bias drift_ratio (late/early) DOWNWARD — tending to HIDE the occupancy
  // drift the signal exists to expose. Default 2m (a 2h soak reaches steady state
  // well within it); override LOW for a short local run.
  lead: env('K6_SOAK_LEAD', '2m'),
  proto: env('PROTO', CONFIG.baseUrl.startsWith('https') ? 'https_h2' : 'http'),
  resultPath: env('K6_SOAK_RESULT_PATH', 'soak-result.json'),
};

// k6 duration string ("5m","90s","2h") -> seconds. Mirrors the config helpers'
// intent; kept local so soak.js needs no new export from the owned config.js.
function toSeconds(spec) {
  const m = String(spec).match(/^(\d+(?:\.\d+)?)(ms|s|m|h|d)?$/);
  if (!m) return 0;
  const v = Number(m[1]);
  const unit = m[2] || 's';
  return { ms: v / 1000, s: v, m: v * 60, h: v * 3600, d: v * 86400 }[unit];
}

const DURATION_SEC = toSeconds(LOAD.soakDuration);
const WINDOW_SEC = toSeconds(SOAK.window);
const LEAD_SEC = toSeconds(SOAK.lead);
// The EARLY window is [LEAD, LEAD+WINDOW] — after the warmup lead-in, so the cold
// JVM/JIT cohort is excluded from it. The LATE window is the last WINDOW_SEC of
// the soak. Clamped to the run duration so a short run degrades sensibly (if the
// lead-in exceeds the duration the early window is empty and drift_ratio is null,
// rather than silently baselining warmup — override K6_SOAK_LEAD for short runs).
const EARLY_START_SEC = Math.min(LEAD_SEC, DURATION_SEC);
const EARLY_END_SEC = Math.min(LEAD_SEC + WINDOW_SEC, DURATION_SEC);
const LATE_START_SEC = Math.max(EARLY_END_SEC, DURATION_SEC - WINDOW_SEC);

// Tag a match request with its drift window from the wall-clock elapsed time
// (robust regardless of which VU runs the iteration — same approach as
// regression.js phaseTag). win:early|late materialise sub-percentiles; the
// warmup lead-in [0,LEAD) and the gap between the windows carry win:mid and are
// counted ONLY in the aggregate gate, never in the drift sub-percentiles.
function matchWindow() {
  const elapsedSec = exec.instance.currentTestRunDuration / 1000;
  if (elapsedSec >= LATE_START_SEC) return 'late';
  if (elapsedSec >= EARLY_START_SEC && elapsedSec <= EARLY_END_SEC) return 'early';
  return 'mid';
}

// A verification body that ALWAYS matches (the /simple path is hit continuously
// by the match scenario, so it is always in the log) — verify therefore returns
// 202, never 406. atLeast:1 keeps the check cheap and deterministic.
const VERIFY_BODY = JSON.stringify({
  httpRequest: { path: '/simple' },
  times: { atLeast: 1 },
});

function verifyLog() {
  const res = http.put(`${CONFIG.controlPlane}/verify`, VERIFY_BODY, {
    headers: { 'Content-Type': 'application/json', ...CONFIG.keepAliveHeaders },
    tags: { op: 'verify', name: 'PUT /mockserver/verify' },
  });
  // 202 = verification satisfied. Anything else (406 not-matched, 400, 5xx) is a
  // real fault and must drag the checks rate below the gate.
  check(res, { 'verify: 202': (r) => r.status === 202 });
  return res;
}

function retrieveLog() {
  // type=REQUESTS is the full recorded-request scan over the event log — the O(n)
  // read the central-deployment pattern performs and the one a ring regression
  // punishes as occupancy grows.
  const res = http.put(`${CONFIG.controlPlane}/retrieve?type=REQUESTS&format=JSON`, null, {
    headers: { 'Content-Type': 'application/json', ...CONFIG.keepAliveHeaders },
    tags: { op: 'retrieve', name: 'PUT /mockserver/retrieve' },
  });
  check(res, { 'retrieve: 200': (r) => r.status === 200 });
  return res;
}

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  // p(50)/p(99) are not in k6's default summary set — declare them so
  // handleSummary can read real quantiles off the sub-metrics.
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    // Steady data-plane matching for the whole soak (the p99-drift subject).
    match: {
      executor: 'constant-arrival-rate',
      exec: 'match',
      rate: LOAD.soakRate,
      timeUnit: '1s',
      duration: LOAD.soakDuration,
      preAllocatedVUs: LOAD.preAllocatedVUs,
      maxVUs: LOAD.maxVUs,
    },
    // Continuous control-plane churn — this is what grows the event log over
    // time, so it is the interesting signal for a memory soak.
    create: {
      executor: 'constant-arrival-rate',
      exec: 'create',
      rate: LOAD.createRate,
      timeUnit: '1s',
      duration: LOAD.soakDuration,
      preAllocatedVUs: 5,
      maxVUs: 50,
    },
    // Item 10b — low-rate event-log queries throughout the soak.
    verify: {
      executor: 'constant-arrival-rate',
      exec: 'verify',
      rate: SOAK.verifyRate,
      timeUnit: '1s',
      duration: LOAD.soakDuration,
      preAllocatedVUs: 2,
      maxVUs: 20,
    },
    retrieve: {
      executor: 'constant-arrival-rate',
      exec: 'retrieve',
      rate: SOAK.retrieveRate,
      timeUnit: '1s',
      duration: LOAD.soakDuration,
      preAllocatedVUs: 2,
      maxVUs: 20,
    },
  },
  thresholds: {
    // --- THE soak gate: data-plane drift + errors -------------------------------
    // p99 of the match hot path over the WHOLE soak must stay under the bound (a
    // drift upward as the ring fills trips this), and match errors must stay low
    // (a connection/fd leak over hours trips this).
    'http_req_duration{op:match}': [`p(99)<${LIMITS.p99}`, `p(95)<${LIMITS.p95}`],
    'http_req_failed{op:match}': [`rate<${LIMITS.errorRate}`],
    // Control-plane churn must not start erroring either.
    'http_req_failed{op:create}': [`rate<${LIMITS.errorRate}`],
    // Every scenario's checks (match 200, create 201, verify 202, retrieve 200)
    // must pass — a systemic control-plane failure (e.g. verify starts 406-ing)
    // drops this below the gate and fails the soak loudly.
    'checks': [`rate>${LIMITS.checkRate}`],
    // --- item 10b: MEASURE, do NOT gate -----------------------------------------
    // verify / retrieve latency against a full log is EXPECTED to be higher than a
    // sub-ms match and is NOTIFY-ONLY (no history yet). Declare always-pass
    // thresholds purely to MATERIALISE the p50/p95/p99 + counts for handleSummary.
    'http_req_duration{op:verify}': ['p(50)>=0', 'p(95)>=0', 'p(99)>=0'],
    'http_reqs{op:verify}': ['count>=0'],
    'http_req_duration{op:retrieve}': ['p(50)>=0', 'p(95)>=0', 'p(99)>=0'],
    'http_reqs{op:retrieve}': ['count>=0'],
    // Early-vs-late drift sub-percentiles for the match path (notify-only).
    'http_req_duration{op:match,win:early}': ['p(99)>=0'],
    'http_req_duration{op:match,win:late}': ['p(99)>=0'],
    'http_reqs{op:match}': ['count>=0'],
    'http_req_failed{op:verify}': ['rate>=0'],
    'http_req_failed{op:retrieve}': ['rate>=0'],
  },
};

export function setup() {
  seedExpectations();
}

export function match() {
  getSimple({ win: matchWindow() });
}

export function create() {
  createSimpleExpectation();
}

export function verify() {
  verifyLog();
}

export function retrieve() {
  retrieveLog();
}

export function teardown() {
  resetMockServer();
}

// Round to `d` decimals; null-safe (a missing metric stays null so the consumer
// can tell "not measured" from "measured zero").
function round(v, d = 3) {
  if (v === undefined || v === null || Number.isNaN(v)) return null;
  const f = Math.pow(10, d);
  return Math.round(v * f) / f;
}

// Emit the machine-readable soak result. This is uploaded as its OWN artifact
// (perf-soak.json) and is DELIBERATELY not shaped like the daily regression
// result: the daily perf-test-compare.sh must never ingest it, because no soak
// budget keys exist yet (soak metrics are notify-only until ~8 weekly runs of
// variance let a budget be derived — roughly two months). The soak STEP posts a
// human annotation from this and the perf agent's occupancy samples. The shape is
// chosen so a future `.soak` enumeration in compare.sh maps cleanly to
// `soak.<arm>.<metric>` budget keys (reported by this unit for later sequencing).
export function handleSummary(data) {
  const stat = (key, s) => {
    const m = data.metrics[key];
    return m && m.values ? round(m.values[s]) : null;
  };
  const count = (key) => {
    const m = data.metrics[key];
    return m && m.values ? round(m.values.count, 0) : 0;
  };
  const rate = (key) => {
    const m = data.metrics[key];
    return m && m.values ? round(m.values.rate, 5) : null;
  };

  const matchP99Early = stat('http_req_duration{op:match,win:early}', 'p(99)');
  const matchP99Late = stat('http_req_duration{op:match,win:late}', 'p(99)');
  const driftRatio =
    matchP99Early && matchP99Early > 0 && matchP99Late !== null
      ? round(matchP99Late / matchP99Early, 4)
      : null;

  const soak = {
    proto: SOAK.proto,
    duration_s: round(DURATION_SEC, 0),
    lead_s: round(LEAD_SEC, 0),
    early_window_start_s: round(EARLY_START_SEC, 0),
    early_window_end_s: round(EARLY_END_SEC, 0),
    late_window_start_s: round(LATE_START_SEC, 0),
    match: {
      samples: count('http_reqs{op:match}'),
      p50_ms: stat('http_req_duration{op:match}', 'p(50)'),
      p95_ms: stat('http_req_duration{op:match}', 'p(95)'),
      p99_ms: stat('http_req_duration{op:match}', 'p(99)'),
      p99_early_ms: matchP99Early,
      p99_late_ms: matchP99Late,
      // The DRIFT signal: late/early p99. ~1.0 means no occupancy-driven drift
      // (the ring bound holds); a growing ratio is the O(n)-eviction fingerprint.
      drift_ratio: driftRatio,
      error_rate: rate('http_req_failed{op:match}'),
    },
    // Item 10b — event-log query cost. Latency here is read AGAINST occupancy by
    // pairing with the step's requests_received_count / heap samples.
    verify: {
      samples: count('http_reqs{op:verify}'),
      p50_ms: stat('http_req_duration{op:verify}', 'p(50)'),
      p95_ms: stat('http_req_duration{op:verify}', 'p(95)'),
      p99_ms: stat('http_req_duration{op:verify}', 'p(99)'),
      error_rate: rate('http_req_failed{op:verify}'),
    },
    retrieve: {
      samples: count('http_reqs{op:retrieve}'),
      p50_ms: stat('http_req_duration{op:retrieve}', 'p(50)'),
      p95_ms: stat('http_req_duration{op:retrieve}', 'p(95)'),
      p99_ms: stat('http_req_duration{op:retrieve}', 'p(99)'),
      error_rate: rate('http_req_failed{op:retrieve}'),
    },
  };

  const out = { soak };
  const json = JSON.stringify(out, null, 2);
  const result = {};
  result[SOAK.resultPath] = json;
  result.stdout = `\nsoak result (${SOAK.proto}):\n${json}\n`;
  return result;
}
