// Emit kitchen-sink standardToJava() outputs as compilable .java files.
//
// WHY: the composer's Java tab (standardCodegen.ts -> standardToJava) is otherwise
// only verified by string-assertion unit tests. Those pin the *shape* of the
// emitted snippet but cannot catch a client-API rename (e.g. a fluent method that
// no longer exists on MockServerClient / the org.mockserver.model.* builders) —
// the generated Java would ship broken silently. This script emits a broad matrix
// of standardToJava outputs, each wrapped in a self-contained class, so a CI step
// can javac them against the REAL mockserver-client-java jar and fail on drift.
//
// MECHANISM: standardCodegen.ts has ZERO imports and uses only erasable TypeScript
// syntax, so Node's native type-stripping (v22.18+) imports it directly — no
// npm install, no bundler, no vitest. Keep it that way; if standardCodegen ever
// grows a non-erasable import, switch this to a vitest-run emitter instead.
//
// USAGE: node scripts/emit-java-codegen-samples.mjs <output-dir>
//   Writes Sample_NN_<name>.java for every case below into <output-dir>
//   (created if absent; existing *.java in it are cleared first).

import { mkdirSync, writeFileSync, readdirSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { standardToJava } from '../src/lib/standardCodegen.ts';

const here = fileURLToPath(new URL('.', import.meta.url));
const outDir = resolve(process.argv[2] || join(here, '..', '.tmp', 'java-codegen-samples'));

// -------------------------------------------------------------------------
// Case builders
// -------------------------------------------------------------------------

/** A default HTTP request matcher; override individual fields per case. */
function httpMatcher(overrides = {}) {
  return {
    id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
    secure: false, priority: 0, times: 0, ...overrides,
  };
}

/** A DNS request matcher (routes to dnsRequest() in the emitter). */
function dnsMatcher(overrides = {}) {
  return httpMatcher({ method: '', path: '', dns: { dnsName: 'api.example.com', dnsType: 'A', dnsClass: 'IN' }, ...overrides });
}

const staticResponse = { type: 'static', static: { statusCode: 200, body: 'ok', contentType: 'text/plain' } };

// (a) The big kitchen-sink case: non-default priority/times/ttl + scenario +
//     namespace (via edit overlay) + capture + jwt + allOf body + a modeled
//     static response with connectionOptions + extra headers + chaos.
const kitchenSink = {
  name: 'kitchen_sink',
  matcher: httpMatcher({
    method: 'POST', path: '/orders', priority: 10, times: 5, ttlSeconds: 120,
    bodyMatcherType: 'allOf', body: '',
    bodyAllOf: [
      { type: 'json', value: '{"a":1}' },
      { type: 'regex', value: '^x.*' },
      { type: 'string', value: 'plain' },
    ],
    jwt: { claims: 'sub=user-1\nscope=!guest', issuer: 'https://issuer', audience: 'my-aud', algorithm: 'RS256', header: 'x-token', scheme: 'Token' },
  }),
  action: {
    type: 'static',
    static: {
      statusCode: 302, body: 'created', contentType: 'application/json',
      headers: 'Location: /new\nCache-Control: no-cache',
      connectionOptions: { keepAliveOverride: false, contentLengthHeaderOverride: 999, suppressConnectionHeader: true },
      reasonPhrase: 'Found', cookies: 'session=abc', delayValue: 250, delayUnit: 'MILLISECONDS',
    },
    scenarioModeled: true,
    scenario: { name: 'checkout', requiredState: 'cart', transitionTo: 'paid' },
    capture: [
      { source: 'header', expression: 'X-Trace', into: 'trace' },
      { source: 'pathParameter', expression: 'userId', into: 'userId' },
    ],
    chaos: { errorStatus: 503, errorProbability: 1 },
    editOriginal: { httpRequest: { path: '/orders' }, namespace: 'team-a' },
    editActionModeled: true,
  },
};

// (b) One case per terminal action type the emitter supports.
const terminalActions = [
  { name: 'respond_static', matcher: httpMatcher(), action: staticResponse },
  { name: 'respond_template_velocity', matcher: httpMatcher(), action: { type: 'template', template: { templateType: 'VELOCITY', template: '{ "id": $!request.body }' } } },
  { name: 'respond_template_javascript', matcher: httpMatcher(), action: { type: 'template', template: { templateType: 'JAVASCRIPT', template: 'return { statusCode: 200 };' } } },
  { name: 'respond_template_mustache', matcher: httpMatcher(), action: { type: 'template', template: { templateType: 'MUSTACHE', template: '{ "id": "{{ request.body }}" }' } } },
  { name: 'forward', matcher: httpMatcher(), action: { type: 'forward', forward: { scheme: 'HTTPS', host: 'upstream.example.com', port: 443 } } },
  { name: 'forward_override', matcher: httpMatcher(), action: { type: 'forward_override', forwardOverride: { overrideMethod: 'PUT', overrideHost: 'upstream.example.com', overrideScheme: 'HTTPS', overridePath: '/v2/orders', overrideQueryString: 'a=b', overrideHeaders: 'X-Fwd: 1', overrideBody: 'body' } } },
  { name: 'forward_fallback', matcher: httpMatcher(), action: { type: 'forward_fallback', forwardFallback: { scheme: 'HTTP', host: 'h', port: 80, fallbackStatusCode: 200, fallbackBody: 'fallback', fallbackOnStatusCodes: '500,502,503', fallbackOnTimeout: true } } },
  { name: 'forward_template', matcher: httpMatcher(), action: { type: 'forward_template', forwardTemplate: { templateType: 'VELOCITY', template: '$!request' } } },
  { name: 'forward_class_callback', matcher: httpMatcher(), action: { type: 'forward_class_callback', forwardClassCallback: { callbackClass: 'com.example.MyForwardCallback' } } },
  { name: 'class_callback', matcher: httpMatcher(), action: { type: 'callback', callback: { callbackClass: 'com.example.MyResponseCallback' } } },
  { name: 'error', matcher: httpMatcher(), action: { type: 'error', error: { dropConnection: true, responseBytesB64: 'SGk=', delayValue: 100, delayUnit: 'MILLISECONDS' } } },
  { name: 'websocket', matcher: httpMatcher(), action: { type: 'websocket', websocket: { subprotocol: 'chat', messages: 'hello\nworld', closeConnection: true, matchers: [] } } },
  { name: 'sse', matcher: httpMatcher(), action: { type: 'sse', sse: { statusCode: 200, headers: 'X-Stream: 1', events: [{ event: 'message', data: 'payload', id: '1', retry: '3000' }], closeConnection: false } } },
  { name: 'binary_response', matcher: httpMatcher(), action: { type: 'binary_response', binaryResponse: { binaryData: 'SGVsbG8=' } } },
  { name: 'dns_response', matcher: dnsMatcher(), action: { type: 'dns_response', dnsResponse: { responseCode: 'NOERROR', answerRecords: JSON.stringify([{ name: 'api.example.com', type: 'A', ttl: 60, value: '1.2.3.4' }]) } } },
  { name: 'grpc_stream', matcher: httpMatcher(), action: { type: 'grpc_stream', grpcStream: { statusName: 'OK', statusMessage: '', headers: 'x-trace: abc', messages: '{"a":1}\n{"b":2}', closeConnection: false } } },
];

// (c) An edit-overlay case that stays representable (namespace carried through
//     an edit, action re-modeled as a forward).
const editOverlay = {
  name: 'edit_overlay_forward',
  matcher: httpMatcher({ path: '/legacy' }),
  action: {
    type: 'forward',
    forward: { scheme: 'HTTP', host: 'legacy.internal', port: 8080 },
    editOriginal: { httpRequest: { path: '/legacy' }, namespace: 'migration', scenarioName: 'cutover', scenarioState: 'phase-1' },
    editActionModeled: true,
  },
};

// (d.1) Preserved httpLlmResponse action (editActionModeled === false): the standard
//       composer form cannot model an LLM response, so an edit of such an expectation
//       preserves it verbatim and standardToJava transpiles it into the type-safe
//       llmResponse() builder chain (.respondWithLlm(...)). This kitchen-sink LLM
//       payload exercises the FULL org.mockserver.model LLM builder surface —
//       Completion/Usage/StreamingPhysics/ToolUse/ConversationPredicates/
//       NormalizationOptions/EmbeddingResponse/RerankResponse/ModerationResponse/
//       LlmContentFilter/LlmChaosProfile — so a rename of any of those fluent methods
//       fails this javac gate rather than shipping broken generated Java.
const llmPreserved = {
  name: 'llm_response_preserved',
  matcher: httpMatcher({ method: 'POST', path: '/v1/chat/completions', priority: 7, times: 3, ttlSeconds: 120 }),
  action: {
    type: 'static',
    static: { statusCode: 200, body: '', contentType: '' },
    scenarioModeled: true,
    scenario: { name: 'chat', requiredState: 'greeted', transitionTo: 'answered' },
    editActionModeled: false,
    editOriginal: {
      httpRequest: { method: 'POST', path: '/v1/chat/completions' },
      httpLlmResponse: {
        provider: 'OPENAI',
        model: 'gpt-4o',
        completion: {
          text: 'Hello there',
          toolCalls: [{ id: 'call_1', name: 'get_weather', arguments: '{"city":"SF"}' }],
          stopReason: 'stop',
          usage: { inputTokens: 12, outputTokens: 34, cachedInputTokens: 4, cacheCreationTokens: 2, reasoningTokens: 8 },
          streaming: true,
          streamingPhysics: { timeToFirstToken: { timeUnit: 'MILLISECONDS', value: 250 }, tokensPerSecond: 50, jitter: 0.2, seed: 99, subwordStreaming: false },
          outputSchema: '{"type":"object"}',
          enforceOutputSchema: true,
          toolChoice: 'required',
          reasoningText: 'let me think',
          reasoningSignature: 'sig-abc',
        },
        conversationPredicates: {
          turnIndex: 2,
          latestMessageContains: 'weather',
          latestMessageRole: 'USER',
          normalization: { collapseWhitespace: true, lowercase: true, dropVolatileFields: ['ts', 'id'] },
        },
        embedding: { dimensions: 1536, deterministicFromInput: true, seed: 7 },
        rerank: { topN: 3, deterministicFromInput: false, seed: 11 },
        moderation: { flaggedCategories: ['hate', 'violence'], model: 'omni-moderation-latest' },
        contentFilter: { hate: 'high', sexual: 'safe', violence: 'medium', selfHarm: 'low' },
        chaos: { errorStatus: 429, retryAfter: '30', errorProbability: 0.5, truncateMode: 'MID_STREAM', truncateAtFraction: 0.75, malformedSse: true, seed: 5, quotaName: 'gpt', quotaLimit: 100, quotaWindowMillis: 60000, errorKind: 'RATE_LIMIT', contentFilterBlockProbability: 0.1 },
        delay: { timeUnit: 'MILLISECONDS', value: 500 },
        primary: true,
      },
    },
  },
};

// (d.2) Preserved response SEQUENCE (editActionModeled === false): the standard
//       composer form cannot model an httpResponses sequence, so an edit preserves
//       it verbatim and standardToJava emits the typed terminal
//       .respond(Arrays.asList(response()..., ...)) plus the selection controls
//       (withResponseMode / withResponseWeights / withSwitchAfter). Exercises the
//       ForwardChainExpectation sequence API so a rename fails this javac gate.
const responseSequencePreserved = {
  name: 'response_sequence_preserved',
  matcher: httpMatcher({ method: 'GET', path: '/seq' }),
  action: {
    type: 'static',
    static: { statusCode: 200, body: '', contentType: '' },
    editActionModeled: false,
    editOriginal: {
      httpRequest: { method: 'GET', path: '/seq' },
      httpResponses: [
        { statusCode: 200, body: 'first' },
        { statusCode: 201, body: 'second', headers: { 'content-type': ['text/plain'] } },
      ],
      responseMode: 'WEIGHTED',
      responseWeights: [3, 1],
      switchAfter: 5,
    },
  },
};

// (d.3) Preserved cross-protocol scenarios + percentage siblings alongside a
//       form-modeled static action (editActionModeled === true): exercises
//       withPercentage / withCrossProtocolScenario (CrossProtocolTrigger) and the
//       honest trailing NOTE for a field the Java API cannot set (timestamp).
const crossProtocolPreserved = {
  name: 'cross_protocol_preserved',
  matcher: httpMatcher({ method: 'GET', path: '/xp' }),
  action: {
    type: 'static',
    static: { statusCode: 200, body: 'ok', contentType: '' },
    editActionModeled: true,
    editOriginal: {
      httpRequest: { method: 'GET', path: '/xp' },
      httpResponse: { statusCode: 200, body: 'ok' },
      crossProtocolScenarios: [
        { trigger: 'DNS_QUERY', matchPattern: '*.example.com', scenarioName: 'dns-scn', targetState: 'RESOLVED' },
      ],
      namespace: 'team-a',
      percentage: 25,
      timestamp: '2026-01-01T00:00:00Z',
    },
  },
};

// (d) Side-effect (before/after webhook) + wasm body matcher + connectionOptions-only.
const extras = [
  {
    name: 'side_effects',
    matcher: httpMatcher(),
    action: {
      type: 'static',
      static: { statusCode: 200, body: 'ok', contentType: '' },
      sideEffects: [
        { position: 'before', method: 'POST', path: '/audit', host: 'audit.internal', body: 'x', delayValue: 0, delayUnit: 'MILLISECONDS', blocking: true, timeoutValue: 5, timeoutUnit: 'SECONDS', failurePolicy: 'FAIL_FAST' },
        { position: 'after', method: 'POST', path: '/notify', host: 'notify.internal', body: 'y', delayValue: 100, delayUnit: 'MILLISECONDS', blocking: false, timeoutValue: 0, timeoutUnit: 'SECONDS', failurePolicy: 'BEST_EFFORT' },
      ],
    },
  },
  {
    name: 'wasm_body',
    matcher: httpMatcher({ body: 'my-module', bodyMatcherType: 'wasm' }),
    action: staticResponse,
  },
];

const cases = [kitchenSink, ...terminalActions, editOverlay, llmPreserved, responseSequencePreserved, crossProtocolPreserved, ...extras];

// Exhaustiveness guard (review INC-12): every StandardActionType member must be
// exercised by exactly one terminalActions case, so a 15th action type added to
// standardCodegen.ts fails this gate until a sample covers it. Keep this list in
// sync with the StandardActionType union in ../src/lib/standardCodegen.ts.
const EXPECTED_ACTION_TYPES = [
  'static', 'forward', 'forward_override', 'forward_fallback', 'callback',
  'template', 'error', 'websocket', 'sse', 'binary_response', 'dns_response',
  'forward_template', 'forward_class_callback', 'grpc_stream',
].sort();
const emittedTypes = [kitchenSink, ...terminalActions].map((c) => c.action.type).sort();
const missing = EXPECTED_ACTION_TYPES.filter((t) => !emittedTypes.includes(t));
if (missing.length > 0) {
  console.error(`FATAL: emitter does not cover action type(s): ${missing.join(', ')}`);
  process.exit(1);
}

// -------------------------------------------------------------------------
// Wrap each snippet into a compilable class.
// -------------------------------------------------------------------------

/** Turn a standardToJava snippet (imports + `mockServerClient....;` body) into a
 *  self-contained compilation unit. Import lines are hoisted above the class; the
 *  remaining body runs inside a method with a MockServerClient field in scope. */
function wrapAsClass(className, snippet) {
  const lines = snippet.split('\n');
  const imports = lines.filter((l) => l.startsWith('import '));
  const body = lines.filter((l) => !l.startsWith('import '));
  const indentedBody = body.map((l) => (l.length ? '        ' + l : l)).join('\n');
  return [
    ...imports,
    '',
    'public class ' + className + ' {',
    '    private final org.mockserver.client.MockServerClient mockServerClient =',
    '        new org.mockserver.client.MockServerClient("localhost", 1080);',
    '',
    '    public void run() {',
    indentedBody,
    '    }',
    '}',
    '',
  ].join('\n');
}

// -------------------------------------------------------------------------
// Emit.
// -------------------------------------------------------------------------

mkdirSync(outDir, { recursive: true });
for (const f of readdirSync(outDir)) {
  if (f.endsWith('.java')) rmSync(join(outDir, f));
}

let n = 0;
for (const c of cases) {
  const idx = String(n).padStart(2, '0');
  const className = 'Sample_' + idx + '_' + c.name;
  const snippet = standardToJava(c.matcher, c.action);
  const source = wrapAsClass(className, snippet);
  writeFileSync(join(outDir, className + '.java'), source);
  n += 1;
}

console.log('Emitted ' + n + ' Java codegen samples to ' + outDir);
