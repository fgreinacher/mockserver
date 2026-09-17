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
import { SharedArray } from 'k6/data';
import { CONFIG, FORWARD, REGRESSION, PROXY, STREAMING } from './config.js';

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

// Item 15a — Mustache response-template arm. jmustache is a NON-optional core
// dependency, so this renders on the stock image alongside Velocity. Mustache
// reads request fields via {{request.path}} / {{request.method}}.
export const MUSTACHE_TEMPLATE_EXPECTATION = {
  httpRequest: { path: '/template_mustache' },
  httpResponseTemplate: {
    templateType: 'MUSTACHE',
    template: '{ "statusCode": 200, "body": "path={{request.path}} method={{request.method}}" }',
  },
  times: { unlimited: true },
};

// Item 15a — JavaScript response-template arm. The GraalJS engine is an OPTIONAL
// dependency present only in the -graaljs image variant; a JavaScript template on
// the stock image throws (fail-loud) and the request 500s. So this arm is seeded
// ONLY when REGRESSION.jsTemplate is on (perf-test-run.sh, running the -graaljs
// image), and regression.js probes it in setup() so an absent engine fails the
// run loudly rather than recording a silent 100%-error arm. A JavaScript template
// is the BARE BODY of the render function (the engine wraps it and supplies
// `request`); it returns the response object — do NOT wrap it in your own
// `function handle(){}`, which would double-wrap and yield a null response body.
export const JS_TEMPLATE_EXPECTATION = {
  httpRequest: { path: '/template_javascript' },
  httpResponseTemplate: {
    templateType: 'JAVASCRIPT',
    template: "return { 'statusCode': 200, 'body': 'path=' + request.path + ' method=' + request.method };",
  },
  times: { unlimited: true },
};

// Item 15d — build a JSON body of approximately `targetBytes` carrying a fixed
// top-level `marker` field. The matching expectation matches ONLY the marker
// (ONLY_MATCHING_FIELDS), so the server must DECODE the whole posted body (the
// size axis) while the match traversal stays constant — the variable under test
// is body size, not match complexity.
function buildLargeJson(targetBytes, marker) {
  const head = `{"marker":"${marker}","filler":[`;
  const tail = ']}';
  // Each element is a fixed-width quoted token plus a comma; size to target.
  const token = (i) => `"item-${String(i).padStart(9, '0')}"`;
  const parts = [];
  let size = head.length + tail.length;
  let i = 0;
  while (size < targetBytes) {
    const t = token(i);
    parts.push(t);
    size += t.length + 1; // + comma
    i += 1;
  }
  return `${head}${parts.join(',')}${tail}`;
}

// The large bodies are held in a SharedArray so they are built ONCE per k6
// process and shared across all VUs, rather than re-materialised in every VU's
// init context (which at 10 MB across the whole VU pool would exhaust the client).
export const LARGE_BODIES = new SharedArray('regression-large-bodies', () => [
  { marker: 'large-1mb', body: buildLargeJson(REGRESSION.large1mbBytes, 'large-1mb') },
  { marker: 'large-10mb', body: buildLargeJson(REGRESSION.large10mbBytes, 'large-10mb') },
]);

// A large-body match expectation keyed on the small fixed marker only.
function largeBodyExpectation(path, marker) {
  return {
    httpRequest: {
      method: 'POST',
      path,
      body: { type: 'JSON', json: JSON.stringify({ marker }), matchType: 'ONLY_MATCHING_FIELDS' },
    },
    httpResponse: { statusCode: 200, body: `matched ${marker}` },
    times: { unlimited: true },
  };
}

export const LARGE_1MB_EXPECTATION = largeBodyExpectation('/large_1mb', 'large-1mb');
export const LARGE_10MB_EXPECTATION = largeBodyExpectation('/large_10mb', 'large-10mb');

// Item 15d — file-backed RESPONSE body arm (FileBodyMaterialiser path). The file
// must exist on the SUT filesystem; perf-test-run.sh generates and mounts it and
// passes its server-side path via K6_REG_FILE_BODY_PATH. When that is empty the
// arm is absent.
export function fileBodyExpectation(filePath) {
  return {
    httpRequest: { path: '/large_file' },
    httpResponse: {
      statusCode: 200,
      body: { type: 'FILE', filePath, contentType: 'application/json' },
    },
    times: { unlimited: true },
  };
}

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

// --- item 12: LLM/SSE streaming under concurrency ----------------------------
//
// Build an httpSseResponse expectation whose `events` each carry a DETERMINISTIC
// per-event delay (no streaming-physics jitter), so the server's inter-token
// timing is a clean actual-minus-requested measurement. Each event's `data` is a
// short "t<idx>" token kept small ON PURPOSE — the whole events list is retained
// per matched request in the count-bounded event-log ring, so bytesPerToken
// drives the retained-heap arithmetic documented in config.js STREAMING.
//
// Every event including the first carries the delay so the reader's inter-arrival
// gaps between CONSECUTIVE data lines are all governed by the same requested
// delay (the reader discards the first gap, which also absorbs connection/TTFB
// overhead — see tools/sse-fidelity-reader.py).
export function streamingSseExpectation(path, tokens, delayMs) {
  const events = [];
  for (let i = 0; i < tokens; i += 1) {
    events.push({ data: `t${i}`, delay: { timeUnit: 'MILLISECONDS', value: delayMs } });
  }
  return {
    httpRequest: { path },
    httpSseResponse: { statusCode: 200, events },
    times: { unlimited: true },
  };
}

// Seed the streaming.js expectations: the SSE stream (item 12's load) and the
// plain /simple match used by the within-run A/B. When K6_STREAM_MATCH_DELAY_MS
// > 0 a fixed server-side delay is added to the match response so a run can prove
// the match percentiles still move with a REAL slowdown (positive control).
export function seedStreaming() {
  const matchResponse = { statusCode: 200, body: 'some simple response' };
  if (STREAMING.matchDelayMs > 0) {
    matchResponse.delay = { timeUnit: 'MILLISECONDS', value: STREAMING.matchDelayMs };
  }
  const expectations = [
    { httpRequest: { path: STREAMING.matchPath }, httpResponse: matchResponse, times: { unlimited: true } },
    streamingSseExpectation(STREAMING.streamPath, STREAMING.tokens, STREAMING.delayMs),
  ];
  const res = http.put(`${CONFIG.controlPlane}/expectation`, JSON.stringify(expectations), jsonParams());
  if (res.status !== 201 && res.status !== 200) {
    fail(`failed to seed streaming expectations: HTTP ${res.status} ${res.body}`);
  }
  return res;
}

// Fail-loud probe of the streaming arms (streaming.js setup()). Both the SSE
// stream and the match path must answer before a run baselines their numbers —
// a stream that 404s would otherwise record a silent 100%-error load arm and a
// heap/fidelity measurement of NOTHING.
export function verifyStreamingArms() {
  const m = http.get(`${CONFIG.baseUrl}${STREAMING.matchPath}`, { headers: CONFIG.keepAliveHeaders });
  if (m.status !== 200) {
    fail(`streaming match arm: GET ${STREAMING.matchPath} returned HTTP ${m.status} — the SUT is not seeded. Body: ${m.body}`);
  }
  // The SSE stream returns the whole (delayed) body to k6's buffering client, so
  // this probe also blocks for ~tokens×delay ms; keep tokens modest. A 200 with a
  // text/event-stream content-type confirms the streaming action is wired.
  const s = http.get(`${CONFIG.baseUrl}${STREAMING.streamPath}`, { headers: CONFIG.keepAliveHeaders });
  if (s.status !== 200) {
    fail(`streaming arm: GET ${STREAMING.streamPath} returned HTTP ${s.status} — the httpSseResponse expectation is not serving. Body: ${s.body}`);
  }
}

// Actions (tagged so k6 reports per-operation).
export function getStream(extraTags) {
  // A VU calling this blocks for the whole (delayed) stream, so a constant-VUs pool
  // of N holds ~N streams open — the concurrency knob. No `check` on the body: the
  // measurement of interest is server-side (heap/fidelity), and a status check adds
  // no value while the VU is occupied for seconds per iteration.
  const res = http.get(`${CONFIG.baseUrl}${STREAMING.streamPath}`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'stream', name: `GET ${STREAMING.streamPath} (SSE)`, ...(extraTags || {}) },
    // The response body is discarded (responseType none) so k6 does not retain the
    // whole streamed body per VU — the client-side memory footprint of holding N
    // concurrent streams open must not itself become the bottleneck.
    responseType: 'none',
  });
  return res;
}

export function getStreamMatch(op, extraTags) {
  const res = http.get(`${CONFIG.baseUrl}${STREAMING.matchPath}`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op, name: `GET ${STREAMING.matchPath}`, ...(extraTags || {}) },
  });
  check(res, { [`${op}: 200`]: (r) => r.status === 200 });
  return res;
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
    MUSTACHE_TEMPLATE_EXPECTATION, // item 15a — always on (jmustache is bundled)
    forwardExpectation(host),
    LARGE_BODY_EXPECTATION, // 4 KB (existing `large` arm)
    LARGE_1MB_EXPECTATION, // item 15d
    LARGE_10MB_EXPECTATION, // item 15d
  ];
  // item 15a — the JavaScript arm needs GraalJS; seed it only when enabled.
  if (REGRESSION.jsTemplate) {
    expectations.push(JS_TEMPLATE_EXPECTATION);
  }
  // item 15d — the file-backed arm needs a server-side file; seed only when set.
  if (REGRESSION.fileBodyPath) {
    expectations.push(fileBodyExpectation(REGRESSION.fileBodyPath));
  }
  const res = http.put(`${CONFIG.controlPlane}/expectation`, JSON.stringify(expectations), jsonParams());
  if (res.status !== 201 && res.status !== 200) {
    fail(`failed to seed regression expectations: HTTP ${res.status} ${res.body}`);
  }
  return res;
}

// Fail-loud verification of the OPTIONAL arms (item 15a JavaScript, item 15d
// file-backed body). Both depend on server-side capability that may be absent (a
// non-GraalJS image; a missing/unreadable file) and would otherwise record a
// silent 100%-error arm that reads as a result. Probe each enabled arm ONCE and
// fail() the whole run if it does not answer 200 — a broken arm must abort the
// run, never be baselined as a zero. Called from regression.js setup().
export function verifyRegressionArms() {
  if (REGRESSION.jsTemplate) {
    const res = http.get(`${CONFIG.baseUrl}/template_javascript`, { headers: CONFIG.keepAliveHeaders });
    if (res.status !== 200) {
      fail(
        `JavaScript template arm (K6_REG_JS_TEMPLATE) is enabled but /template_javascript returned HTTP ${res.status} — ` +
        `the SUT image almost certainly lacks the GraalJS engine (use a -graaljs image, or set K6_REG_JS_TEMPLATE=false). ` +
        `Body: ${res.body}`,
      );
    }
  }
  if (REGRESSION.fileBodyPath) {
    const res = http.get(`${CONFIG.baseUrl}/large_file`, { headers: CONFIG.keepAliveHeaders });
    if (res.status !== 200) {
      fail(
        `File-backed body arm (K6_REG_FILE_BODY_PATH=${REGRESSION.fileBodyPath}) is enabled but /large_file returned ` +
        `HTTP ${res.status} — the SUT cannot read that file (check the mount/path). Body: ${res.body}`,
      );
    }
  }
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

// item 15a — Mustache template arm.
export function getTemplatedMustache(extraTags) {
  const res = http.get(`${CONFIG.baseUrl}/template_mustache`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'template_mustache', name: 'GET /template_mustache', ...(extraTags || {}) },
  });
  check(res, { 'template_mustache: 200': (r) => r.status === 200 });
  return res;
}

// item 15a — JavaScript template arm (GraalJS).
export function getTemplatedJavaScript(extraTags) {
  const res = http.get(`${CONFIG.baseUrl}/template_javascript`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'template_javascript', name: 'GET /template_javascript', ...(extraTags || {}) },
  });
  check(res, { 'template_javascript: 200': (r) => r.status === 200 });
  return res;
}

// item 15d — POST a large JSON body (server decodes the whole body; matches only
// the small marker). LARGE_BODIES[0] is 1 MB, [1] is 10 MB.
export function postLarge1mb(extraTags) {
  const res = http.post(`${CONFIG.baseUrl}/large_1mb`, LARGE_BODIES[0].body,
    jsonParams({ op: 'large_1mb', name: 'POST /large_1mb', ...(extraTags || {}) }));
  check(res, { 'large_1mb: 200': (r) => r.status === 200 });
  return res;
}

export function postLarge10mb(extraTags) {
  const res = http.post(`${CONFIG.baseUrl}/large_10mb`, LARGE_BODIES[1].body,
    jsonParams({ op: 'large_10mb', name: 'POST /large_10mb', ...(extraTags || {}) }));
  check(res, { 'large_10mb: 200': (r) => r.status === 200 });
  return res;
}

// item 15d — GET a response served from a file on the SUT (FileBodyMaterialiser).
export function getLargeFile(extraTags) {
  const res = http.get(`${CONFIG.baseUrl}/large_file`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'large_file', name: 'GET /large_file', ...(extraTags || {}) },
  });
  check(res, { 'large_file: 200': (r) => r.status === 200 });
  return res;
}

// --- proxy.js: item 9a (forward proxy) + item 14 (TLS handshake) actions ------
//
// The SUT under measurement in proxy.js's FORWARD mode is configured as a forward
// PROXY, so these arms do NOT seed it — they route a request at the UPSTREAM and
// let MockServer relay it. The run step seeds the upstream's /simple; k6 only
// probes the arms in setup() (verifyProxyForwardArms) and fails loud if the relay
// does not work, never recording a silent 100%-error arm.

// item 9a — absolute-URI forwarding. With HTTP_PROXY pointed at the SUT, an http://
// target is sent to the SUT as an absolute-URI GET; the SUT forwards it to the
// upstream and relays the response. Keep-Alive so connections are reused (a real
// proxy client), which keeps the measured latency about the RELAY, not handshakes.
export function getProxiedAbsolute(extraTags) {
  const res = http.get(`http://${PROXY.upstreamHost}/simple`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'forward_absolute', name: 'GET http://upstream/simple (absolute-URI)', ...(extraTags || {}) },
  });
  check(res, { 'forward_absolute: 200': (r) => r.status === 200 });
  return res;
}

// item 9a — CONNECT tunnel carrying HTTPS. With HTTPS_PROXY pointed at the SUT, an
// https:// target makes k6 issue CONNECT <upstream> to the SUT, which establishes a
// tunnel; k6 then performs a TLS handshake over that tunnel and the request reaches
// the upstream (the response body confirms the relay landed there). NOTE: this
// measures the proxy CONNECT PATH, not necessarily an end-to-end-to-upstream TLS
// session — MockServer's forward proxy may terminate the CONNECT TLS itself with a
// dynamically generated certificate (a MITM), in which case http_req_tls_handshaking
// is timed against the SUT. Either way a NON-ZERO http_req_tls_handshaking proves a
// real TLS handshake was carried over the tunnel (the arm is genuinely a TLS path,
// not a plain request), which is what the setup() guard asserts.
export function getProxiedConnect(extraTags) {
  const res = http.get(`https://${PROXY.upstreamHost}/simple`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'forward_connect', name: 'GET https://upstream/simple (CONNECT tunnel)', ...(extraTags || {}) },
  });
  check(res, { 'forward_connect: 200': (r) => r.status === 200 });
  return res;
}

// item 14 — a direct-TLS GET against a handshake SUT's seeded /simple. With
// noConnectionReuse (proxy.js handshake mode) every call is a fresh TCP+TLS
// handshake, so http_req_tls_handshaking is paid on EVERY iteration (never
// amortised). The body is header-only/tiny so the handshake dominates.
export function getHandshakeSimple(baseUrl, op, extraTags) {
  const res = http.get(`${baseUrl}/simple`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op, name: `GET ${op} /simple (fresh TLS)`, ...(extraTags || {}) },
  });
  check(res, { [`${op}: 200`]: (r) => r.status === 200 });
  return res;
}

// Fail-loud probe of the FORWARD-mode arms: the absolute-URI and CONNECT relays
// must each return 200 from the upstream. Called from proxy.js setup().
export function verifyProxyForwardArms() {
  const abs = http.get(`http://${PROXY.upstreamHost}/simple`, { headers: CONFIG.keepAliveHeaders });
  if (abs.status !== 200) {
    fail(`forward_absolute arm: GET http://${PROXY.upstreamHost}/simple via proxy returned HTTP ${abs.status} — the SUT is not forwarding absolute-URI requests to the upstream (check HTTP_PROXY + upstream seed). Body: ${abs.body}`);
  }
  const conn = http.get(`https://${PROXY.upstreamHost}/simple`, { headers: CONFIG.keepAliveHeaders });
  if (conn.status !== 200) {
    fail(`forward_connect arm: GET https://${PROXY.upstreamHost}/simple via CONNECT tunnel returned HTTP ${conn.status} — the SUT is not tunnelling CONNECT to the upstream (check HTTPS_PROXY + upstream TLS). Body: ${conn.body}`);
  }
  // The CONNECT arm MUST have carried a real TLS handshake over the tunnel (whether
  // terminated at the upstream or at the SUT's MITM cert); a zero handshake time
  // would mean no TLS occurred, i.e. this is not a real HTTPS CONNECT path. Fail the
  // run loudly rather than baseline an arm that never carried TLS.
  if (!(conn.timings && conn.timings.tls_handshaking > 0)) {
    fail(`forward_connect arm: CONNECT to ${PROXY.upstreamHost} completed with tls_handshaking=${conn.timings ? conn.timings.tls_handshaking : 'n/a'} ms — no TLS handshake was carried over the tunnel, so this is NOT a real HTTPS CONNECT path.`);
  }
}

// Fail-loud probe of the HANDSHAKE-mode arms: each ENABLED direct-TLS SUT must
// answer /simple with 200, over a genuine TLS handshake. Called from proxy.js
// setup(). An enabled arm that cannot handshake aborts the run rather than
// recording a silent 100%-error / zero-handshake arm.
export function verifyHandshakeArms() {
  const arms = [
    ['tls13', PROXY.tls13Url],
    ['mtls', PROXY.mtlsUrl],
    ['jdk', PROXY.jdkUrl],
  ];
  for (const [name, url] of arms) {
    if (!url) {
      continue;
    }
    const res = http.get(`${url}/simple`, { headers: CONFIG.keepAliveHeaders });
    if (res.status !== 200) {
      fail(`handshake_${name} arm: GET ${url}/simple returned HTTP ${res.status} — the SUT is not reachable over TLS / not seeded / (mtls) rejected the client certificate. Body: ${res.body}`);
    }
    if (!(res.timings && res.timings.tls_handshaking > 0)) {
      fail(`handshake_${name} arm: ${url}/simple answered but tls_handshaking=${res.timings ? res.timings.tls_handshaking : 'n/a'} ms — no TLS handshake was measured, so the handshake numbers would be meaningless.`);
    }
  }
}
