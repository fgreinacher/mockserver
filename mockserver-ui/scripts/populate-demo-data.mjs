#!/usr/bin/env node
/**
 * populate-demo-data.mjs
 * ----------------------
 * Populate a running MockServer with a rich, varied dataset so every dashboard
 * UI view can be exercised by hand:
 *
 *   - Active expectations    plain HTTP (varied verbs / status / bodies / headers
 *                            / query / cookies / delay / times / priority), a
 *                            forward, and LLM response mocks for every provider.
 *   - Traffic               recorded request/response pairs (matched + unmatched)
 *                            including one classified lane per LLM provider, each
 *                            carrying token usage so the cost detail populates.
 *   - Sessions / call graph  multi-turn agent loops sharing an isolation header,
 *                            grouped via a conversation expectation's scenarioName.
 *   - Predicate pills        a showcase conversation expectation exercising every
 *                            predicate type (incl. semanticMatchAgainst + a
 *                            normalization block) and a chaos profile.
 *   - Service chaos          a few service-scoped chaos registrations (varied fault
 *                            types, two with an auto-revert TTL) for the Chaos tab.
 *   - TCP chaos              a few TCP-layer chaos registrations (raw byte-stream
 *                            faults, two with an auto-revert TTL) for the Chaos tab.
 *   - gRPC health chaos      a few forced gRPC health-check serving statuses
 *                            (NOT_SERVING / SERVICE_UNKNOWN / SERVING) for the Chaos tab.
 *   - gRPC fault injection   a few gRPC fault-injection chaos registrations
 *                            (status errors + latency + quota) for the Chaos tab.
 *   - gRPC mocks             server-streaming, unary, and error gRPC expectations
 *                            (Mocks page · gRPC kind).
 *   - DNS mocks              A / AAAA / CNAME / NXDOMAIN DNS expectations
 *                            (Mocks page · DNS kind).
 *   - WASM module            an example Rust WASM match module uploaded to the
 *                            store + a WASM-body-matched expectation (Library page).
 *   - Side-effect exps       expectations with before-actions (blocking + non-blocking
 *                            audit calls) and after-actions (fire-and-forget webhooks),
 *                            loadable into the Composer's side-effects panel.
 *   - Scenarios              seeded scenario state machines (incl. a timed auto-transition
 *                            + a cross-protocol trigger expectation) listed in the
 *                            Sessions · Scenarios panel.
 *   - gRPC descriptors       a compiled protobuf FileDescriptorSet (greeting.dsc) uploaded
 *                            so the Library · gRPC Descriptors tab lists the service/methods.
 *   - Cassettes              example cassette fixtures (scripts/demo-cassettes/) registered in the
 *                            server-side cassette registry so they list in Library · Cassettes.
 *
 * It talks to MockServer over its plain REST API (no extra dependencies — uses
 * the built-in global fetch in Node 18+). Safe to re-run: it resets first.
 *
 * Usage:
 *   node scripts/populate-demo-data.mjs [--url http://localhost:1080] [--quiet]
 *   MOCKSERVER_URL=http://localhost:1080 node scripts/populate-demo-data.mjs
 */

import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------------------
// Configuration & tiny CLI parsing
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const opts = { url: process.env.MOCKSERVER_URL || 'http://localhost:1080', quiet: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--url') {
      opts.url = argv[++i];
      if (opts.url == null) { console.error('ERROR: --url requires a value'); process.exit(1); }
    }
    else if (arg.startsWith('--url=')) opts.url = arg.slice('--url='.length);
    else if (arg === '--quiet') opts.quiet = true;
    else if (arg === '--help' || arg === '-h') {
      console.log('Usage: node scripts/populate-demo-data.mjs [--url <baseUrl>] [--quiet]');
      process.exit(0);
    }
  }
  opts.url = opts.url.replace(/\/+$/, '');
  return opts;
}

const { url: BASE, quiet } = parseArgs(process.argv.slice(2));

// Self address — used to forward /proxy/* paths back to this same MockServer so
// the demo can produce proxied (forwarded) traffic without an external upstream.
const TARGET = new URL(BASE);
const SELF_HOST = TARGET.hostname;
const SELF_PORT = Number(TARGET.port || (TARGET.protocol === 'https:' ? 443 : 80));
const SELF_SCHEME = TARGET.protocol === 'https:' ? 'HTTPS' : 'HTTP';

const counts = { expectations: 0, requests: 0, unmatched: 0, serviceChaos: 0, tcpChaos: 0, grpcHealth: 0, grpcChaos: 0, experiments: 0, drift: 0, wasmModules: 0, scenarios: 0, grpcServices: 0, sideEffects: 0, cassettes: 0, asyncChannels: 0, mcpCalls: 0, mcpServerCalls: 0, loadScenario: 0 };
function log(msg) { if (!quiet) console.log(msg); }

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

async function api(method, path, body, headers = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'content-type': 'application/json', ...headers },
    body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)),
  });
  // Drain the body so the connection is freed (and SSE streams complete).
  await res.text().catch(() => undefined);
  return res;
}

async function expectation(label, expectationJson) {
  const res = await api('PUT', '/mockserver/expectation', expectationJson);
  if (!res.ok) throw new Error(`Failed to create expectation "${label}": HTTP ${res.status}`);
  counts.expectations++;
  log(`   + expectation  ${label}`);
}

async function traffic(label, method, path, { body, headers } = {}) {
  const res = await api(method, path, body, headers);
  if (res.status === 404) counts.unmatched++;
  counts.requests++;
  log(`   > ${method.padEnd(4)} ${path}  ->  ${res.status}   (${label})`);
}

// ---------------------------------------------------------------------------
// Provider catalogue (paths + request bodies + token usage)
// ---------------------------------------------------------------------------

const PROVIDERS = {
  ANTHROPIC: {
    model: 'claude-sonnet-4-20250514',
    path: '/v1/messages',
    usage: { inputTokens: 1840, outputTokens: 320 },
    request: {
      model: 'claude-sonnet-4-20250514',
      max_tokens: 1024,
      messages: [{ role: 'user', content: 'What is the weather in Paris?' }],
    },
  },
  OPENAI: {
    model: 'gpt-4o',
    path: '/v1/chat/completions',
    usage: { inputTokens: 920, outputTokens: 210 },
    request: {
      model: 'gpt-4o',
      messages: [{ role: 'user', content: 'Summarise the quarterly report.' }],
    },
  },
  OPENAI_RESPONSES: {
    model: 'gpt-4o',
    path: '/v1/responses',
    usage: { inputTokens: 1500, outputTokens: 540 },
    request: {
      model: 'gpt-4o',
      input: [{ role: 'user', content: 'Draft a release announcement.' }],
    },
  },
  GEMINI: {
    model: 'gemini-2.0-flash',
    path: '/v1beta/models/gemini-2.0-flash/generateContent',
    usage: { inputTokens: 2100, outputTokens: 410 },
    request: {
      contents: [{ role: 'user', parts: [{ text: 'Explain vector databases briefly.' }] }],
    },
  },
  OLLAMA: {
    model: 'llama3.2',
    path: '/api/chat',
    usage: { inputTokens: 300, outputTokens: 120 },
    request: {
      model: 'llama3.2',
      messages: [{ role: 'user', content: 'Give me a haiku about testing.' }],
    },
  },
};

const SCENARIO = '__llm_conv_weather_agent__iso=header:x-agent-id';

// A SECOND isolated conversation on a different provider (OpenAI), path
// (/v1/chat/completions) and isolation key (header x-session-id). Driving it
// for several session ids gives the Sessions tab additional distinct lanes
// under a different provider, alongside the weather-agent lanes above.
const SUPPORT_SCENARIO = '__llm_conv_support_bot__iso=header:x-session-id';

// ---------------------------------------------------------------------------
// 1. Plain HTTP expectations
// ---------------------------------------------------------------------------

async function plainHttpExpectations() {
  log('\n→ Plain HTTP expectations');

  await expectation('GET /api/users (JSON list)', {
    httpRequest: { method: 'GET', path: '/api/users' },
    httpResponse: {
      statusCode: 200,
      headers: { 'content-type': ['application/json'], 'x-demo': ['users'] },
      body: { json: [{ id: 1, name: 'Alice' }, { id: 2, name: 'Bob' }] },
    },
  });

  await expectation('GET /api/users/{id} (path + query)', {
    httpRequest: {
      method: 'GET',
      path: '/api/users/42',
      queryStringParameters: { expand: ['profile', 'orders'] },
    },
    httpResponse: {
      statusCode: 200,
      body: { json: { id: 42, name: 'Carol', profile: { tier: 'gold' } } },
    },
  });

  await expectation('POST /api/users (201 created)', {
    httpRequest: {
      method: 'POST',
      path: '/api/users',
      headers: { 'content-type': ['application/json'] },
    },
    httpResponse: {
      statusCode: 201,
      headers: { location: ['/api/users/99'] },
      body: { json: { id: 99, status: 'created' } },
    },
  });

  await expectation('PUT /api/users/42 (204 no content)', {
    httpRequest: { method: 'PUT', path: '/api/users/42' },
    httpResponse: { statusCode: 204 },
  });

  await expectation('DELETE /api/users/42 (limited times)', {
    httpRequest: { method: 'DELETE', path: '/api/users/42' },
    httpResponse: { statusCode: 200, body: 'deleted' },
    times: { remainingTimes: 2, unlimited: false },
  });

  await expectation('GET /api/report.xml (XML body + delay)', {
    httpRequest: { method: 'GET', path: '/api/report.xml' },
    httpResponse: {
      statusCode: 200,
      headers: { 'content-type': ['application/xml'] },
      body: '<report><total>128</total><status>ok</status></report>',
      delay: { timeUnit: 'MILLISECONDS', value: 400 },
    },
  });

  await expectation('GET /api/page.html (HTML body)', {
    httpRequest: { method: 'GET', path: '/api/page.html' },
    httpResponse: {
      statusCode: 200,
      headers: { 'content-type': ['text/html'] },
      body: '<html><body><h1>Demo</h1></body></html>',
    },
  });

  await expectation('POST /api/login (cookie + 401 variant)', {
    httpRequest: {
      method: 'POST',
      path: '/api/login',
      cookies: { session: 'expired' },
    },
    httpResponse: {
      statusCode: 401,
      body: { json: { error: 'session expired' } },
    },
  });

  await expectation('GET /api/flaky (500 error)', {
    httpRequest: { method: 'GET', path: '/api/flaky' },
    httpResponse: { statusCode: 500, body: { json: { error: 'internal error' } } },
  });

  await expectation('GET /api/priority (high priority override)', {
    priority: 100,
    httpRequest: { method: 'GET', path: '/api/priority' },
    httpResponse: { statusCode: 200, body: 'high-priority winner' },
  });

}

// ---------------------------------------------------------------------------
// 1b. Proxy / forward expectations + traffic (self-forwarded — no upstream needed)
// ---------------------------------------------------------------------------

// Forwarded ("proxied") request/response pairs power the dashboard's proxied
// traffic lane and proxied sessions. To produce them without an external
// upstream, each /proxy/* path is forwarded back to a mock on THIS MockServer
// (a path rewrite via httpOverrideForwardedRequest), so the demo works offline.
const PROXY_FORWARDS = [
  { from: '/proxy/users', to: '/api/users' },
  { from: '/proxy/report', to: '/api/report.xml' },
  { from: '/proxy/flaky', to: '/api/flaky' },
];

async function proxyExpectations() {
  log('\n→ Proxy / forward expectations (self-forwarded, no upstream needed)');
  for (const f of PROXY_FORWARDS) {
    await expectation(`forward ${f.from} -> ${f.to}`, {
      httpRequest: { method: 'GET', path: f.from },
      httpOverrideForwardedRequest: {
        httpRequest: {
          path: f.to,
          socketAddress: { host: SELF_HOST, port: SELF_PORT, scheme: SELF_SCHEME },
        },
      },
    });
  }
}

async function proxyTraffic() {
  log('\n→ Proxied traffic (forwarded request/response pairs)');
  await traffic('proxied users', 'GET', '/proxy/users');
  await traffic('proxied report', 'GET', '/proxy/report');
  await traffic('proxied flaky (500)', 'GET', '/proxy/flaky');
  await traffic('proxied users (again)', 'GET', '/proxy/users');
}

// ---------------------------------------------------------------------------
// 1c. Interactive-breakpoint loopback (Breakpoints panel · Live Exchanges / Live Streams)
// ---------------------------------------------------------------------------

// Breakpoints pause FORWARDED traffic and are resolved interactively over the
// callback WebSocket by a connected callback client — the dashboard Breakpoints
// panel is one. To give the panel something to pause, we set up a self-forwarded
// (loopback) target with NO external upstream:
//   GET /breakpoint/exchange  -> forwards to a buffered JSON mock on THIS server
//                                 (RESPONSE-phase breakpoint -> Live Exchanges)
//   GET /breakpoint/stream    -> forwards to a chunked streaming mock on THIS
//                                 server (RESPONSE_STREAM breakpoint -> Live Streams)
// The matched forward (httpOverrideForwardedRequest + socketAddress -> SELF) is
// what makes the request a forwarded exchange the breakpoint engine can hold.
async function breakpointLoopbackExpectations() {
  log('\n→ Interactive-breakpoint loopback (Breakpoints · Live Exchanges / Live Streams)');

  // Upstream mock served directly (the forward target) — buffered JSON.
  await expectation('breakpoint upstream (JSON, buffered)', {
    httpRequest: { method: 'GET', path: '/breakpoint-upstream/exchange' },
    httpResponse: {
      statusCode: 200,
      headers: { 'content-type': ['application/json'] },
      body: JSON.stringify({ id: 'demo-42', name: 'Ada Lovelace', role: 'forwarded-upstream', note: 'pause me at the response breakpoint, then Continue / Modify / Abort' }),
    },
  });

  // Upstream mock served directly — chunked streaming (SSE-style), so the
  // forwarded response is delivered frame-by-frame and each frame can be held.
  await expectation('breakpoint upstream (chunked stream)', {
    httpRequest: { method: 'GET', path: '/breakpoint-upstream/stream' },
    httpResponse: {
      statusCode: 200,
      headers: { 'content-type': ['text/event-stream'] },
      body: 'data: frame-1 hello\n\ndata: frame-2 from\n\ndata: frame-3 the\n\ndata: frame-4 loopback\n\ndata: frame-5 stream\n\ndata: frame-6 continue/modify/drop/inject/close me\n\n',
      // chunkSize < body length => Transfer-Encoding: chunked, multiple frames;
      // chunkDelay paces them so the per-frame holds are easy to watch.
      connectionOptions: { chunkSize: 20, chunkDelay: { timeUnit: 'MILLISECONDS', value: 600 } },
    },
  });

  // Loopback forwards: a matched forward back to THIS server (path-rewrite +
  // socketAddress -> SELF), identical pattern to the /proxy/* forwards above.
  await expectation('breakpoint forward  /breakpoint/exchange -> upstream', {
    httpRequest: { method: 'GET', path: '/breakpoint/exchange' },
    httpOverrideForwardedRequest: {
      httpRequest: { path: '/breakpoint-upstream/exchange', socketAddress: { host: SELF_HOST, port: SELF_PORT, scheme: SELF_SCHEME } },
    },
  });
  await expectation('breakpoint forward  /breakpoint/stream   -> upstream', {
    httpRequest: { method: 'GET', path: '/breakpoint/stream' },
    httpOverrideForwardedRequest: {
      httpRequest: { path: '/breakpoint-upstream/stream', socketAddress: { host: SELF_HOST, port: SELF_PORT, scheme: SELF_SCHEME } },
    },
  });
}

// ---------------------------------------------------------------------------
// 2. LLM response expectations (one per provider + tool / streaming / chaos)
// ---------------------------------------------------------------------------

async function llmExpectations() {
  log('\n→ LLM response expectations');

  for (const [provider, p] of Object.entries(PROVIDERS)) {
    await expectation(`${provider} single-shot (${p.model})`, {
      httpRequest: { method: 'POST', path: p.path },
      httpLlmResponse: {
        provider,
        model: p.model,
        completion: {
          text: `Mocked ${provider} reply with realistic token usage.`,
          stopReason: 'end_turn',
          usage: p.usage,
        },
      },
    });
  }

  await expectation('ANTHROPIC tool-call (get_weather)', {
    // Higher priority so these query-scoped variants win over the catch-all
    // single-shot expectations that share the same path.
    priority: 20,
    httpRequest: { method: 'POST', path: '/v1/messages', queryStringParameters: { demo: ['tools'] } },
    httpLlmResponse: {
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-20250514',
      completion: {
        text: 'Let me look that up.',
        toolCalls: [{ id: 'toolu_demo1', name: 'get_weather', arguments: '{"city":"Paris"}' }],
        stopReason: 'tool_use',
        usage: { inputTokens: 640, outputTokens: 60 },
      },
    },
  });

  await expectation('OPENAI streaming (SSE physics)', {
    priority: 20,
    httpRequest: { method: 'POST', path: '/v1/chat/completions', queryStringParameters: { stream: ['true'] } },
    httpLlmResponse: {
      provider: 'OPENAI',
      model: 'gpt-4o',
      completion: {
        text: 'This response is streamed token by token so you can watch the SSE timeline render.',
        streaming: true,
        streamingPhysics: { tokensPerSecond: 40, jitter: 0.25 },
        usage: { inputTokens: 120, outputTokens: 60 },
      },
    },
  });

  await expectation('OPENAI chaos (429 + Retry-After)', {
    priority: 20,
    httpRequest: { method: 'POST', path: '/v1/chat/completions', queryStringParameters: { chaos: ['429'] } },
    httpLlmResponse: {
      provider: 'OPENAI',
      model: 'gpt-4o',
      completion: { text: 'You should not see this — chaos injects an error.', usage: { inputTokens: 50, outputTokens: 10 } },
      chaos: { errorStatus: 429, retryAfter: '30', errorProbability: 1.0, seed: 42 },
    },
  });
}

// ---------------------------------------------------------------------------
// 3. Conversation expectations (Sessions grouping + predicate pills)
// ---------------------------------------------------------------------------

async function conversationExpectations() {
  log('\n→ Conversation expectations (Sessions + predicate pills)');

  // Turn expectations that actually drive the agent loop below. turnIndex is the
  // count of prior user turns in the request, so 0/1/2 match successive calls.
  await expectation('weather agent · turn 0 (tool_use)', {
    scenarioName: SCENARIO,
    priority: 15,
    httpRequest: { method: 'POST', path: '/v1/messages' },
    httpLlmResponse: {
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-20250514',
      completion: {
        text: 'Let me search for the weather.',
        toolCalls: [{ id: 'toolu_w0', name: 'search_weather', arguments: '{"city":"Paris"}' }],
        stopReason: 'tool_use',
        usage: { inputTokens: 210, outputTokens: 40 },
      },
      conversationPredicates: { turnIndex: 0, latestMessageRole: 'USER' },
    },
  });

  await expectation('weather agent · turn 1 (final answer, after tool_result)', {
    scenarioName: SCENARIO,
    priority: 15,
    httpRequest: { method: 'POST', path: '/v1/messages' },
    httpLlmResponse: {
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-20250514',
      completion: {
        text: 'Here is the current weather for that city.',
        stopReason: 'end_turn',
        usage: { inputTokens: 360, outputTokens: 24 },
      },
      conversationPredicates: { turnIndex: 1, containsToolResultFor: 'search_weather' },
    },
  });

  // Showcase expectation exercising EVERY predicate type + normalization + chaos,
  // so the dashboard renders the full set of predicate pills. A high turnIndex
  // keeps it from intercepting the loop traffic above — it is here to be seen.
  await expectation('predicate showcase (all pills + normalization + chaos)', {
    scenarioName: SCENARIO,
    httpRequest: { method: 'POST', path: '/v1/messages' },
    httpLlmResponse: {
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-20250514',
      completion: {
        text: 'Showcase response demonstrating every conversation predicate.',
        stopReason: 'end_turn',
        usage: { inputTokens: 800, outputTokens: 90 },
      },
      conversationPredicates: {
        turnIndex: 9,
        latestMessageContains: 'weather',
        latestMessageMatches: '^What is the.*Paris.*\\?$',
        latestMessageRole: 'USER',
        containsToolResultFor: 'search_weather',
        semanticMatchAgainst: 'the user is asking about the weather forecast',
        normalization: {
          collapseWhitespace: true,
          lowercase: false,
          sortJsonKeys: true,
          dropBuiltInVolatileFields: true,
          dropVolatileFields: ['request_id', 'timestamp'],
        },
      },
    },
  });

  // Second isolated conversation: an OpenAI "support bot" isolated by the
  // x-session-id header (SUPPORT_SCENARIO). A two-turn loop — user question →
  // assistant tool_use (lookup_order) → user tool_result → assistant final
  // answer — driven below for several session ids so the Sessions tab shows
  // additional distinct OpenAI lanes. turnIndex counts prior user turns.
  await expectation('support bot · turn 0 (tool_use)', {
    scenarioName: SUPPORT_SCENARIO,
    priority: 15,
    httpRequest: { method: 'POST', path: '/v1/chat/completions' },
    httpLlmResponse: {
      provider: 'OPENAI',
      model: 'gpt-4o',
      completion: {
        text: 'Let me look up your order status.',
        toolCalls: [{ id: 'call_s0', name: 'lookup_order', arguments: '{"orderId":"A-4291"}' }],
        stopReason: 'tool_calls',
        usage: { inputTokens: 180, outputTokens: 32 },
      },
      conversationPredicates: { turnIndex: 0, latestMessageRole: 'USER' },
    },
  });

  await expectation('support bot · turn 1 (final answer, after tool_result)', {
    scenarioName: SUPPORT_SCENARIO,
    priority: 15,
    httpRequest: { method: 'POST', path: '/v1/chat/completions' },
    httpLlmResponse: {
      provider: 'OPENAI',
      model: 'gpt-4o',
      completion: {
        text: 'Your order A-4291 shipped yesterday and arrives Thursday.',
        stopReason: 'stop',
        usage: { inputTokens: 300, outputTokens: 22 },
      },
      conversationPredicates: { turnIndex: 1, containsToolResultFor: 'lookup_order' },
    },
  });
}

// ---------------------------------------------------------------------------
// 3b. Service-scoped chaos (Chaos tab)
// ---------------------------------------------------------------------------

// Registered against the control-plane /mockserver/serviceChaos endpoint so the
// dashboard's Chaos tab has example data: a mix of fault types, and two TTL-bearing
// registrations so the live auto-revert countdown is visible. These are applied to
// the matched-forward path keyed by Host, so they do not affect the demo's own
// traffic — they exist to populate the Chaos tab's "active registrations" view.
const SERVICE_CHAOS = [
  {
    host: 'payments.svc',
    chaos: { errorStatus: 503, errorProbability: 0.3, latency: { timeUnit: 'MILLISECONDS', value: 500 } },
    ttlMillis: 600000,
  },
  {
    host: 'checkout.svc',
    chaos: { errorStatus: 429, retryAfter: '30', errorProbability: 1.0 },
    ttlMillis: 300000,
  },
  {
    host: 'inventory.svc',
    chaos: { dropConnectionProbability: 0.25 },
  },
  {
    host: 'recommendations.svc',
    chaos: { latency: { timeUnit: 'MILLISECONDS', value: 1200 } },
  },
  {
    host: 'graphql-gateway.svc',
    chaos: {
      errorStatus: 200,
      graphqlErrors: true,
      graphqlErrorMessage: 'Rate limit exceeded',
      graphqlErrorCode: 'RATE_LIMITED',
      graphqlNullifyData: true,
      seed: 42,
      succeedFirst: 3,
      failRequestCount: 10,
    },
    ttlMillis: 900000,
  },
];

async function serviceChaosExamples() {
  log('\n→ Service-scoped chaos (Chaos tab)');
  for (const entry of SERVICE_CHAOS) {
    const res = await api('PUT', '/mockserver/serviceChaos', entry);
    if (!res.ok) throw new Error(`Failed to register service chaos for "${entry.host}": HTTP ${res.status}`);
    counts.serviceChaos++;
    log(`   ~ service chaos  ${entry.host}${entry.ttlMillis ? `  (ttl ${entry.ttlMillis}ms)` : ''}`);
  }
}

// TCP-layer chaos registrations (Chaos tab → "TCP-Layer Chaos" section). These exercise
// the raw byte-stream fault types (Toxiproxy-style) keyed by upstream host, distinct from
// the HTTP-semantic faults above. Two carry an auto-revert TTL so the countdown is visible.
const TCP_CHAOS = [
  {
    host: 'db.primary.svc',
    chaos: { latencyMs: 800, bandwidthBytesPerSec: 65536 },
    ttlMillis: 600000,
  },
  {
    host: 'cache.svc',
    chaos: { resetPeer: true },
    ttlMillis: 300000,
  },
  {
    host: 'queue.svc',
    chaos: { timeout: true },
  },
  {
    host: 'upload.svc',
    chaos: { slicerChunkSize: 128, limitDataBytes: 1048576, slowClose: true },
  },
];

async function tcpChaosExamples() {
  log('\n→ TCP-layer chaos (Chaos tab)');
  for (const entry of TCP_CHAOS) {
    const res = await api('PUT', '/mockserver/tcpChaos', entry);
    if (!res.ok) throw new Error(`Failed to register TCP chaos for "${entry.host}": HTTP ${res.status}`);
    counts.tcpChaos++;
    log(`   ~ tcp chaos      ${entry.host}${entry.ttlMillis ? `  (ttl ${entry.ttlMillis}ms)` : ''}`);
  }
}

// gRPC health-check chaos (Chaos tab → "gRPC Health Chaos" section). Forcing a service's
// health-check serving status simulates an unhealthy/degraded dependency so client and
// orchestrator (K8s readiness/liveness) reactions can be exercised. Empty service name
// sets the default status for all services.
const GRPC_HEALTH = [
  { service: 'payments.v1.PaymentService', status: 'NOT_SERVING' },
  { service: 'inventory.v1.InventoryService', status: 'SERVICE_UNKNOWN' },
  { service: 'catalog.v1.CatalogService', status: 'SERVING' },
];

async function grpcHealthExamples() {
  log('\n→ gRPC health chaos (Chaos tab)');
  for (const entry of GRPC_HEALTH) {
    const res = await api('PUT', '/mockserver/grpc/health', entry);
    if (!res.ok) throw new Error(`Failed to set gRPC health for "${entry.service}": HTTP ${res.status}`);
    counts.grpcHealth++;
    log(`   ~ grpc health    ${entry.service}  → ${entry.status}`);
  }
}

// gRPC fault-injection chaos (Chaos tab -> "gRPC Fault Injection" section). These exercise
// gRPC-level status errors and latency keyed by gRPC service name, distinct from gRPC health
// overrides and HTTP-semantic faults. One carries an auto-revert TTL so the countdown is visible.
const GRPC_CHAOS = [
  {
    service: 'payments.v1.PaymentService',
    chaos: { errorStatusCode: 'UNAVAILABLE', errorProbability: 0.5, latencyMs: 200 },
    ttlMillis: 600000,
  },
  {
    service: 'orders.v1.OrderService',
    chaos: { errorStatusCode: 'RESOURCE_EXHAUSTED', quotaName: 'orders', quotaLimit: 100, quotaWindowMillis: 60000 },
  },
  {
    service: 'shipping.v1.ShippingService',
    chaos: { errorStatusCode: 'DEADLINE_EXCEEDED', errorProbability: 1.0 },
  },
  {
    service: 'streaming.v1.StreamService',
    chaos: {
      errorStatusCode: 'INTERNAL',
      errorMessage: 'stream aborted mid-flight',
      omitGrpcStatus: true,
      customTrailers: { 'x-debug-id': 'chaos-demo-001', 'x-retry': 'false' },
      abortAfterMessages: 5,
      seed: 7,
      succeedFirst: 2,
      failRequestCount: 20,
    },
    ttlMillis: 450000,
  },
];

async function grpcChaosExamples() {
  log('\n→ gRPC fault injection chaos (Chaos tab)');
  for (const entry of GRPC_CHAOS) {
    const res = await api('PUT', '/mockserver/grpcChaos', entry);
    if (!res.ok) throw new Error(`Failed to register gRPC chaos for "${entry.service}": HTTP ${res.status}`);
    counts.grpcChaos++;
    log(`   ~ grpc chaos     ${entry.service}${entry.ttlMillis ? `  (ttl ${entry.ttlMillis}ms)` : ''}`);
  }
}

// ---------------------------------------------------------------------------
// 3b-ii. Multi-stage chaos experiment (Chaos tab -> Experiments section)
// ---------------------------------------------------------------------------

// A multi-stage chaos experiment registered against the orchestrator endpoint
// (PUT /mockserver/chaosExperiment). Unlike the single service-chaos
// registrations above, an experiment progresses automatically through ordered
// stages on a timer. The stages are long and the experiment loops so the
// Experiments section's live status (current stage, elapsed/remaining, loop
// iteration) stays visible while exploring the dashboard. The host profiles
// target fictitious upstreams, so this does not affect the demo's own traffic.
const CHAOS_EXPERIMENT = {
  name: 'Black Friday checkout degradation',
  loop: true,
  stages: [
    {
      durationMillis: 120000,
      profiles: {
        'checkout.svc': { latency: { timeUnit: 'MILLISECONDS', value: 300 }, seed: 42 },
      },
    },
    {
      durationMillis: 120000,
      profiles: {
        'checkout.svc': { errorStatus: 503, errorProbability: 0.25, latency: { timeUnit: 'MILLISECONDS', value: 800 }, seed: 42 },
        'payments.svc': { errorStatus: 429, retryAfter: '30', errorProbability: 0.5 },
      },
    },
    {
      durationMillis: 120000,
      profiles: {
        'checkout.svc': { dropConnectionProbability: 0.4 },
        'payments.svc': { errorStatus: 503, errorProbability: 0.8 },
      },
    },
  ],
};

async function chaosExperimentExample() {
  log('\n→ Multi-stage chaos experiment (Chaos tab · Experiments)');
  const res = await api('PUT', '/mockserver/chaosExperiment', CHAOS_EXPERIMENT);
  if (!res.ok) throw new Error(`Failed to start chaos experiment "${CHAOS_EXPERIMENT.name}": HTTP ${res.status}`);
  counts.experiments++;
  log(`   ~ experiment     ${CHAOS_EXPERIMENT.name}  (${CHAOS_EXPERIMENT.stages.length} stages, looping)`);
}

// ---------------------------------------------------------------------------
// 3c. gRPC mock expectations (Mocks page -> gRPC kind)
// ---------------------------------------------------------------------------

// gRPC requests are transcoded to HTTP and matched by path /package.Service/Method.
// A server-streaming RPC uses a grpcStreamResponse action (multiple messages, each
// with an optional inter-message delay); a unary RPC is just a normal httpResponse
// carrying the grpc-status trailer header. These show up in the Mocks page gRPC list.
async function grpcMockExpectations() {
  log('\n→ gRPC mock expectations (Mocks page · gRPC kind)');

  // Server-streaming RPC: stream three greetings, the later two delayed.
  await expectation('gRPC stream  greeter.v1.Greeter/ListGreetings', {
    httpRequest: {
      method: 'POST',
      path: '/greeter.v1.Greeter/ListGreetings',
      headers: {
        'x-grpc-service': ['greeter.v1.Greeter'],
        'x-grpc-method': ['ListGreetings'],
      },
    },
    grpcStreamResponse: {
      statusName: 'OK',
      messages: [
        { json: '{"greeting":"Hello Alice"}' },
        { json: '{"greeting":"Hello Bob"}', delay: { timeUnit: 'MILLISECONDS', value: 100 } },
        { json: '{"greeting":"Hello Charlie"}', delay: { timeUnit: 'MILLISECONDS', value: 200 } },
      ],
    },
  });

  // Unary RPC: single response with the gRPC OK status trailer.
  await expectation('gRPC unary   greeter.v1.Greeter/SayHello', {
    httpRequest: {
      method: 'POST',
      path: '/greeter.v1.Greeter/SayHello',
      headers: {
        'x-grpc-service': ['greeter.v1.Greeter'],
        'x-grpc-method': ['SayHello'],
      },
    },
    httpResponse: {
      statusCode: 200,
      headers: { 'grpc-status': ['0'], 'content-type': ['application/grpc+json'] },
      body: '{"greeting":"Hello World"}',
    },
  });

  // Unary RPC that returns a gRPC error status (NOT_FOUND = 5) with a message.
  await expectation('gRPC error   pay.v1.PaymentService/GetReceipt (NOT_FOUND)', {
    httpRequest: {
      method: 'POST',
      path: '/pay.v1.PaymentService/GetReceipt',
      headers: {
        'x-grpc-service': ['pay.v1.PaymentService'],
        'x-grpc-method': ['GetReceipt'],
      },
    },
    grpcStreamResponse: {
      statusName: 'NOT_FOUND',
      statusMessage: 'no receipt for the supplied transaction id',
    },
  });
}

// ---------------------------------------------------------------------------
// 3d. DNS mock expectations (Mocks page -> DNS kind)
// ---------------------------------------------------------------------------

// DNS queries are matched by a DnsRequestDefinition (dnsName + record type + class),
// carried in the request object via the dnsName key, and answered with a dnsResponse.
async function dnsMockExpectations() {
  log('\n→ DNS mock expectations (Mocks page · DNS kind)');

  // A-record lookup.
  await expectation('DNS A      api.example.com → 2 A records', {
    httpRequest: { dnsName: 'api.example.com', dnsType: 'A', dnsClass: 'IN' },
    dnsResponse: {
      responseCode: 'NOERROR',
      answerRecords: [
        { name: 'api.example.com', type: 'A', ttl: 300, value: '93.184.216.34' },
        { name: 'api.example.com', type: 'A', ttl: 300, value: '93.184.216.35' },
      ],
    },
  });

  // AAAA-record lookup.
  await expectation('DNS AAAA   ipv6.example.com → AAAA record', {
    httpRequest: { dnsName: 'ipv6.example.com', dnsType: 'AAAA', dnsClass: 'IN' },
    dnsResponse: {
      responseCode: 'NOERROR',
      answerRecords: [
        { name: 'ipv6.example.com', type: 'AAAA', ttl: 300, value: '2606:2800:220:1:248:1893:25c8:1946' },
      ],
    },
  });

  // CNAME alias.
  await expectation('DNS CNAME  www.example.com → example.com', {
    httpRequest: { dnsName: 'www.example.com', dnsType: 'CNAME', dnsClass: 'IN' },
    dnsResponse: {
      responseCode: 'NOERROR',
      answerRecords: [
        { name: 'www.example.com', type: 'CNAME', ttl: 600, value: 'example.com' },
      ],
    },
  });

  // NXDOMAIN: name that deliberately does not resolve.
  await expectation('DNS NXDOMAIN  missing.example.com', {
    httpRequest: { dnsName: 'missing.example.com', dnsType: 'A', dnsClass: 'IN' },
    dnsResponse: { responseCode: 'NXDOMAIN' },
  });
}

// ---------------------------------------------------------------------------
// 3d-bis. WASM module + WASM-matched expectation (Library page · WASM Modules)
// ---------------------------------------------------------------------------

// Upload one of the repo's example WASM modules (the Rust `match` example) so the
// Library page's "WASM Modules" section is populated, then register an expectation
// that matches its request body via that module. WASM evaluation requires the
// server to be started with -Dmockserver.wasmEnabled=true (the demo launcher sets
// this); the upload + listing work regardless.
async function wasmModuleExample() {
  log('\n→ WASM module (Library · WASM Modules)');
  const moduleName = 'match-demo';
  // examples/wasm/rust/match.wasm lives at the repo root, two levels up from mockserver-ui/scripts.
  const wasmPath = join(SCRIPT_DIR, '..', '..', 'examples', 'wasm', 'rust', 'match.wasm');
  let bytes;
  try {
    bytes = await readFile(wasmPath);
  } catch (e) {
    log(`   ! skipped WASM module — could not read ${wasmPath} (${e.code || e.message})`);
    return;
  }
  // Raw binary upload — do NOT use the JSON api() helper (it forces a JSON content-type).
  const res = await fetch(`${BASE}/mockserver/wasm/modules?name=${encodeURIComponent(moduleName)}`, {
    method: 'PUT',
    headers: { 'content-type': 'application/wasm' },
    body: bytes,
  });
  await res.text().catch(() => undefined);
  if (!res.ok) throw new Error(`Failed to upload WASM module "${moduleName}": HTTP ${res.status}`);
  counts.wasmModules++;
  log(`   + wasm module    ${moduleName}  (${bytes.length} bytes, from examples/wasm/rust/match.wasm)`);

  // An expectation whose request body is matched by the uploaded WASM module.
  await expectation('WASM body match  POST /wasm/echo', {
    httpRequest: {
      method: 'POST',
      path: '/wasm/echo',
      body: { type: 'WASM', moduleName },
    },
    httpResponse: {
      statusCode: 200,
      headers: { 'content-type': ['application/json'] },
      body: '{"matched":"by-wasm-module","module":"match-demo"}',
    },
  });
}

// ---------------------------------------------------------------------------
// 3e. Mock drift detection (Drift tab)
// ---------------------------------------------------------------------------

// Drift records are only produced by the proxy-forward path: MockServer compares
// the real upstream response against a matching response-type stub ("baseline").
// To generate examples self-contained, we spin up a throwaway local upstream that
// returns responses deliberately diverging from the baseline stubs, register a
// high-priority forward (to the upstream) plus a low-priority baseline stub for the
// same path, then send one request per scenario so the DriftAnalyzer records the
// divergence. The upstream is torn down immediately afterwards.
//
// Each scenario: { path, baseline (status/headers/body the stub claims), real
// (status/headers/body the upstream actually returns) } — chosen to exercise the
// status / schema-added / schema-removed / type-changed / header drift types.
const DRIFT_SCENARIOS = [
  {
    path: '/drift/users',
    note: 'status drift (200 → 503)',
    baseline: { statusCode: 200, body: { id: 1, name: 'Alice', active: true } },
    real: { statusCode: 503, body: { error: 'service unavailable' } },
  },
  {
    path: '/drift/orders',
    note: 'schema fields added (currency, tax)',
    baseline: { statusCode: 200, body: { id: 7, total: 42 } },
    real: { statusCode: 200, body: { id: 7, total: 42, currency: 'USD', tax: 3 } },
  },
  {
    path: '/drift/profile',
    note: 'schema field removed (role) + type changed (age number→string)',
    baseline: { statusCode: 200, body: { id: 3, name: 'Bob', role: 'admin', age: 30 } },
    real: { statusCode: 200, body: { id: 3, name: 'Bob', age: '30' } },
  },
  {
    path: '/drift/inventory',
    note: 'header drift (x-api-version v1 → v2)',
    baseline: { statusCode: 200, headers: { 'x-api-version': ['v1'] }, body: { sku: 'A-1', qty: 5 } },
    real: { statusCode: 200, headers: { 'x-api-version': 'v2' }, body: { sku: 'A-1', qty: 5 } },
  },
];

async function driftExamples() {
  log('\n→ Mock drift detection (Drift tab)');

  // Throwaway upstream returning the deliberately-divergent "real" responses.
  const byPath = new Map(DRIFT_SCENARIOS.map((s) => [s.path, s.real]));
  const upstream = http.createServer((req, res) => {
    const real = byPath.get((req.url || '').split('?')[0]);
    if (!real) {
      res.writeHead(404).end();
      return;
    }
    const headers = { 'content-type': 'application/json', ...(real.headers || {}) };
    res.writeHead(real.statusCode, headers);
    res.end(JSON.stringify(real.body));
  });

  await new Promise((resolve) => upstream.listen(0, '127.0.0.1', resolve));
  const upstreamPort = upstream.address().port;

  try {
    for (const s of DRIFT_SCENARIOS) {
      // High-priority forward to the throwaway upstream (this is what actually serves).
      const fwd = await api('PUT', '/mockserver/expectation', {
        priority: 10,
        httpRequest: { method: 'GET', path: s.path },
        httpForward: { host: '127.0.0.1', port: upstreamPort, scheme: 'HTTP' },
      });
      if (!fwd.ok) throw new Error(`drift forward setup failed for ${s.path}: HTTP ${fwd.status}`);

      // Low-priority baseline stub — never serves (forward wins) but is the drift
      // comparison baseline that the real upstream response is diffed against.
      const base = await api('PUT', '/mockserver/expectation', {
        priority: 0,
        httpRequest: { method: 'GET', path: s.path },
        httpResponse: {
          statusCode: s.baseline.statusCode,
          headers: s.baseline.headers,
          body: { type: 'JSON', json: s.baseline.body },
        },
      });
      if (!base.ok) throw new Error(`drift baseline setup failed for ${s.path}: HTTP ${base.status}`);

      // Send the request → forwarded to the upstream → DriftAnalyzer records divergence.
      await api('GET', s.path);
      counts.drift++;
      log(`   ~ drift          ${s.path}  (${s.note})`);
    }
    // Drift analysis runs asynchronously on a scheduler thread after each forward
    // completes, so give those tasks a moment to record before we remove the baseline
    // stubs they compare against (clearing too early would race the analysis and could
    // drop records). A short settle delay makes the seeding deterministic.
    await new Promise((resolve) => setTimeout(resolve, 750));

    // The /drift/* forward+baseline expectations are throwaway scaffolding (the forward
    // target is about to close); the drift records persist independently in the DriftStore,
    // so clear the scaffolding to keep the Library/expectations view clean.
    await api('PUT', '/mockserver/clear?type=expectations', { path: '/drift/.*' });
  } finally {
    await new Promise((resolve) => upstream.close(resolve));
  }
}

// ---------------------------------------------------------------------------
// 4. Recorded traffic (Traffic view, token/cost, unmatched diagnostics)
// ---------------------------------------------------------------------------

async function plainHttpTraffic() {
  log('\n→ Plain HTTP traffic');
  await traffic('list users', 'GET', '/api/users');
  await traffic('get user', 'GET', '/api/users/42?expand=profile&expand=orders');
  await traffic('create user', 'POST', '/api/users', { body: { name: 'Dave' } });
  await traffic('update user', 'PUT', '/api/users/42', { body: { name: 'Carol II' } });
  await traffic('xml report', 'GET', '/api/report.xml');
  await traffic('flaky 500', 'GET', '/api/flaky');
  // Unmatched requests — exercise the "no expectation matched" diagnostics.
  await traffic('unmatched', 'GET', '/api/does-not-exist');
  await traffic('unmatched', 'POST', '/api/unknown', { body: { foo: 'bar' } });
}

// A final burst of cleanly-matched requests, run LAST so these matched entries are
// the freshest in the dashboard's reverse-chronological event log — making the
// "request matched expectation → response returned" lane unmistakable at a glance
// (rather than scrolling past the intentionally-unmatched diagnostics traffic).
async function matchedShowcaseTraffic() {
  log('\n→ Matched-expectation showcase (freshest entries in the dashboard log)');
  await traffic('matched · list users', 'GET', '/api/users');
  await traffic('matched · get user (path+query)', 'GET', '/api/users/42?expand=profile&expand=orders');
  await traffic('matched · priority override', 'GET', '/api/priority');
  await traffic('matched · create order (before/after side-effects)', 'POST', '/api/orders', { body: { sku: 'A-1', qty: 2 } });
}

async function llmTraffic() {
  log('\n→ LLM traffic (one classified lane per provider, with token usage)');
  for (const [provider, p] of Object.entries(PROVIDERS)) {
    await traffic(`${provider} completion`, 'POST', p.path, {
      body: p.request,
      headers: { 'content-type': 'application/json' },
    });
  }
  await traffic('ANTHROPIC tool-call', 'POST', '/v1/messages?demo=tools', {
    body: PROVIDERS.ANTHROPIC.request,
  });
  await traffic('OPENAI streaming', 'POST', '/v1/chat/completions?stream=true', {
    body: PROVIDERS.OPENAI.request,
  });
  await traffic('OPENAI chaos 429', 'POST', '/v1/chat/completions?chaos=429', {
    body: PROVIDERS.OPENAI.request,
  });
}

async function agentLoops() {
  log('\n→ Agent loops (Sessions + call graph, grouped by x-agent-id + x-session-id)');

  // A full two-turn weather agent loop per agent: user → assistant tool_use →
  // user tool_result → assistant final answer. The request bodies carry the
  // growing history the call graph is reconstructed from. Each agent asks about
  // a DIFFERENT city so that, once the call graph is scoped to a single session
  // server-side, each lane's graph shows a distinct USER question instead of the
  // same merged Paris graph. The turn expectations match on turnIndex / path /
  // containsToolResultFor (NOT the city), so varying the city text is safe.
  const agentCities = {
    'agent-001': 'Paris',
    'agent-002': 'Tokyo',
    'agent-003': 'London',
    'agent-004': 'Berlin',
  };

  for (const [agent, city] of Object.entries(agentCities)) {
    const turn1Body = {
      model: 'claude-sonnet-4-20250514',
      max_tokens: 1024,
      messages: [{ role: 'user', content: `What is the weather in ${city}?` }],
    };
    const turn2Body = {
      model: 'claude-sonnet-4-20250514',
      max_tokens: 1024,
      messages: [
        { role: 'user', content: `What is the weather in ${city}?` },
        {
          role: 'assistant',
          content: [
            { type: 'text', text: 'Let me search for the weather.' },
            { type: 'tool_use', id: 'toolu_w0', name: 'search_weather', input: { city } },
          ],
        },
        { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'toolu_w0', content: `18C, sunny in ${city}` }] },
      ],
    };

    await traffic(`${agent} turn 1`, 'POST', '/v1/messages', {
      body: turn1Body,
      headers: { 'x-agent-id': agent },
    });
    await traffic(`${agent} turn 2`, 'POST', '/v1/messages', {
      body: turn2Body,
      headers: { 'x-agent-id': agent },
    });
  }

  // Second isolated conversation: an OpenAI "support bot" grouped by the
  // x-session-id header (SUPPORT_SCENARIO above). Same two-turn shape but a
  // clearly different question / tool / answer so the lanes read distinctly.
  // Driven to /v1/chat/completions to match the conversation expectation path.
  const supportTurn1Body = {
    model: 'gpt-4o',
    messages: [{ role: 'user', content: 'Where is my order A-4291?' }],
  };
  const supportTurn2Body = {
    model: 'gpt-4o',
    messages: [
      { role: 'user', content: 'Where is my order A-4291?' },
      {
        role: 'assistant',
        content: 'Let me look up your order status.',
        tool_calls: [
          {
            id: 'call_s0',
            type: 'function',
            function: { name: 'lookup_order', arguments: '{"orderId":"A-4291"}' },
          },
        ],
      },
      { role: 'tool', tool_call_id: 'call_s0', content: 'shipped 2024-01-01, ETA Thursday' },
    ],
  };

  for (const session of ['sess-alpha', 'sess-bravo', 'sess-charlie']) {
    await traffic(`${session} turn 1`, 'POST', '/v1/chat/completions', {
      body: supportTurn1Body,
      headers: { 'x-session-id': session },
    });
    await traffic(`${session} turn 2`, 'POST', '/v1/chat/completions', {
      body: supportTurn2Body,
      headers: { 'x-session-id': session },
    });
  }
}

// ---------------------------------------------------------------------------
// 5. Side-effect expectations (before / after actions)
// ---------------------------------------------------------------------------

// An expectation can fire extra HTTP side-effects around its primary response:
//   - beforeActions run BEFORE the response is written. A blocking before-action
//     (blocking:true) makes the primary response wait for it, with a timeout and a
//     failurePolicy (FAIL_FAST aborts the response on failure; BEST_EFFORT continues).
//   - afterActions are always fire-and-forget AFTER the response is written.
// Each action's httpRequest is dispatched by MockServer's HTTP client, so it carries a
// socketAddress telling MockServer where to send it — here we point them back at this
// same MockServer (self) and add tiny stubs for the audit / webhook targets so the
// side-effects resolve cleanly offline. These load into the Composer's side-effects panel.
async function sideEffectExpectations() {
  log('\n→ Side-effect expectations (before / after actions)');

  // Targets for the side-effect calls (so they resolve to 200 rather than 404).
  await expectation('audit sink   POST /audit/log', {
    httpRequest: { method: 'POST', path: '/audit/log' },
    httpResponse: { statusCode: 200, body: { json: { logged: true } } },
  });
  await expectation('webhook sink POST /webhooks/order-created', {
    httpRequest: { method: 'POST', path: '/webhooks/order-created' },
    httpResponse: { statusCode: 202, body: { json: { accepted: true } } },
  });

  const selfSocket = { host: SELF_HOST, port: SELF_PORT, scheme: SELF_SCHEME };

  // Primary expectation with a blocking before-action (audit) and a fire-and-forget
  // after-action (webhook). The response waits up to 2s for the audit call; if it fails
  // the response is still sent (BEST_EFFORT).
  await expectation('POST /api/orders (before: audit · after: webhook)', {
    httpRequest: { method: 'POST', path: '/api/orders' },
    httpResponse: {
      statusCode: 201,
      headers: { location: ['/api/orders/5001'] },
      body: { json: { id: 5001, status: 'confirmed' } },
    },
    beforeActions: [
      {
        httpRequest: {
          method: 'POST',
          path: '/audit/log',
          body: { json: { event: 'order.create.attempt', source: 'demo' } },
          socketAddress: selfSocket,
        },
        blocking: true,
        timeout: { timeUnit: 'MILLISECONDS', value: 2000 },
        failurePolicy: 'BEST_EFFORT',
      },
    ],
    afterActions: [
      {
        httpRequest: {
          method: 'POST',
          path: '/webhooks/order-created',
          body: { json: { event: 'order.created', orderId: 5001 } },
          socketAddress: selfSocket,
        },
      },
    ],
  });

  // A second expectation showing a non-blocking before-action plus a delayed after-action.
  await expectation('DELETE /api/orders/5001 (before: non-blocking audit · after: delayed webhook)', {
    httpRequest: { method: 'DELETE', path: '/api/orders/5001' },
    httpResponse: { statusCode: 204 },
    beforeActions: [
      {
        httpRequest: {
          method: 'POST',
          path: '/audit/log',
          body: { json: { event: 'order.delete', orderId: 5001 } },
          socketAddress: selfSocket,
        },
        blocking: false,
      },
    ],
    afterActions: [
      {
        httpRequest: {
          method: 'POST',
          path: '/webhooks/order-created',
          body: { json: { event: 'order.deleted', orderId: 5001 } },
          socketAddress: selfSocket,
        },
        delay: { timeUnit: 'MILLISECONDS', value: 250 },
      },
    ],
  });

  counts.sideEffects += 2;
}

// ---------------------------------------------------------------------------
// 6. Scenario state machines (Sessions · Scenarios panel)
// ---------------------------------------------------------------------------

// Seed a handful of named scenario state machines via PUT /mockserver/scenario/{name}
// so the Scenarios panel's "Existing scenarios" list (GET /mockserver/scenario) is
// populated. One sets a timed auto-transition so the countdown chip is visible, plus a
// companion checkout stub whose scenario state can be advanced from the Scenarios panel.
const SCENARIO_STATES = [
  { name: 'checkout-flow', state: 'cart' },
  { name: 'payment-gateway', state: 'healthy' },
  { name: 'feature-rollout', state: 'disabled' },
  { name: 'order-fulfilment', state: 'received' },
];

async function scenarioStateExamples() {
  log('\n→ Scenario state machines (Sessions · Scenarios panel)');

  for (const s of SCENARIO_STATES) {
    const res = await api('PUT', `/mockserver/scenario/${encodeURIComponent(s.name)}`, { state: s.state });
    if (!res.ok) throw new Error(`Failed to set scenario "${s.name}": HTTP ${res.status}`);
    counts.scenarios++;
    log(`   ~ scenario       ${s.name}  → ${s.state}`);
  }

  // A scenario with a scheduled auto-transition so the live countdown is visible.
  const timed = await api('PUT', '/mockserver/scenario/nightly-batch', {
    state: 'running',
    transitionAfterMs: 600000,
    nextState: 'idle',
  });
  if (!timed.ok) throw new Error(`Failed to set timed scenario "nightly-batch": HTTP ${timed.status}`);
  counts.scenarios++;
  log('   ~ scenario       nightly-batch  → running  (auto → idle in 600000ms)');

  // A companion stub for the checkout flow. The scenario state is driven manually from
  // the Scenarios panel (Set State / Trigger) — advance checkout-flow to "paid" there to
  // see the list update.
  await expectation('POST /api/checkout/pay (checkout-flow stub)', {
    httpRequest: { method: 'POST', path: '/api/checkout/pay' },
    httpResponse: { statusCode: 200, body: { json: { status: 'paid' } } },
  });
}

// ---------------------------------------------------------------------------
// 6b. Cassettes (Library · Cassettes tab)
// ---------------------------------------------------------------------------

// Register the example cassette fixtures (scripts/demo-cassettes/*.json) in MockServer's
// server-side cassette registry (PUT /mockserver/cassettes) so the Library · Cassettes tab lists
// them — the dashboard merges the server registry with its per-browser localStorage list. Each
// cassette's expectationCount is read from the file. The actual expectations are not loaded here;
// use the Cassettes · Load tab (or load_expectations_from_file) to replay them.
const DEMO_CASSETTES = ['rest-api-crud.json', 'llm-anthropic-weather.json'];

async function cassetteExamples() {
  log('\n→ Cassettes (Library · Cassettes tab)');
  const cassetteDir = join(SCRIPT_DIR, 'demo-cassettes');
  for (const filename of DEMO_CASSETTES) {
    const path = join(cassetteDir, filename);
    let expectationCount = -1;
    try {
      const parsed = JSON.parse(await readFile(path, 'utf8'));
      expectationCount = Array.isArray(parsed) ? parsed.length : -1;
    } catch (e) {
      log(`   ! skipped cassette ${filename} — could not read (${e.code || e.message})`);
      continue;
    }
    const res = await api('PUT', '/mockserver/cassettes', { path, filename, expectationCount, origin: 'loaded' });
    if (!res.ok) throw new Error(`Failed to register cassette "${filename}": HTTP ${res.status}`);
    counts.cassettes++;
    log(`   + cassette       ${filename}  (${expectationCount} expectations)`);
  }
}

// ---------------------------------------------------------------------------
// 7. gRPC descriptor set (Library · gRPC Descriptors tab)
// ---------------------------------------------------------------------------

// Upload a compiled protobuf FileDescriptorSet (the repo's greeting.dsc test fixture) so
// the Library page's "gRPC Descriptors" tab lists the service and its methods (unary,
// server-/client-streaming, bidi). The endpoint takes the raw descriptor-set bytes.
async function grpcDescriptorExample() {
  log('\n→ gRPC descriptor set (Library · gRPC Descriptors tab)');
  // greeting.dsc lives in mockserver-core test resources, two levels up from mockserver-ui/scripts.
  const dscPath = join(SCRIPT_DIR, '..', '..', 'mockserver', 'mockserver-core', 'src', 'test', 'resources', 'grpc', 'greeting.dsc');
  let bytes;
  try {
    bytes = await readFile(dscPath);
  } catch (e) {
    log(`   ! skipped gRPC descriptors — could not read ${dscPath} (${e.code || e.message})`);
    return;
  }
  // Raw binary upload — do NOT use the JSON api() helper.
  const res = await fetch(`${BASE}/mockserver/grpc/descriptors`, {
    method: 'PUT',
    headers: { 'content-type': 'application/octet-stream' },
    body: bytes,
  });
  await res.text().catch(() => undefined);
  if (!res.ok) throw new Error(`Failed to upload gRPC descriptor set: HTTP ${res.status}`);
  counts.grpcServices++;
  log(`   + grpc descriptors  com.example.grpc.GreetingService  (${bytes.length} bytes, from greeting.dsc)`);
}

// ---------------------------------------------------------------------------
// AsyncAPI broker-mock example (Tools · "AsyncAPI Broker Mock" panel)
// ---------------------------------------------------------------------------
// Loads one representative AsyncAPI 3.0 spec so the panel's header chips
// (connected + spec title/version) and Channels table are populated for
// screenshots. The channels are deliberately varied to exercise every column:
// with/without a payload schema (✓ vs disabled icon) and 0 / 1 / many examples,
// plus a multi-message channel and an MQTT-style wildcard topic.
//
// Two modes, controlled by the DEMO_MQTT_BROKER_URL env var:
//
//   • broker-less (default) — no brokerConfig is sent, so MockServer does NOT try
//     to reach Kafka/MQTT. publishers/subscribers stay 0 and the "Recorded
//     Messages" table shows its empty state. Keeps the demo self-contained
//     (java/curl/node only — no Docker broker required).
//
//   • live broker (DEMO_MQTT_BROKER_URL set, e.g. tcp://localhost:1883) — the
//     spec is loaded with a brokerConfig that points at a real MQTT broker and
//     enables BOTH publish-on-a-schedule and consume. MockServer publishes each
//     channel's example payload to the broker every few seconds and subscribes to
//     the same topics, recording its own messages → the "Recorded Messages" table
//     fills in (a live, ticking feed). `launch-with-demo-data.sh --with-broker`
//     starts a Mosquitto container and sets this var automatically.
//
// All channel topics are MQTT-publishable (no '+'/'#' wildcards) so the live-broker
// mode can publish to every channel. See docs/code/async-messaging.md.

const ASYNCAPI_SPEC = {
  asyncapi: '3.0.0',
  info: { title: 'Order Fulfilment Events', version: '2.4.0' },
  channels: {
    // schema ✓, 1 example
    'orders.placed': {
      address: 'orders.placed',
      messages: {
        OrderPlaced: {
          name: 'OrderPlaced',
          payload: {
            type: 'object',
            properties: {
              orderId: { type: 'string', format: 'uuid' },
              customer: { type: 'string', format: 'email' },
              total: { type: 'number', minimum: 0 },
              currency: { type: 'string', enum: ['GBP', 'USD', 'EUR'] },
              items: { type: 'integer', minimum: 1 },
            },
            required: ['orderId', 'total'],
          },
          examples: [
            { payload: { orderId: 'a1b2c3d4-0000-4000-8000-000000000001', customer: 'alice@example.com', total: 129.99, currency: 'GBP', items: 3 } },
          ],
        },
      },
    },
    // schema ✓, 2 examples
    'payments.processed': {
      address: 'payments.processed',
      messages: {
        PaymentProcessed: {
          payload: {
            type: 'object',
            properties: {
              paymentId: { type: 'string' },
              orderId: { type: 'string' },
              amount: { type: 'number', minimum: 0 },
              status: { type: 'string', enum: ['captured', 'declined', 'refunded'] },
            },
            required: ['paymentId', 'status'],
          },
          examples: [
            { payload: { paymentId: 'pay_1029', orderId: 'a1b2c3d4-0000-4000-8000-000000000001', amount: 129.99, status: 'captured' } },
            { payload: { paymentId: 'pay_1030', orderId: 'b2c3d4e5-0000-4000-8000-000000000002', amount: 49.0, status: 'declined' } },
          ],
        },
      },
    },
    // schema ✓, 0 examples (exercises the "schema but no example" row)
    'inventory.low-stock': {
      messages: {
        LowStock: {
          payload: {
            type: 'object',
            properties: {
              sku: { type: 'string' },
              warehouse: { type: 'string' },
              remaining: { type: 'integer', minimum: 0 },
            },
            required: ['sku'],
          },
        },
      },
    },
    // multi-message channel — schema ✓, exampleCount reflects the first message
    'notifications/email': {
      messages: {
        UserRegistered: {
          payload: { type: 'object', properties: { email: { type: 'string', format: 'email' }, name: { type: 'string' } } },
          examples: [{ payload: { email: 'bob@example.com', name: 'Bob' } }],
        },
        PasswordReset: {
          payload: { type: 'object', properties: { email: { type: 'string', format: 'email' }, token: { type: 'string' } } },
          examples: [{ payload: { email: 'bob@example.com', token: 'reset-2f9c1a' } }],
        },
      },
    },
    // MQTT-publishable telemetry topic, no schema (exercises the disabled "no schema" icon)
    'telemetry/device/status': {
      messages: { DeviceStatus: {} },
    },
  },
};

async function asyncApiExamples() {
  const brokerUrl = process.env.DEMO_MQTT_BROKER_URL;
  log('\n→ AsyncAPI broker mock (Tools · AsyncAPI panel)');

  // In live-broker mode, wrap the spec with a brokerConfig that publishes each
  // channel's example on a schedule and consumes the same topics, so the Recorded
  // Messages table populates from MockServer's own round-tripped messages.
  const body = brokerUrl
    ? { spec: ASYNCAPI_SPEC, brokerConfig: { mqttBrokerUrl: brokerUrl, consume: true, publishOnLoad: true, publishIntervalMillis: 3000 } }
    : ASYNCAPI_SPEC;

  const res = await api('PUT', '/mockserver/asyncapi', body);
  if (res.status === 501) {
    log('   ! skipped AsyncAPI — mockserver-async module not on this server classpath (501)');
    return;
  }
  if (!res.ok) throw new Error(`Failed to load AsyncAPI spec: HTTP ${res.status}`);
  const channelNames = Object.keys(ASYNCAPI_SPEC.channels);
  counts.asyncChannels += channelNames.length;
  log(`   + asyncapi spec  "${ASYNCAPI_SPEC.info.title}" v${ASYNCAPI_SPEC.info.version}  (${channelNames.length} channels)`);
  for (const name of channelNames) log(`       channel  ${name}`);
  if (brokerUrl) {
    log(`   ↻ live broker  ${brokerUrl}  — publishing every 3s + consuming; Recorded Messages will tick up`);
  } else {
    log('   (broker-less load — Recorded Messages stay empty; run launch-with-demo-data.sh --with-broker for a live feed)');
  }
}

// ---------------------------------------------------------------------------
// MCP server health (AI · MCP Health panel)
// ---------------------------------------------------------------------------
// Populates the dashboard's "MCP Health" panel, which aggregates captured MCP
// (JSON-RPC) traffic PER SERVER (grouped by the request Host header) and shows
// call count, error rate, and latency (median / p95 / max) so a user can see
// which MCP server a coding-assistant CLI talks to is the bottleneck.
//
// To make that realistic each call is SELF-FORWARDED (httpOverrideForwardedRequest
// back to a mock on THIS server) so the recorded exchange carries real round-trip
// timing (including the target mock's `delay`) — mocked-only responses have no
// timing. The requests are sent with raw node:http so a distinct Host header can be
// set per server (the WHATWG fetch used elsewhere forbids overriding Host), which is
// what the panel groups by. Four servers give a worst-first spread: a slow one, an
// error-prone one, a moderate one with a transport error, and a healthy one.
const MCP_HEALTH_SERVERS = [
  {
    host: 'chrome-devtools-mcp',
    slug: 'cdp',
    calls: [
      { method: 'initialize', delayMs: 60 },
      { method: 'tools/list', delayMs: 80 },
      { method: 'tools/call', tool: 'take_snapshot', delayMs: 110 },
      { method: 'tools/call', tool: 'list_console_messages', delayMs: 90 },
      // the real-world villain: one slow browser navigation drags p95 over the 5s slow threshold
      { method: 'tools/call', tool: 'navigate_page', delayMs: 5500 },
    ],
  },
  {
    host: 'filesystem-mcp',
    slug: 'fs',
    calls: [
      { method: 'initialize', delayMs: 30 },
      { method: 'tools/list', delayMs: 35 },
      { method: 'tools/call', tool: 'read_file', delayMs: 45 },
      { method: 'tools/call', tool: 'list_directory', delayMs: 40 },
      { method: 'resources/read', delayMs: 50 },
    ],
  },
  {
    host: 'github-mcp',
    slug: 'gh',
    calls: [
      { method: 'initialize', delayMs: 90 },
      { method: 'tools/list', delayMs: 120 },
      { method: 'tools/call', tool: 'search_issues', delayMs: 140 },
      // a couple of JSON-RPC error responses (HTTP 200 with an `error` member) -> high error rate
      { method: 'tools/call', tool: 'create_pull_request', delayMs: 130, error: 'jsonrpc' },
      { method: 'tools/call', tool: 'merge_pull_request', delayMs: 110, error: 'jsonrpc' },
    ],
  },
  {
    host: 'devbot-mcp',
    slug: 'devbot',
    calls: [
      { method: 'initialize', delayMs: 600 },
      { method: 'tools/list', delayMs: 700 },
      { method: 'tools/call', tool: 'run_query', delayMs: 850 },
      { method: 'tools/call', tool: 'fetch_context', delayMs: 750 },
      // one transport-level failure (HTTP 503) -> counted as an error via non-2xx status
      { method: 'tools/call', tool: 'deploy_preview', delayMs: 300, error: 'http503' },
    ],
  },
];

function mcpJsonRpcRequest(id, call) {
  return {
    jsonrpc: '2.0',
    id,
    method: call.method,
    params: call.tool ? { name: call.tool, arguments: {} } : {},
  };
}

function mcpJsonRpcResponse(id, call) {
  if (call.error === 'jsonrpc' || call.error === 'http503') {
    return { jsonrpc: '2.0', id, error: { code: -32603, message: `MCP call failed: ${call.tool || call.method}` } };
  }
  return { jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: `ok: ${call.tool || call.method}` }] } };
}

// Send a single MCP JSON-RPC POST with an explicit Host header via raw node:http
// (fetch forbids overriding Host). HTTP only — the demo dashboard server is plain HTTP.
function mcpForwardedCall(host, path, jsonRpcBody) {
  return new Promise((resolve) => {
    const body = JSON.stringify(jsonRpcBody);
    const req = http.request({
      hostname: SELF_HOST,
      port: SELF_PORT,
      method: 'POST',
      path,
      headers: {
        'content-type': 'application/json',
        'content-length': Buffer.byteLength(body),
        Host: host,
      },
    }, (res) => {
      res.on('data', () => {});
      res.on('end', () => resolve(res.statusCode));
    });
    req.on('error', () => resolve(0));
    req.write(body);
    req.end();
  });
}

async function mcpServerHealthExpectations() {
  log('\n→ MCP server health expectations (self-forwarded MCP servers for the MCP Health panel)');
  if (SELF_SCHEME !== 'HTTP') {
    log('   (skipped — MCP Health demo lane needs a plain-HTTP dashboard server)');
    return;
  }
  let id = 0;
  for (const server of MCP_HEALTH_SERVERS) {
    for (let i = 0; i < server.calls.length; i++) {
      const call = server.calls[i];
      id++;
      const fwdPath = `/mcp-fwd/${server.slug}/${i}`;
      const upPath = `/mcp-up/${server.slug}/${i}`;
      // inbound request -> forwarded back to the self-hosted target mock (gives real timing)
      await expectation(`MCP forward ${server.host} ${call.method}`, {
        httpRequest: { method: 'POST', path: fwdPath },
        httpOverrideForwardedRequest: {
          httpRequest: {
            path: upPath,
            socketAddress: { host: SELF_HOST, port: SELF_PORT, scheme: SELF_SCHEME },
          },
        },
      });
      // the target MCP "server" mock: a JSON-RPC response with the call's latency / error
      await expectation(`MCP upstream ${server.host} ${call.method}`, {
        httpRequest: { method: 'POST', path: upPath },
        httpResponse: {
          statusCode: call.error === 'http503' ? 503 : 200,
          headers: { 'content-type': ['application/json'] },
          body: JSON.stringify(mcpJsonRpcResponse(id, call)),
          delay: { timeUnit: 'MILLISECONDS', value: call.delayMs },
        },
      });
    }
  }
}

async function mcpServerHealthTraffic() {
  log('\n→ MCP server health traffic (per-server JSON-RPC calls -> MCP Health panel)');
  if (SELF_SCHEME !== 'HTTP') return;
  let id = 0;
  for (const server of MCP_HEALTH_SERVERS) {
    for (let i = 0; i < server.calls.length; i++) {
      const call = server.calls[i];
      id++;
      const fwdPath = `/mcp-fwd/${server.slug}/${i}`;
      const status = await mcpForwardedCall(server.host, fwdPath, mcpJsonRpcRequest(id, call));
      counts.requests++;
      counts.mcpServerCalls++;
      log(`   > POST ${fwdPath}  (Host: ${server.host}, ${call.method})  ->  ${status}`);
    }
  }
}

// ---------------------------------------------------------------------------
// MCP tool calls (Tools · MCP panel + Metrics · "MCP tool calls" chart)
// ---------------------------------------------------------------------------
// Drives a handful of READ-ONLY MCP tools over POST /mockserver/mcp so the
// mock_server_mcp_tool_calls_total{tool} counter is populated and the Metrics
// view's "MCP tool calls" line chart shows one cumulative series per tool. Each
// tool is called a different number of times so the chart has distinct line
// heights. Only read-only tools are used (never reset / clear / stop_server) so
// the rest of the demo data is left intact; this runs last.

const MCP_PATH = '/mockserver/mcp';

// Perform the MCP initialize + notifications/initialized handshake (mirrors
// src/lib/mcpClient.ts) and return the Mcp-Session-Id, or null if unavailable.
async function mcpInitSession() {
  const initRes = await fetch(`${BASE}${MCP_PATH}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'mockserver-demo', version: '1' } } }),
  });
  const sid = initRes.headers.get('Mcp-Session-Id');
  await initRes.text().catch(() => undefined);
  if (!initRes.ok || !sid) return null;
  const note = await fetch(`${BASE}${MCP_PATH}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'Mcp-Session-Id': sid },
    body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
  });
  await note.text().catch(() => undefined);
  return sid;
}

async function mcpCall(sid, name, args = {}) {
  const res = await fetch(`${BASE}${MCP_PATH}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'Mcp-Session-Id': sid },
    body: JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method: 'tools/call', params: { name, arguments: args } }),
  });
  const ok = res.ok;
  await res.text().catch(() => undefined);
  return ok;
}

// Read-only tools + how many times to call each (varied line heights).
const MCP_DEMO_CALLS = [
  ['list_mock_tools', 5],
  ['get_status', 4],
  ['retrieve_recorded_requests', 3],
  ['retrieve_logs', 3],
  ['retrieve_request_responses', 2],
  ['explain_unmatched_requests', 2],
];

async function mcpToolCallExamples() {
  log('\n→ MCP tool calls (Tools · MCP panel + Metrics · MCP tool calls chart)');
  let sid;
  try {
    sid = await mcpInitSession();
  } catch (e) {
    log(`   ! skipped MCP — could not initialise session (${e.message})`);
    return;
  }
  if (!sid) {
    log('   ! skipped MCP — server returned no Mcp-Session-Id (MCP endpoint unavailable?)');
    return;
  }
  for (const [name, times] of MCP_DEMO_CALLS) {
    let okCount = 0;
    for (let i = 0; i < times; i++) {
      if (await mcpCall(sid, name)) okCount++;
    }
    counts.mcpCalls += okCount;
    log(`   + mcp tool  ${name.padEnd(30)} ×${okCount}`);
  }
}

// ---------------------------------------------------------------------------
// 8. LLM cost-optimisation showcase (Optimise tab — all six signals fire)
// ---------------------------------------------------------------------------

// A single coherent agent "run" — a customer-support triage agent making seven
// OpenAI Chat Completions calls — crafted so the optimisation report
// (GET /mockserver/llm/optimisationReport) fires ALL SIX deterministic signals.
//
// Why OpenAI /v1/chat/completions: its codec is the one that decodes a `system`
// role message into a SYSTEM ParsedMessage (so the system-prompt signals fire),
// parses assistant `tool_calls` (deterministic-tool detection) and `tool` role
// messages with `tool_call_id` (oversized-tool-result detection). The report is
// built from RECORDED request/response pairs: signal inputs come from each
// request body's decoded conversation, and the token counts come from each
// mocked response's `usage` block (so costIsEstimated=false — real numbers).
//
// How each signal is provoked (see OptimisationSignals.java thresholds):
//   REPEATED_SYSTEM_PROMPT (HIGH)      every call resends the same large system
//                                       prompt (≥1000 tokens, repeated ≥3 times).
//   LARGE_STATIC_CONTEXT_RESENT (HIGH) that same system prompt is ≥2000 tokens
//                                       (systemPromptTokens = ceil(chars/4), so the
//                                       block below is sized to ~9k chars ⇒ ~2.3k tokens).
//   DETERMINISTIC_TOOL_CALL (MEDIUM)   the assistant calls lookup_ticket with the
//                                       SAME arguments, and that assistant turn is
//                                       carried in ≥2 calls' message history.
//   OVERSIZED_TOOL_RESULT (MEDIUM)     a tool result message ≥1000 tokens (≥4k chars).
//   OUTPUT_TOKEN_BLOAT (LOW)           one call's response usage.completion_tokens is
//                                       ≥1500 while the others are small.
//   DUPLICATE_CONSECUTIVE_CALL (MEDIUM) two CONSECUTIVE calls with identical request
//                                       shape (path/model/messageCount/systemPrompt
//                                       fingerprint/inputTokens) — a retry.
//
// The calls are keyed by an `opt` query parameter and given high priority so each
// returns its own exact `usage`, winning over the generic OpenAI single-shot mock.

const OPTIMISE_MODEL = 'gpt-4o-mini';
const OPTIMISE_PATH = '/v1/chat/completions';

// A large, static instruction + context block (~9k chars ⇒ ~2.3k tokens) reused
// verbatim on every call so its fingerprint is identical run-wide. Padded with a
// realistic, repetitive policy/runbook body — exactly the kind of static context
// that should be cached or retrieved rather than resent every turn.
const OPTIMISE_SYSTEM_PROMPT = (() => {
  const header =
    'You are ACME Corp\'s customer-support triage agent. Follow the support runbook ' +
    'precisely and never invent policy. Classify each ticket, decide the next action, ' +
    'and call the provided tools when a lookup is required.\n\n';
  // A long, static policy/runbook block. Repeated bullet sections pad it out to a
  // realistically large (and resent-every-turn) static context.
  const policyUnit =
    'POLICY: Refunds are permitted within 30 days of delivery for unused items in original ' +
    'packaging. Shipping fees are non-refundable except where the item arrived damaged or the ' +
    'wrong item was sent. Always verify the order id against the ticket before promising a refund. ' +
    'Escalate to a human agent for chargeback threats, legal language, or order values over 500 GBP. ' +
    'For delivery delays, check the carrier status before responding and set expectations conservatively. ' +
    'Never share another customer\'s personal data. Log every action to the audit trail. ';
  let body = header;
  // 16 sections ⇒ ~9k chars ⇒ ~2.3k tokens (comfortably over the 2,000-token
  // LARGE_STATIC_CONTEXT_RESENT threshold; systemPromptTokens = ceil(chars/4)).
  for (let i = 1; i <= 16; i++) {
    body += `RUNBOOK SECTION ${i}. ${policyUnit}\n`;
  }
  return body;
})();

// The single deterministic tool call the agent keeps making (identical args).
const LOOKUP_TICKET = {
  id: 'call_lookup_ticket_0',
  type: 'function',
  function: { name: 'lookup_ticket', arguments: '{"ticketId":"TICKET-1001"}' },
};

// An oversized (~4.5k char ⇒ ~1.1k token) tool result — a giant raw ticket dump
// that gets re-sent as input on every subsequent turn.
const OVERSIZED_TOOL_RESULT = (() => {
  const line =
    'event: status_change; from: open; to: pending; actor: system; note: customer contacted carrier; ';
  let dump = 'TICKET-1001 full history dump (verbose): ';
  for (let i = 0; i < 45; i++) {
    dump += `[#${i}] ${line}`;
  }
  return dump;
})();

// Build the message history for a given turn. Each call resends the full system
// prompt + the growing conversation (this is what real agent frameworks do).
function optimiseMessages(turn) {
  const messages = [
    { role: 'system', content: OPTIMISE_SYSTEM_PROMPT },
    { role: 'user', content: 'Customer asks: where is my refund for order TICKET-1001?' },
  ];
  if (turn >= 2) {
    // assistant decides to look the ticket up (the deterministic tool call)
    messages.push({ role: 'assistant', content: null, tool_calls: [LOOKUP_TICKET] });
  }
  if (turn >= 3) {
    // the oversized tool result is fed back, then re-sent every later turn
    messages.push({ role: 'tool', tool_call_id: LOOKUP_TICKET.id, content: OVERSIZED_TOOL_RESULT });
    messages.push({ role: 'user', content: 'Please summarise the refund status for the customer.' });
  }
  return messages;
}

// The crafted run: seven calls. usage.prompt_tokens drives inputTokens (must be
// identical for the two duplicate calls); usage.completion_tokens drives
// outputTokens (one call is deliberately bloated). Two consecutive calls (5 and 6)
// share an identical body AND identical prompt_tokens ⇒ DUPLICATE_CONSECUTIVE_CALL.
const OPTIMISE_RUN = [
  { opt: 1, turn: 1, text: 'Let me check the refund policy and the order.', usage: { input: 2400, output: 40 } },
  { opt: 2, turn: 2, text: 'I need to look up the ticket.', toolUse: true, usage: { input: 2410, output: 45 } },
  { opt: 3, turn: 3, text: 'The order was delivered 12 days ago; a refund is permitted.', usage: { input: 3590, output: 60 } },
  { opt: 4, turn: 3, text: 'Here is a very long, over-verbose answer that should have been constrained.', usage: { input: 3620, output: 1800 } },
  // Calls 5 and 6 are an identical retry pair (same body AND same prompt_tokens) ⇒ DUPLICATE_CONSECUTIVE_CALL.
  { opt: 5, turn: 3, text: 'Refund approved — processed to the original payment method.', usage: { input: 3600, output: 55 } },
  { opt: 6, turn: 3, text: 'Refund approved — processed to the original payment method.', usage: { input: 3600, output: 55 } },
  { opt: 7, turn: 3, text: 'Anything else I can help with?', usage: { input: 3610, output: 30 } },
];

async function optimisationShowcase() {
  log('\n→ LLM cost-optimisation showcase (Optimise tab — all six signals)');

  // One high-priority mock per call, keyed by ?opt=N, returning the exact usage.
  for (const call of OPTIMISE_RUN) {
    const completion = {
      text: call.text,
      stopReason: call.toolUse ? 'tool_calls' : 'stop',
      usage: { inputTokens: call.usage.input, outputTokens: call.usage.output },
    };
    if (call.toolUse) {
      completion.toolCalls = [{ id: LOOKUP_TICKET.id, name: 'lookup_ticket', arguments: LOOKUP_TICKET.function.arguments }];
    }
    await expectation(`optimise call ${call.opt} (usage in=${call.usage.input} out=${call.usage.output})`, {
      priority: 50,
      httpRequest: { method: 'POST', path: OPTIMISE_PATH, queryStringParameters: { opt: [String(call.opt)] } },
      httpLlmResponse: { provider: 'OPENAI', model: OPTIMISE_MODEL, completion },
    });
  }

  // Drive the run, back-to-back so the duplicate pair stays consecutive in the log.
  for (const call of OPTIMISE_RUN) {
    await traffic(`optimise run · call ${call.opt}`, 'POST', `${OPTIMISE_PATH}?opt=${call.opt}`, {
      body: { model: OPTIMISE_MODEL, messages: optimiseMessages(call.turn) },
      headers: { 'content-type': 'application/json' },
    });
  }
  log('   ↳ open the dashboard Optimise tab (or curl /mockserver/llm/optimisationReport?format=markdown) to see all six signals');
}

// ---------------------------------------------------------------------------
// 6b. Load injection (Performance tab — live load scenario vs a delayed target)
// ---------------------------------------------------------------------------

// Opt-in via DEMO_WITH_LOAD_INJECTION=true (the launcher's --with-load-injection flag,
// which also starts the server with -Dmockserver.loadGenerationEnabled=true so the
// /mockserver/loadScenario control plane is enabled, and -Dmockserver.sloTrackingEnabled
// so the load run's samples feed the SLO store). This registers a few SELF-TARGET
// endpoints with realistic delays, then PUTs a long-running load scenario that drives
// traffic at them — so opening the dashboard's Performance tab shows a running scenario
// with live latency / throughput against a real (delayed) target you can watch and edit.

// Endpoints the load scenario drives. Each is a normal mock on THIS server with a
// deliberate delay so the latency histogram has something non-trivial to show. The
// slow endpoint uses a UNIFORM delay distribution for per-request jitter (so p50/p95
// differ); the others use a fixed delay. All are unlimited so the scenario can hammer
// them for the whole run.
const LOAD_TARGETS = [
  {
    label: 'fast health (≈5ms)',
    path: '/demo-target/health',
    response: {
      statusCode: 200,
      headers: { 'content-type': ['application/json'] },
      body: '{"status":"UP"}',
      delay: { timeUnit: 'MILLISECONDS', value: 5 },
    },
  },
  {
    label: 'slow checkout (≈250ms ± jitter)',
    path: '/demo-target/checkout',
    response: {
      statusCode: 200,
      headers: { 'content-type': ['application/json'] },
      body: '{"order":"placed","ms":250}',
      // UNIFORM jitter 180–320ms → realistic spread so p50 < p95 < p99 in the metrics.
      delay: { timeUnit: 'MILLISECONDS', value: 250, distribution: { type: 'UNIFORM', min: 180, max: 320 } },
    },
  },
  {
    // Parameterised: a path regex so /demo-target/orders/<anything> matches; the
    // scenario step varies the id via the iteration.index template var below.
    label: 'parameterised order (≈80ms)',
    path: '/demo-target/orders/.*',
    response: {
      statusCode: 200,
      headers: { 'content-type': ['application/json'] },
      body: '{"order":"found"}',
      delay: { timeUnit: 'MILLISECONDS', value: 80 },
    },
  },
];

async function loadInjectionExamples() {
  if (process.env.DEMO_WITH_LOAD_INJECTION !== 'true') return;
  log('\n→ Load injection (Performance tab — live load scenario vs a delayed target)');

  // a) Register the delayed self-target endpoints (unlimited times).
  for (const t of LOAD_TARGETS) {
    await expectation(`load target  GET ${t.path}  (${t.label})`, {
      httpRequest: { method: 'GET', path: t.path },
      httpResponse: t.response,
    });
  }

  // b) LOAD (register) a long-running CONSTANT load scenario that self-targets THIS server, then
  //    TRIGGER it to start. Loading no longer runs the scenario — PUT /loadScenario registers it
  //    (LOADED), and PUT /loadScenario/start triggers it (RUNNING). A 1-hour duration + a high
  //    maxRequests keeps it running while the user explores the UI; 5 VUs with per-step think-time
  //    keeps the self-load gentle. The path uses $iteration.index (Velocity) so the order id varies.
  const scenario = {
    name: 'demo checkout journey',
    templateType: 'VELOCITY',
    // Cap well above what 5 VUs hit in an hour so the run is bounded but never ends early.
    maxRequests: 2000000,
    labels: { team: 'demo', env: 'local' },
    // A short VU ramp (1->5 over 30s) then hold at 5 VUs for the rest of the hour, so the
    // dashboard shows a real ramp followed by a steady state. Stages run in sequence; the
    // total duration is the documented 1-hour max.
    profile: {
      stages: [
        { type: 'VU', startVus: 1, endVus: 5, durationMillis: 30000, curve: 'LINEAR' },
        { type: 'VU', vus: 5, durationMillis: 3570000 },
      ],
    },
    steps: [
      {
        name: 'health',
        request: {
          method: 'GET',
          path: '/demo-target/health',
          socketAddress: { host: SELF_HOST, port: SELF_PORT, scheme: SELF_SCHEME },
        },
        thinkTime: { timeUnit: 'MILLISECONDS', value: 50 },
      },
      {
        name: 'checkout',
        request: {
          method: 'GET',
          path: '/demo-target/checkout',
          socketAddress: { host: SELF_HOST, port: SELF_PORT, scheme: SELF_SCHEME },
        },
        thinkTime: { timeUnit: 'MILLISECONDS', value: 100 },
      },
      {
        name: 'order',
        request: {
          method: 'GET',
          // iteration.index varies the order id each iteration so the data isn't static.
          path: '/demo-target/orders/$iteration.index',
          socketAddress: { host: SELF_HOST, port: SELF_PORT, scheme: SELF_SCHEME },
        },
        thinkTime: { timeUnit: 'MILLISECONDS', value: 50 },
      },
    ],
  };

  // A SECOND scenario, registered with a startDelayMillis so it begins ~20s AFTER it is triggered —
  // showcasing staggered concurrent runs. It hits only the health endpoint at a steady 2 VUs.
  const delayedScenario = {
    name: 'demo background poller',
    templateType: 'VELOCITY',
    maxRequests: 2000000,
    labels: { team: 'demo', env: 'local' },
    // Begin 20s after the start trigger so the dashboard shows it PENDING then RUNNING alongside
    // the checkout journey (two concurrent runs, staggered).
    startDelayMillis: 20000,
    profile: {
      stages: [
        { type: 'VU', vus: 2, durationMillis: 3580000 },
      ],
    },
    steps: [
      {
        name: 'poll-health',
        request: {
          method: 'GET',
          path: '/demo-target/health',
          socketAddress: { host: SELF_HOST, port: SELF_PORT, scheme: SELF_SCHEME },
        },
        thinkTime: { timeUnit: 'MILLISECONDS', value: 250 },
      },
    ],
  };

  // LOAD (register) both scenarios. Loading is allowed even when load generation is disabled.
  const loadRes = await api('PUT', '/mockserver/loadScenario', scenario);
  if (!loadRes.ok) throw new Error(`Failed to load load scenario "${scenario.name}": HTTP ${loadRes.status}`);
  const loadRes2 = await api('PUT', '/mockserver/loadScenario', delayedScenario);
  if (!loadRes2.ok) throw new Error(`Failed to load load scenario "${delayedScenario.name}": HTTP ${loadRes2.status}`);

  // TRIGGER both to start in one call (each honours its own startDelayMillis).
  const startRes = await api('PUT', '/mockserver/loadScenario/start', {
    names: [scenario.name, delayedScenario.name],
  });
  if (startRes.status === 403) {
    // Shouldn't happen via the launcher (it sets loadGenerationEnabled=true), but be helpful.
    log('   ! load scenarios LOADED but NOT started — server returned 403 (load generation disabled).');
    log('     Start MockServer with -Dmockserver.loadGenerationEnabled=true (the launcher\'s');
    log('     --with-load-injection flag does this) and re-run, or use: npm run demo -- --with-load-injection');
    counts.loadScenario = 2;
    return;
  }
  if (!startRes.ok) throw new Error(`Failed to start load scenarios: HTTP ${startRes.status}`);
  counts.loadScenario = 2;
  log(`   ⚡ load scenario  "${scenario.name}"  (VU ramp 1→5 then hold, ${scenario.steps.length} steps: health / checkout / order)`);
  log(`   ⚡ load scenario  "${delayedScenario.name}"  (startDelayMillis 20000 → PENDING then RUNNING, 2 VUs)`);
  log('   ↳ two load scenarios are now triggered against the delayed self-target endpoints —');
  log('     open the dashboard Performance tab to watch live throughput + latency and to edit them,');
  log(`     or curl ${BASE}/mockserver/loadScenario for the live JSON list.`);
}

// ---------------------------------------------------------------------------
// Orchestration
// ---------------------------------------------------------------------------

async function main() {
  log(`\nPopulating MockServer demo data at ${BASE}\n`);

  // Fail fast with a clear message if the server is not reachable.
  let status;
  try {
    status = await api('PUT', '/mockserver/status');
  } catch (err) {
    console.error(`ERROR: cannot reach MockServer at ${BASE} (${err.message}).`);
    console.error('Start MockServer first, or pass --url <baseUrl>.');
    process.exit(1);
  }
  if (!status.ok) {
    console.error(`ERROR: MockServer at ${BASE} returned HTTP ${status.status} for /mockserver/status.`);
    process.exit(1);
  }

  log('→ Resetting MockServer');
  const reset = await api('PUT', '/mockserver/reset');
  if (!reset.ok) {
    console.error(`ERROR: reset failed (HTTP ${reset.status}) — is authentication enabled on this MockServer?`);
    process.exit(1);
  }

  await plainHttpExpectations();
  await proxyExpectations();
  await breakpointLoopbackExpectations();
  await llmExpectations();
  await conversationExpectations();
  await serviceChaosExamples();
  await tcpChaosExamples();
  await grpcHealthExamples();
  await grpcChaosExamples();
  await chaosExperimentExample();
  await grpcMockExpectations();
  await dnsMockExpectations();
  await wasmModuleExample();
  await sideEffectExpectations();
  await scenarioStateExamples();
  await cassetteExamples();
  await grpcDescriptorExample();
  await asyncApiExamples();
  await driftExamples();
  await mcpServerHealthExpectations();
  await plainHttpTraffic();
  await proxyTraffic();
  await llmTraffic();
  await agentLoops();
  await optimisationShowcase();
  await mcpServerHealthTraffic();
  await mcpToolCallExamples();
  await matchedShowcaseTraffic();
  await loadInjectionExamples();

  log('\n========================================');
  log(' Demo data loaded');
  log('========================================');
  log(` Expectations created : ${counts.expectations}`);
  log(` Requests sent        : ${counts.requests} (incl. ~${counts.unmatched} intentionally unmatched)`);
  log(` Service chaos hosts  : ${counts.serviceChaos} (incl. GraphQL-semantic chaos + auto-revert TTL)`);
  log(` TCP chaos hosts      : ${counts.tcpChaos} (2 with an auto-revert TTL countdown)`);
  log(` gRPC health statuses : ${counts.grpcHealth} (NOT_SERVING / SERVICE_UNKNOWN / SERVING)`);
  log(` gRPC chaos services  : ${counts.grpcChaos} (incl. streaming/trailer faults + auto-revert TTL)`);
  log(` Chaos experiments    : ${counts.experiments} (3-stage looping experiment; live status in Chaos · Experiments)`);
  log(` WASM modules         : ${counts.wasmModules} (example Rust match module + a WASM-matched expectation)`);
  log(` Side-effect exps     : ${counts.sideEffects} (before-actions [blocking/non-blocking] + after-actions [webhook])`);
  log(` Scenarios            : ${counts.scenarios} (incl. one timed auto-transition; listed in the Scenarios panel)`);
  log(` gRPC descriptor sets : ${counts.grpcServices} (greeting.dsc → com.example.grpc.GreetingService, 4 methods)`);
  log(` Cassettes            : ${counts.cassettes} (registered server-side; listed in Library · Cassettes)`);
  log(` Drift scenarios      : ${counts.drift} (status / schema-added / schema-removed+type / header)`);
  log(` AsyncAPI channels    : ${counts.asyncChannels}${process.env.DEMO_MQTT_BROKER_URL ? ' (live MQTT broker — Recorded Messages ticking up)' : ' (broker-less; Recorded Messages need --with-broker)'}`);
  log(` MCP tool calls       : ${counts.mcpCalls} (read-only tools; Metrics · "MCP tool calls" chart + Tools · MCP panel)`);
  log(` MCP server calls     : ${counts.mcpServerCalls} across ${MCP_HEALTH_SERVERS.length} servers (per-server latency/errors in AI · MCP Health — chrome-devtools-mcp is the slow one)`);
  if (counts.loadScenario) {
    log(` Load scenarios       : ${counts.loadScenario} loaded+triggered (checkout journey now + a 20s-delayed background poller; concurrent runs live in the Performance tab)`);
  }
  log('');

  // The example cassettes are registered in the server-side registry (so they appear in the
  // Cassettes tab automatically). Their expectations are not loaded — use the Cassettes · Load tab
  // (or load_expectations_from_file) with these paths to replay them:
  const cassetteDir = join(SCRIPT_DIR, 'demo-cassettes');
  log(' Example cassette files (load via Library → Cassettes → Load to replay their expectations):');
  for (const filename of DEMO_CASSETTES) {
    log(`   ${join(cassetteDir, filename)}`);
  }
  log('');

  log(' Try these views in the dashboard:');
  log('   Dashboard / Library — active expectations (HTTP, forward, LLM, conversation pills, before/after actions) + WASM modules + gRPC Descriptors tab');
  log('   Mocks              — HTTP, gRPC (stream/unary), DNS (A/AAAA/CNAME/NXDOMAIN) mocks listed per kind');
  log('   Traffic            — recorded + proxied (forwarded) requests, incl. a lane per LLM provider + token/cost');
  log('   Sessions           — agent-001 / agent-002 loops + call graphs; Scenarios panel lists the seeded scenario state machines');
  log('   Optimise           — LLM cost-optimisation brief from a crafted 7-call support-agent run; all six signals fire (REPEATED_SYSTEM_PROMPT, LARGE_STATIC_CONTEXT_RESENT, DETERMINISTIC_TOOL_CALL, OVERSIZED_TOOL_RESULT, OUTPUT_TOKEN_BLOAT, DUPLICATE_CONSECUTIVE_CALL)');
  log('   MCP Health         — per-MCP-server latency + error rate across 4 servers (chrome-devtools-mcp flagged SLOW, github-mcp high error rate), worst-first');
  log('   Chaos              — HTTP service chaos (incl. GraphQL-semantic) + gRPC chaos (health + fault injection with streaming/trailer faults) + TCP-layer chaos + a looping multi-stage Experiment');
  log('   Drift              — schema / status / header drift records from proxied-vs-stub comparison');
  log('   AsyncAPI           — Tools · AsyncAPI Broker Mock: loaded spec + Channels table (5 channels, varied schema/example counts)');
  log('   Metrics            — request activity, throughput, MCP tool calls (6 tools), chaos faults' + (process.env.DEMO_MQTT_BROKER_URL ? ', async messages' : ''));
  log('   Breakpoints        — register a matcher (Matchers tab), then trigger the loopback below to pause Live Exchanges / Live Streams');
  if (counts.loadScenario) {
    log('   Performance        — two load scenarios triggered (checkout journey RUNNING + a 20s-delayed background poller) against delayed self-target endpoints; watch live throughput + latency, and edit/restart them');
  }
  log('');

  // --- Interactive breakpoints: loopback endpoints + how to drive them ---
  log('========================================');
  log(' Interactive breakpoints — Live Exchanges & Live Streams');
  log('========================================');
  log(' Breakpoints pause matched exchanges (forwards, mock responses, and unmatched 404s)');
  log(' and are resolved live in the dashboard Breakpoints panel (which connects to the');
  log(' callback WebSocket as a client). This demo seeded two self-forwarded (loopback)');
  log(' endpoints to pause:');
  log(`   GET ${BASE}/breakpoint/exchange   (buffered JSON  -> Live Exchanges, REQUEST + RESPONSE phases)`);
  log(`   GET ${BASE}/breakpoint/stream     (chunked stream -> Live Streams,   RESPONSE_STREAM phase)`);
  log('');
  log(' 0) Sanity-check the loopback works (no breakpoint registered yet):');
  log(`      curl -s ${BASE}/breakpoint/exchange`);
  log(`      curl -N -s ${BASE}/breakpoint/stream`);
  log('');
  log(' 1) Live Exchanges:');
  log('      • Dashboard → Breakpoints → Matchers tab → Add matcher:');
  log('          method GET, path /breakpoint/exchange, phase = Request and/or Response');
  log('      • Then run:');
  log(`          curl -s ${BASE}/breakpoint/exchange`);
  log('      • Request phase pauses BEFORE the forward (Continue / Modify / Abort); Response');
  log('        phase pauses the forwarded response before it is written. Both show in Live Exchanges.');
  log('');
  log(' 2) Live Streams:');
  log('      • Matchers tab → Add matcher:');
  log('          method GET, path /breakpoint/stream, phase = Response stream frames');
  log('      • Then run (note -N to disable curl buffering so frames arrive one at a time):');
  log(`          curl -N -s ${BASE}/breakpoint/stream`);
  log('      • Each chunk pauses in the Live Streams tab — Continue / Modify / Drop / Inject / Close.');
  log('');
  log(' (Matcher registration uses the dashboard\'s own callback clientId automatically.');
  log('  The equivalent REST call is PUT /mockserver/breakpoint/matcher with');
  log('  {httpRequest, phases, clientId} — clientId must be a CONNECTED callback client.)');
  log('');
}

main().catch((err) => {
  console.error(`\nERROR: ${err?.message ?? String(err)}`);
  process.exit(1);
});
