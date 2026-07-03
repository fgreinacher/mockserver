/**
 * Regression coverage for the Java client codegen produced by standardCodegen.ts. These
 * pin fixes to bugs where the generated Java did not compile against the real MockServer
 * client API or diverged from the JSON payload:
 *
 *  - DNS expectations emitted a bare request() instead of a dnsRequest() matcher.
 *  - SSE / WebSocket / binary / DNS / gRPC-stream responses used a .respond(...) overload
 *    that does not exist (the client exposes respondWithSse/WebSocket/Binary/Dns/GrpcStream).
 *  - forward-with-fallback used .forward(...) instead of the .forwardWithFallback(...) method.
 *  - chaos chained .withChaos(...) AFTER the terminal action (which returns Expectation[]);
 *    it must come before, on the ForwardChainExpectation.
 *  - DNS answer records / SSE retry / gRPC headers were emitted in JSON but dropped from Java.
 */
import { describe, it, expect } from 'vitest';
import {
  standardToJava,
  standardToJson,
  standardToNode,
  standardToCurl,
  buildExpectationJson,
  unrepresentableJavaActionKey,
  whenArgsFromJson,
  type StandardMatcher,
  type StandardActionPayload,
} from '../lib/standardCodegen';

function httpMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
    secure: false, priority: 0, times: 0, ...overrides,
  };
}

function dnsMatcher(): StandardMatcher {
  return httpMatcher({ method: '', path: '', dns: { dnsName: 'api.example.com', dnsType: 'A', dnsClass: 'IN' } });
}

describe('DNS request matcher Java', () => {
  it('emits a dnsRequest() matcher, not a bare request()', () => {
    const java = standardToJava(dnsMatcher(), { type: 'dns_response', dnsResponse: { responseCode: 'NOERROR', answerRecords: '' } });
    expect(java).toContain('dnsRequest()');
    expect(java).toContain('.withDnsName("api.example.com")');
    expect(java).toContain('.withDnsType(DnsRecordType.A)');
    expect(java).toContain('.withDnsClass(DnsRecordClass.IN)');
    expect(java).toContain('import static org.mockserver.model.DnsRequestDefinition.dnsRequest;');
    expect(java).toContain('import org.mockserver.model.DnsRecordType;');
    expect(java).toContain('import org.mockserver.model.DnsRecordClass;');
    // must NOT fall back to the HTTP request matcher
    expect(java).not.toContain('request()');
  });
});

describe('streaming/binary/DNS response actions use the type-specific fluent method', () => {
  const cases: { type: StandardActionPayload['type']; action: StandardActionPayload; method: string }[] = [
    { type: 'sse', action: { type: 'sse', sse: { statusCode: 200, headers: '', events: [{ event: 'm', data: 'd', id: '', retry: '' }], closeConnection: false } }, method: '.respondWithSse(' },
    { type: 'websocket', action: { type: 'websocket', websocket: { subprotocol: '', messages: 'hi', closeConnection: false, matchers: [] } }, method: '.respondWithWebSocket(' },
    { type: 'binary_response', action: { type: 'binary_response', binaryResponse: { binaryData: 'SGk=' } }, method: '.respondWithBinary(' },
    { type: 'dns_response', action: { type: 'dns_response', dnsResponse: { responseCode: 'NXDOMAIN', answerRecords: '' } }, method: '.respondWithDns(' },
    { type: 'grpc_stream', action: { type: 'grpc_stream', grpcStream: { statusName: 'OK', statusMessage: '', headers: '', messages: '{"a":1}', closeConnection: false } }, method: '.respondWithGrpcStream(' },
  ];
  for (const c of cases) {
    it(`${c.type} uses ${c.method.slice(0, -1)} not .respond(`, () => {
      const java = standardToJava(httpMatcher(), c.action);
      expect(java).toContain(c.method);
      // the non-existent generic .respond(<thatType>) overload must not be used
      expect(java).not.toMatch(/\.respond\(\s*\n\s*(sseResponse|webSocketResponse|binaryResponse|dnsResponse|grpcStreamResponse)/);
    });
  }
});

describe('forward-with-fallback Java', () => {
  it('uses .forwardWithFallback(...) not .forward(...)', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'forward_fallback',
      forwardFallback: { scheme: 'HTTP', host: 'h', port: 80, fallbackStatusCode: 200, fallbackBody: '', fallbackOnStatusCodes: '500', fallbackOnTimeout: true },
    });
    expect(java).toContain('.forwardWithFallback(');
    expect(java).not.toContain('.forward(forwardWithFallback');
  });
});

describe('chaos Java placement', () => {
  it('emits .withChaos(...) before the terminal action', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'ok', contentType: '' },
      chaos: { errorStatus: 503, errorProbability: 1 },
    });
    const chaosIdx = java.indexOf('.withChaos(');
    const respondIdx = java.indexOf('.respond(');
    expect(chaosIdx).toBeGreaterThan(-1);
    expect(respondIdx).toBeGreaterThan(-1);
    expect(chaosIdx).toBeLessThan(respondIdx);
  });
});

describe('Java/JSON parity additions', () => {
  it('SSE emits .withRetry for events with a retry value', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'sse',
      sse: { statusCode: 200, headers: '', events: [{ event: 'm', data: 'd', id: '', retry: '3000' }], closeConnection: false },
    });
    expect(java).toContain('.withRetry(3000)');
  });

  it('gRPC stream emits headers', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'grpc_stream',
      grpcStream: { statusName: 'OK', statusMessage: '', headers: 'x-trace: abc', messages: '', closeConnection: false },
    });
    expect(java).toContain('.withHeader("x-trace", "abc")');
  });

  it('DNS response emits answer records parsed from the JSON field', () => {
    const records = JSON.stringify([{ name: 'api.example.com', type: 'A', ttl: 60, value: '1.2.3.4' }]);
    const java = standardToJava(dnsMatcher(), { type: 'dns_response', dnsResponse: { responseCode: 'NOERROR', answerRecords: records } });
    // the nested dnsRecord() builder is emitted across indented lines, not one long call
    expect(java).toMatch(/\.withAnswerRecord\(\s*\n\s*dnsRecord\(\)/);
    expect(java).toContain('.withName("api.example.com")');
    expect(java).toContain('.withType(DnsRecordType.A)');
    expect(java).toContain('.withTtl(60)');
    expect(java).toContain('.withValue("1.2.3.4")');
    expect(java).toContain('import static org.mockserver.model.DnsRecord.dnsRecord;');
  });
});

describe('static response connectionOptions', () => {
  const action: StandardActionPayload = {
    type: 'static',
    static: { statusCode: 200, body: '', contentType: '', connectionOptions: { keepAliveOverride: false, contentLengthHeaderOverride: 999, suppressConnectionHeader: true } },
  };

  it('emits connectionOptions in the httpResponse JSON (only set fields)', () => {
    const resp = buildExpectationJson(httpMatcher(), action)['httpResponse'] as Record<string, unknown>;
    expect(resp['connectionOptions']).toEqual({ keepAliveOverride: false, contentLengthHeaderOverride: 999, suppressConnectionHeader: true });
  });

  it('emits .withConnectionOptions(...) in Java with the import', () => {
    const java = standardToJava(httpMatcher(), action);
    expect(java).toContain('.withConnectionOptions(');
    expect(java).toContain('.withKeepAliveOverride(false)');
    expect(java).toContain('.withContentLengthHeaderOverride(999)');
    expect(java).toContain('.withSuppressConnectionHeader(true)');
    expect(java).toContain('import static org.mockserver.model.ConnectionOptions.connectionOptions;');
  });

  it('omits connectionOptions when nothing is set', () => {
    const resp = buildExpectationJson(httpMatcher(), { type: 'static', static: { statusCode: 200, body: '', contentType: '' } })['httpResponse'] as Record<string, unknown>;
    expect(resp).not.toHaveProperty('connectionOptions');
  });
});

describe('WASM body matcher Java codegen', () => {
  it('emits WasmBody.wasmBody() not a non-existent wasm() factory', () => {
    const java = standardToJava(httpMatcher({ body: 'my-module', bodyMatcherType: 'wasm' }), {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '' },
    });
    expect(java).toContain('WasmBody.wasmBody("my-module")');
    expect(java).not.toContain('wasm("my-module")');
    expect(java).toContain('import org.mockserver.model.WasmBody;');
  });

  it('emits the correct JSON shape for a wasm body matcher', () => {
    const json = buildExpectationJson(httpMatcher({ body: 'my-module', bodyMatcherType: 'wasm' }), {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '' },
    });
    const body = (json['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body['type']).toBe('WASM');
    expect(body['moduleName']).toBe('my-module');
  });
});

describe('expectation timeToLive', () => {
  const ttlAction: StandardActionPayload = { type: 'static', static: { statusCode: 200, body: '', contentType: '' } };

  it('emits a SECONDS timeToLive when ttlSeconds > 0', () => {
    const json = buildExpectationJson(httpMatcher({ ttlSeconds: 90 }), ttlAction);
    expect(json['timeToLive']).toEqual({ timeUnit: 'SECONDS', timeToLive: 90, unlimited: false });
  });

  it('omits timeToLive when ttlSeconds is 0 or absent', () => {
    expect(buildExpectationJson(httpMatcher({ ttlSeconds: 0 }), ttlAction)).not.toHaveProperty('timeToLive');
    expect(buildExpectationJson(httpMatcher(), ttlAction)).not.toHaveProperty('timeToLive');
  });
});

describe('static response preserves arbitrary response headers', () => {
  const action: StandardActionPayload = {
    type: 'static',
    static: { statusCode: 302, body: '', contentType: '', headers: 'Location: /new\nCache-Control: no-cache' },
  };

  it('emits the extra headers (plus content-type) in the JSON payload', () => {
    const json = buildExpectationJson(httpMatcher(), action);
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp['headers']).toEqual({
      'Location': ['/new'],
      'Cache-Control': ['no-cache'],
    });
  });

  it('emits .withHeader(...) for each extra header in the Java snippet', () => {
    const java = standardToJava(httpMatcher(), action);
    expect(java).toContain('.withHeader("Location", "/new")');
    expect(java).toContain('.withHeader("Cache-Control", "no-cache")');
  });

  it('does not double-emit content-type if the user also types it in the headers textarea', () => {
    const a: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: 'application/json', headers: 'Content-Type: text/html\nX-A: 1' },
    };
    const json = buildExpectationJson(httpMatcher(), a);
    const resp = json['httpResponse'] as Record<string, unknown>;
    // the dedicated contentType field wins; the textarea content-type is dropped
    expect(resp['headers']).toEqual({ 'X-A': ['1'], 'content-type': ['application/json'] });
    const java = standardToJava(httpMatcher(), a);
    expect(java.match(/Content-Type/gi)?.length).toBe(1);
  });

  it('merges extra headers with an explicit content-type', () => {
    const json = buildExpectationJson(httpMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'x', contentType: 'application/json', headers: 'X-Trace: abc' },
    });
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp['headers']).toEqual({
      'X-Trace': ['abc'],
      'content-type': ['application/json'],
    });
  });
});

// ---------------------------------------------------------------------------
// Java tab honest fallback — when editing an expectation whose ORIGINAL action
// the form cannot model (e.g. httpLlmResponse), the merged JSON preserves that
// action, but the fluent Java builder would fabricate an unrelated response().
// The Java tab must instead show an honest notice pointing at the faithful tabs.
// ---------------------------------------------------------------------------

describe('Java tab honest fallback for an unmodeled preserved action (httpLlmResponse)', () => {
  const original = {
    httpRequest: { method: 'POST', path: '/v1/chat/completions' },
    httpLlmResponse: { provider: 'openai', model: 'gpt-4o' },
    id: 'llm-1',
  };
  // The form did NOT model the action (editActionModeled === false), exactly as
  // ComposerView records when actionFromExpectation returns null for httpLlmResponse.
  const editAction: StandardActionPayload = {
    type: 'static',
    static: { statusCode: 200, body: '', contentType: '' },
    editOriginal: original,
    editActionModeled: false,
  };
  const matcher = httpMatcher({ id: 'llm-1', method: 'POST', path: '/v1/chat/completions' });

  it('detects the unrepresentable action key', () => {
    expect(unrepresentableJavaActionKey(editAction)).toBe('httpLlmResponse');
    // A modeled action (or new-compose, no editOriginal) is representable.
    expect(unrepresentableJavaActionKey({ ...editAction, editActionModeled: true })).toBeUndefined();
    expect(unrepresentableJavaActionKey({ type: 'static', static: { statusCode: 200, body: '', contentType: '' } })).toBeUndefined();
  });

  it('Java tab shows an honest notice naming the action, not fabricated builder code', () => {
    const java = standardToJava(matcher, editAction);
    expect(java).toContain('httpLlmResponse');
    expect(java).toContain('cannot represent');
    expect(java).toContain('JSON or curl');
    // No fabricated fluent builder snippet.
    expect(java).not.toContain('mockServerClient');
    expect(java).not.toContain('.respond(');
    expect(java).not.toContain('response(');
    expect(java).not.toContain('request(');
  });

  it('JSON / Node / curl tabs still render the full, faithful expectation JSON (httpLlmResponse preserved)', () => {
    const json = standardToJson(matcher, editAction);
    expect(JSON.parse(json)['httpLlmResponse']).toEqual(original.httpLlmResponse);
    // The fabricated static response must NOT leak into the wire JSON.
    expect(JSON.parse(json)['httpResponse']).toBeUndefined();
    expect(standardToNode(matcher, editAction, 'http://localhost:1080')).toContain('httpLlmResponse');
    expect(standardToCurl(matcher, editAction, 'http://localhost:1080')).toContain('httpLlmResponse');
  });

  it('does NOT trigger the fallback when the action is modeled — Java stays faithful', () => {
    const java = standardToJava(matcher, { ...editAction, editActionModeled: true });
    expect(java).toContain('mockServerClient');
    expect(java).not.toContain('cannot represent');
  });
});

// ---------------------------------------------------------------------------
// Cross-cutting expectation modifiers (priority / times / timeToLive → the
// 4-arg when overload; namespace / scenario / capture setters) and the two new
// request-matcher features (JWT, allOf) — every field the composer emits to JSON
// must also be represented, type-safely, in the Java tab.
// ---------------------------------------------------------------------------

describe('priority / times / timeToLive → 4-arg when(...)', () => {
  it('emits the plain when(request) overload when all three are default', () => {
    const java = standardToJava(httpMatcher(), { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } });
    expect(java).not.toContain('Times.');
    expect(java).not.toContain('TimeToLive.');
    expect(java).not.toContain('import org.mockserver.matchers.Times;');
  });

  it('emits when(request, Times.exactly, TimeToLive.exactly, priority) when all are set', () => {
    const java = standardToJava(
      httpMatcher({ priority: 10, times: 5, ttlSeconds: 120 }),
      { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } },
    );
    expect(java).toContain('Times.exactly(5)');
    expect(java).toContain('TimeToLive.exactly(TimeUnit.SECONDS, 120L)');
    // the priority is the 4th argument to when(...)
    expect(java).toMatch(/TimeToLive\.exactly\(TimeUnit\.SECONDS, 120L\),\n\s*10\n\s*\)/);
    expect(java).toContain('import org.mockserver.matchers.Times;');
    expect(java).toContain('import org.mockserver.matchers.TimeToLive;');
    expect(java).toContain('import java.util.concurrent.TimeUnit;');
  });

  it('uses unlimited() for the unset dimensions when only priority is set', () => {
    const java = standardToJava(
      httpMatcher({ priority: 7 }),
      { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } },
    );
    expect(java).toContain('Times.unlimited()');
    expect(java).toContain('TimeToLive.unlimited()');
    expect(java).toMatch(/TimeToLive\.unlimited\(\),\n\s*7\n\s*\)/);
    // TimeUnit is only needed for a limited TTL
    expect(java).not.toContain('import java.util.concurrent.TimeUnit;');
  });

  it('uses Times.exactly with unlimited TTL and priority 0 when only times is set', () => {
    const java = standardToJava(
      httpMatcher({ times: 3 }),
      { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } },
    );
    expect(java).toContain('Times.exactly(3)');
    expect(java).toContain('TimeToLive.unlimited()');
    expect(java).toMatch(/TimeToLive\.unlimited\(\),\n\s*0\n\s*\)/);
  });
});

describe('scenario setters + namespace + capture on the ForwardChainExpectation', () => {
  it('emits withScenarioName/State/NewScenarioState before the terminal action', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'ok', contentType: '' },
      scenarioModeled: true,
      scenario: { name: 'checkout', requiredState: 'cart', transitionTo: 'paid' },
    });
    expect(java).toContain('.withScenarioName("checkout")');
    expect(java).toContain('.withScenarioState("cart")');
    expect(java).toContain('.withNewScenarioState("paid")');
    expect(java.indexOf('.withScenarioName(')).toBeLessThan(java.indexOf('.respond('));
  });

  it('emits withCapture(capture(CaptureRule.Source.X, ...)) with the imports', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'ok', contentType: '' },
      capture: [
        { source: 'header', expression: 'X-Trace', into: 'trace' },
        { source: 'pathParameter', expression: 'userId', into: 'userId' },
      ],
    });
    expect(java).toContain('.withCapture(');
    expect(java).toContain('capture(CaptureRule.Source.header, "X-Trace", "trace")');
    expect(java).toContain('capture(CaptureRule.Source.pathParameter, "userId", "userId")');
    expect(java).toContain('import static org.mockserver.model.CaptureRule.capture;');
    expect(java).toContain('import org.mockserver.model.CaptureRule;');
  });

  it('single capture rule is emitted inline', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'ok', contentType: '' },
      capture: [{ source: 'queryStringParameter', expression: 'id', into: 'id' }],
    });
    expect(java).toContain('.withCapture(capture(CaptureRule.Source.queryStringParameter, "id", "id"))');
  });

  it('emits withNamespace(...) when the built JSON carries a namespace (edit overlay)', () => {
    const java = standardToJava(httpMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'ok', contentType: '' },
      editOriginal: { httpRequest: { path: '/api' }, namespace: 'team-a' },
      editActionModeled: true,
    });
    expect(java).toContain('.withNamespace("team-a")');
    // the JSON tab it mirrors must also carry the namespace
    expect(JSON.parse(standardToJson(httpMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'ok', contentType: '' },
      editOriginal: { httpRequest: { path: '/api' }, namespace: 'team-a' },
      editActionModeled: true,
    }))['namespace']).toBe('team-a');
  });
});

describe('JWT request matcher', () => {
  const jwtMatcher = httpMatcher({
    jwt: { claims: 'sub=user-1\nscope=!guest', issuer: 'https://issuer', audience: 'my-aud', algorithm: 'RS256', header: 'x-token', scheme: 'Token' },
  });

  it('emits .withJwt(jwt()...) with claim/issuer/audience/algorithm/header/scheme', () => {
    const java = standardToJava(jwtMatcher, { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } });
    expect(java).toContain('.withJwt(jwt()');
    expect(java).toContain('.withHeader("x-token")');
    expect(java).toContain('.withScheme("Token")');
    expect(java).toContain('.withClaim("sub", "user-1")');
    expect(java).toContain('.withClaim("scope", "!guest")');
    expect(java).toContain('.withIssuer("https://issuer")');
    expect(java).toContain('.withAudience("my-aud")');
    expect(java).toContain('.withAlgorithm("RS256")');
    expect(java).toContain('import static org.mockserver.model.Jwt.jwt;');
  });

  it('omits default header/scheme from both JSON and Java', () => {
    const m = httpMatcher({ jwt: { claims: 'sub=abc', header: 'authorization', scheme: 'Bearer' } });
    const java = standardToJava(m, { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } });
    expect(java).not.toContain('.withHeader("authorization")');
    expect(java).not.toContain('.withScheme("Bearer")');
    const jwtJson = JSON.parse(standardToJson(m, { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } }))['httpRequest']['jwt'];
    expect(jwtJson).toEqual({ claims: { sub: 'abc' } });
  });

  it('emits nothing for an enabled-but-empty jwt (byte-identical to no jwt)', () => {
    const withEmpty = standardToJson(httpMatcher({ jwt: {} }), { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } });
    const without = standardToJson(httpMatcher(), { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } });
    expect(withEmpty).toBe(without);
    const java = standardToJava(httpMatcher({ jwt: {} }), { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } });
    expect(java).not.toContain('.withJwt(');
  });
});

describe('allOf composite body matcher', () => {
  const allOfMatcher = httpMatcher({
    bodyMatcherType: 'allOf',
    body: '',
    bodyAllOf: [
      { type: 'json', value: '{"a":1}' },
      { type: 'regex', value: '^x.*' },
      { type: 'string', value: 'plain' },
    ],
  });

  it('emits .withBody(allOf(json(...), regex(...), exact(...))) with imports', () => {
    const java = standardToJava(allOfMatcher, { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } });
    expect(java).toContain('.withBody(allOf(json("{\\"a\\":1}"), regex("^x.*"), exact("plain")))');
    expect(java).toContain('import static org.mockserver.model.AllOfBody.allOf;');
    expect(java).toContain('import static org.mockserver.model.JsonBody.json;');
    expect(java).toContain('import static org.mockserver.model.RegexBody.regex;');
    expect(java).toContain('import static org.mockserver.model.StringBody.exact;');
  });

  it('JSON tab emits the ALL_OF wire shape with bodyAllOf sub-bodies', () => {
    const body = JSON.parse(standardToJson(allOfMatcher, { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } }))['httpRequest']['body'];
    expect(body['type']).toBe('ALL_OF');
    expect(Array.isArray(body['bodyAllOf'])).toBe(true);
    expect(body['bodyAllOf'][0]).toEqual({ type: 'JSON', json: { a: 1 } });
    expect(body['bodyAllOf'][1]).toEqual({ type: 'REGEX', regex: '^x.*' });
    expect(body['bodyAllOf'][2]).toEqual({ type: 'STRING', string: 'plain' });
  });

  it('drops blank sub-matcher rows and emits no body when all are blank', () => {
    const m = httpMatcher({ bodyMatcherType: 'allOf', body: '', bodyAllOf: [{ type: 'json', value: '' }] });
    const body = JSON.parse(standardToJson(m, { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } }))['httpRequest']['body'];
    expect(body).toBeUndefined();
    const java = standardToJava(m, { type: 'static', static: { statusCode: 200, body: 'ok', contentType: '' } });
    expect(java).not.toContain('.withBody(allOf(');
  });
});

describe('whenArgsFromJson timeUnit hardening', () => {
  it('falls back to TimeUnit.SECONDS for an exotic/misspelled timeUnit so the Java compiles', () => {
    const { ttlExpr } = whenArgsFromJson({ timeToLive: { timeUnit: 'FORTNIGHTS', timeToLive: 3, unlimited: false } });
    expect(ttlExpr).toBe('TimeToLive.exactly(TimeUnit.SECONDS, 3L)');
  });

  it('preserves every real java.util.concurrent.TimeUnit constant', () => {
    for (const unit of ['NANOSECONDS', 'MICROSECONDS', 'MILLISECONDS', 'SECONDS', 'MINUTES', 'HOURS', 'DAYS']) {
      const { ttlExpr } = whenArgsFromJson({ timeToLive: { timeUnit: unit, timeToLive: 2, unlimited: false } });
      expect(ttlExpr).toBe(`TimeToLive.exactly(TimeUnit.${unit}, 2L)`);
    }
  });
});
