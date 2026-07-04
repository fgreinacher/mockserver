/**
 * Shared fixture set for the per-language emitter byte-identity parity harness
 * ({@link ./extractParity.test.ts}) and the one-off golden generator.
 *
 * The combos are chosen to exercise the distinct buildExpectationJson branches
 * AND the per-language escaping paths (Go backtick break-out, Rust raw-string
 * hash escalation, C# verbatim quote doubling, Python literalisation of
 * booleans/null). The typed-construction Ruby emitter is exercised by its own
 * golden harness (./ruby.test.ts against ./__fixtures__/rubyGolden.ts).
 */
import {
  standardToGo,
  standardToCsharp,
  standardToRust,
  type StandardMatcher,
  type StandardActionPayload,
} from '../standardCodegen';

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
    secure: false, priority: 0, times: 0, ...overrides,
  };
}

const BASE_URL = 'http://localhost:1080';
const HTTPS_URL = 'https://mock.example.com';

export interface Combo {
  name: string;
  matcher: StandardMatcher;
  action: StandardActionPayload;
  baseUrl: string;
}

export const combos: Combo[] = [
  {
    name: 'simple-static',
    matcher: baseMatcher(),
    action: { type: 'static', static: { statusCode: 200, body: 'hello', contentType: 'text/plain', bodyFromFile: false, filePath: '', fileTemplateType: '' } },
    baseUrl: BASE_URL,
  },
  {
    name: 'static-file-body-full',
    matcher: baseMatcher({ method: 'POST', path: '/orders' }),
    action: {
      type: 'static',
      static: {
        statusCode: 201, body: '', contentType: 'application/json',
        bodyFromFile: true, filePath: 'responses/order.json', fileTemplateType: 'MUSTACHE',
        headers: 'X-Trace: abc\nX-Env: prod', cookies: 'session=xyz\ntheme=dark',
        reasonPhrase: 'Created', delayValue: 250, delayUnit: 'MILLISECONDS',
        connectionOptions: { keepAliveOverride: true, closeSocket: false, suppressContentLengthHeader: true },
      },
    },
    baseUrl: BASE_URL,
  },
  {
    name: 'json-body-jwt-secure',
    matcher: baseMatcher({
      method: 'PUT', path: '/account', secure: true,
      headers: 'Accept: application/json', queryString: 'v=2', cookies: 'sid=1',
      pathParams: 'id=42', body: '{"ok":true,"n":3}', bodyMatcherType: 'json', jsonMatchType: 'STRICT',
      jwt: { header: 'x-auth', scheme: 'Token', claims: 'sub=user1\nrole=admin', issuer: 'iss', audience: 'aud', algorithm: 'RS256' },
      priority: 5, times: 2, ttlSeconds: 60,
    }),
    action: { type: 'static', static: { statusCode: 200, body: '', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' } },
    baseUrl: HTTPS_URL,
  },
  {
    name: 'forward',
    matcher: baseMatcher(),
    action: { type: 'forward', forward: { scheme: 'HTTPS', host: 'upstream.example.com', port: 8443 } },
    baseUrl: BASE_URL,
  },
  {
    name: 'forward-override',
    matcher: baseMatcher(),
    action: {
      type: 'forward_override',
      forwardOverride: {
        overrideMethod: 'PATCH', overrideHost: 'rewrite.example.com', overrideScheme: 'HTTPS',
        overridePath: '/v2/api', overrideQueryString: 'debug=1', overrideHeaders: 'X-Fwd: yes', overrideBody: '{"patched":true}',
      },
    },
    baseUrl: BASE_URL,
  },
  {
    name: 'callback',
    matcher: baseMatcher(),
    action: { type: 'callback', callback: { callbackClass: 'com.example.MyCallback' } },
    baseUrl: BASE_URL,
  },
  {
    name: 'template-velocity',
    matcher: baseMatcher(),
    action: { type: 'template', template: { templateType: 'VELOCITY', template: '$!request.body' } },
    baseUrl: BASE_URL,
  },
  {
    name: 'error',
    matcher: baseMatcher(),
    action: { type: 'error', error: { dropConnection: true, responseBytesB64: 'AQIDBA==', delayValue: 5, delayUnit: 'SECONDS' } },
    baseUrl: BASE_URL,
  },
  {
    name: 'forward-fallback',
    matcher: baseMatcher(),
    action: {
      type: 'forward_fallback',
      forwardFallback: {
        scheme: 'HTTP', host: 'primary.example.com', port: 80,
        fallbackStatusCode: 503, fallbackBody: 'unavailable', fallbackOnStatusCodes: '500,502,503', fallbackOnTimeout: true,
      },
    },
    baseUrl: BASE_URL,
  },
  {
    name: 'websocket',
    matcher: baseMatcher(),
    action: {
      type: 'websocket',
      websocket: {
        subprotocol: 'chat', messages: 'hello\nworld', closeConnection: false,
        matchers: [{ frameType: 'TEXT', textMatcher: 'ping', responses: 'pong\nack' }],
      },
    },
    baseUrl: BASE_URL,
  },
  {
    name: 'sse',
    matcher: baseMatcher(),
    action: {
      type: 'sse',
      sse: { statusCode: 200, headers: 'Cache-Control: no-cache', closeConnection: false, events: [{ event: 'msg', data: 'tick', id: '1', retry: '1000' }] },
    },
    baseUrl: BASE_URL,
  },
  {
    name: 'binary-response',
    matcher: baseMatcher(),
    action: { type: 'binary_response', binaryResponse: { binaryData: 'SGVsbG8=' } },
    baseUrl: BASE_URL,
  },
  {
    name: 'dns',
    matcher: baseMatcher({ dns: { dnsName: 'example.com', dnsType: 'A', dnsClass: 'IN' } }),
    action: { type: 'dns_response', dnsResponse: { responseCode: 'NOERROR', answerRecords: '[{"name":"example.com","type":"A","ttl":300,"value":"1.2.3.4"}]' } },
    baseUrl: BASE_URL,
  },
  {
    name: 'grpc-stream',
    matcher: baseMatcher(),
    action: {
      type: 'grpc_stream',
      grpcStream: { statusName: 'OK', statusMessage: '', headers: 'grpc-encoding: identity', messages: '{"a":1}\n{"b":2}', closeConnection: false },
    },
    baseUrl: BASE_URL,
  },
  {
    name: 'allOf-body',
    matcher: baseMatcher({
      method: 'POST', bodyMatcherType: 'allOf',
      bodyAllOf: [
        { type: 'json', value: '{"k":1}' },
        { type: 'xpath', value: '/a/b' },
        { type: 'regex', value: '.*foo.*' },
      ],
    }),
    action: { type: 'static', static: { statusCode: 200, body: 'ok', contentType: 'text/plain', bodyFromFile: false, filePath: '', fileTemplateType: '' } },
    baseUrl: BASE_URL,
  },
  {
    name: 'special-chars-escaping',
    matcher: baseMatcher({ path: '/a`b/c"#d', body: 'line1\nline2 "quoted" `tick`', bodyMatcherType: 'string' }),
    action: { type: 'static', static: { statusCode: 200, body: 'resp "with" `back`tick and #hash', contentType: 'text/plain', bodyFromFile: false, filePath: '', fileTemplateType: '' } },
    baseUrl: BASE_URL,
  },
  {
    name: 'capture-scenario-sideEffects',
    matcher: baseMatcher({ method: 'POST', path: '/checkout' }),
    action: {
      type: 'static',
      static: { statusCode: 200, body: 'ok', contentType: 'text/plain', bodyFromFile: false, filePath: '', fileTemplateType: '' },
      capture: [{ source: 'jsonPath', expression: '$.token', into: 'authToken' }],
      scenario: { name: 'login', requiredState: 'START', transitionTo: 'DONE' },
      scenarioModeled: true,
      sideEffects: [{
        position: 'after', method: 'POST', path: '/audit', host: 'audit.example.com', body: '{"logged":true}',
        delayValue: 0, delayUnit: 'MILLISECONDS', blocking: false, timeoutValue: 0, timeoutUnit: 'MILLISECONDS', failurePolicy: 'BEST_EFFORT',
      }],
    },
    baseUrl: BASE_URL,
  },
  {
    name: 'steps-pipeline',
    matcher: baseMatcher({ path: '/pipeline' }),
    action: {
      type: 'static',
      steps: [
        { actionType: 'httpResponse', responder: true, actionBody: '{"statusCode":200,"body":"step"}', blocking: false, delayValue: 0, delayUnit: 'MILLISECONDS', timeoutValue: 0, timeoutUnit: 'MILLISECONDS', failurePolicy: 'BEST_EFFORT' },
        { actionType: 'httpRequest', responder: false, actionBody: '{"path":"/hook"}', blocking: true, delayValue: 100, delayUnit: 'MILLISECONDS', timeoutValue: 2, timeoutUnit: 'SECONDS', failurePolicy: 'FAIL_FAST' },
      ],
    },
    baseUrl: BASE_URL,
  },
];

// NOTE: `python` is intentionally NOT in this map. The Python emitter was
// rewritten to build typed client objects (see ../python.ts) rather than embed a
// JSON dict, so it no longer reproduces the byte-for-byte from_dict golden the
// other emitters share. It now has its own golden fixture (__fixtures__/pythonGolden.ts)
// and its own test (../../__tests__/pythonCodegen.test.ts) which additionally
// proves round-trip semantic equivalence by executing the generated code.
export const emitters: Record<string, (m: StandardMatcher, a: StandardActionPayload, u: string) => string> = {
  go: standardToGo,
  csharp: standardToCsharp,
  rust: standardToRust,
};
