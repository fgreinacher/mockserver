// Expectation seeding + the per-request actions exercised by the scenarios.
//
// Parity with the historical Locust harness:
//   - the same 4 expectations are seeded (the request matches the LAST one, so
//     the matcher does a near-full scan — the realistic worst case)
//   - the `match` action GETs /simple (data-plane hot path)
//   - the `create` action PUTs a /simple expectation with remainingTimes:5
//     (control-plane churn, exactly as the Locust `expectation` task did)
//   - the `forward` action GETs /forward (proxy/override path)
//
// New scenarios add a large-body match and a regex-matcher workload to exercise
// the body-decode and regex paths called out in the performance plan.

import http from 'k6/http';
import { check, fail } from 'k6';
import { CONFIG, FORWARD, REGRESSION } from './config.js';

// The 4 seeded expectations — byte-for-byte the same shapes as the legacy
// expectations.json so recorded baselines remain comparable.
export const SEED_EXPECTATIONS = [
  {
    httpRequest: { path: '/not_simple' },
    httpResponse: { statusCode: 200, body: 'some not simple response' },
    times: { unlimited: true },
  },
  {
    httpRequest: { method: 'POST', path: '/simple' },
    httpResponse: { statusCode: 200, body: 'some simple POST response' },
    times: { unlimited: true },
  },
  {
    httpRequest: { path: '/forward' },
    httpOverrideForwardedRequest: {
      httpRequest: { headers: { host: ['127.0.0.1:1080'] }, path: '/simple' },
    },
    times: { unlimited: true },
  },
  {
    httpRequest: { path: '/simple' },
    httpResponse: { statusCode: 200, body: 'some simple response' },
    times: { unlimited: true },
  },
];

// A ~4 KB JSON expectation + matching request body to exercise the body-decode
// and JSON-match path (BodyDecoderEncoder / JsonStringMatcher in the plan).
const LARGE_BODY = JSON.stringify({
  items: Array.from({ length: 50 }, (_, i) => ({
    id: i,
    name: `item-${i}`,
    tags: ['alpha', 'beta', 'gamma'],
    nested: { value: i * 7, label: `label-${i}` },
  })),
});

export const LARGE_BODY_EXPECTATION = {
  httpRequest: {
    method: 'POST',
    path: '/large',
    body: { type: 'JSON', json: LARGE_BODY, matchType: 'ONLY_MATCHING_FIELDS' },
  },
  httpResponse: { statusCode: 200, body: 'matched large body' },
  times: { unlimited: true },
};

// A regex path matcher to exercise the RegexStringMatcher / timeout-executor
// path (plan A1.3).
export const REGEX_EXPECTATION = {
  httpRequest: { path: '/regex/[a-z0-9]+/resource' },
  httpResponse: { statusCode: 200, body: 'matched regex' },
  times: { unlimited: true },
};

// A Velocity response-template expectation to exercise the dynamic
// response-generation path (TemplateEngine), the heaviest always-available
// per-request CPU path that needs no external responder. VELOCITY is the
// default engine and is always on the classpath, so this runs on the stock
// image. (Object/class callbacks need a connected websocket responder / a class
// on the classpath — deferred to a v2 nullable `callback` behaviour.)
export const TEMPLATE_EXPECTATION = {
  httpRequest: { path: '/template' },
  httpResponseTemplate: {
    templateType: 'VELOCITY',
    template: '{ "statusCode": 200, "body": "path=$!request.path method=$!request.method" }',
  },
  times: { unlimited: true },
};

// Build the /forward expectation routed at the given upstream host. Uses
// httpOverrideForwardedRequest (a forward action that applies overrides then
// forwards), routing /forward -> <host>/simple. For a regression baseline the
// host is a DEDICATED upstream so the forward latency is not contaminated by the
// matching load on the instance under measurement.
export function forwardExpectation(host) {
  return {
    httpRequest: { path: '/forward' },
    httpOverrideForwardedRequest: {
      httpRequest: { headers: { host: [host] }, path: '/simple' },
    },
    times: { unlimited: true },
  };
}

function jsonParams(extraTags) {
  return {
    headers: { 'Content-Type': 'application/json', ...CONFIG.keepAliveHeaders },
    tags: extraTags || {},
  };
}

// --- lifecycle -------------------------------------------------------------

// Seed the base expectations. Called from each scenario's setup(). Fails the
// run loudly if MockServer is unreachable or rejects the expectations.
export function seedExpectations(extra = []) {
  const payload = JSON.stringify([...SEED_EXPECTATIONS, ...extra]);
  const res = http.put(`${CONFIG.controlPlane}/expectation`, payload, jsonParams());
  if (res.status !== 201 && res.status !== 200) {
    fail(`failed to seed expectations: HTTP ${res.status} ${res.body}`);
  }
  return res;
}

export function resetMockServer() {
  return http.put(`${CONFIG.controlPlane}/reset`, null, jsonParams());
}

// Seed the expectations exercised by regression.js / growth.js: the static
// /simple (mock match), /template (dynamic response), /forward (forward action
// to the upstream — or self when K6_FORWARD_SELF=true), and the large JSON body.
// The upstream MockServer must itself be seeded with a /simple response (done by
// perf-test-run.sh) for the forward behaviour to return 200.
export function seedRegression() {
  const host = FORWARD.forwardSelf ? '127.0.0.1:1080' : FORWARD.upstreamHost;
  // Self-test hook: when K6_REG_MATCH_DELAY_MS > 0, add a fixed server-side delay
  // to the /simple (match) response so a run proves the measured percentiles
  // still move with a REAL server slowdown. Off by default (delay omitted).
  const simpleResponse = { statusCode: 200, body: 'some simple response' };
  if (REGRESSION.matchDelayMs > 0) {
    simpleResponse.delay = { timeUnit: 'MILLISECONDS', value: REGRESSION.matchDelayMs };
  }
  const expectations = [
    {
      httpRequest: { path: '/simple' },
      httpResponse: simpleResponse,
      times: { unlimited: true },
    },
    TEMPLATE_EXPECTATION,
    forwardExpectation(host),
    LARGE_BODY_EXPECTATION,
  ];
  const res = http.put(`${CONFIG.controlPlane}/expectation`, JSON.stringify(expectations), jsonParams());
  if (res.status !== 201 && res.status !== 200) {
    fail(`failed to seed regression expectations: HTTP ${res.status} ${res.body}`);
  }
  return res;
}

// Seed ONLY the /forward expectation (forward.js). The forward path is the sole
// workload, so the instance under measurement has just this one expectation. The
// upstream MockServer (or self, when K6_FORWARD_SELF=true) must itself answer
// /simple with a 200 for the forward to succeed.
export function seedForward() {
  const host = FORWARD.forwardSelf ? '127.0.0.1:1080' : FORWARD.upstreamHost;
  const res = http.put(
    `${CONFIG.controlPlane}/expectation`,
    JSON.stringify([forwardExpectation(host)]),
    jsonParams(),
  );
  if (res.status !== 201 && res.status !== 200) {
    fail(`failed to seed forward expectation: HTTP ${res.status} ${res.body}`);
  }
  return res;
}

// --- actions (tagged so k6 reports per-operation) --------------------------

const SIMPLE_EXPECTATION = JSON.stringify([
  {
    httpRequest: { path: '/simple' },
    httpResponse: { statusCode: 200, body: 'some simple response' },
    times: { remainingTimes: 5 },
  },
]);

export function createSimpleExpectation() {
  const res = http.put(
    `${CONFIG.controlPlane}/expectation`,
    SIMPLE_EXPECTATION,
    jsonParams({ op: 'create', name: 'PUT /mockserver/expectation' }),
  );
  check(res, { 'create: 201': (r) => r.status === 201 });
  return res;
}

export function getSimple(extraTags) {
  const res = http.get(`${CONFIG.baseUrl}/simple`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'match', name: 'GET /simple', ...(extraTags || {}) },
  });
  check(res, { 'match: 200': (r) => r.status === 200 });
  return res;
}

export function getForward(extraTags) {
  const res = http.get(`${CONFIG.baseUrl}/forward`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'forward', name: 'GET /forward', ...(extraTags || {}) },
  });
  check(res, { 'forward: 200': (r) => r.status === 200 });
  return res;
}

export function postLargeBody(extraTags) {
  const res = http.post(`${CONFIG.baseUrl}/large`, LARGE_BODY, jsonParams({ op: 'large', name: 'POST /large', ...(extraTags || {}) }));
  check(res, { 'large: 200': (r) => r.status === 200 });
  return res;
}

export function getRegex() {
  const res = http.get(`${CONFIG.baseUrl}/regex/abc123/resource`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'regex', name: 'GET /regex/:id/resource' },
  });
  check(res, { 'regex: 200': (r) => r.status === 200 });
  return res;
}

export function getTemplated(extraTags) {
  const res = http.get(`${CONFIG.baseUrl}/template`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'template', name: 'GET /template', ...(extraTags || {}) },
  });
  check(res, { 'template: 200': (r) => r.status === 200 });
  return res;
}
