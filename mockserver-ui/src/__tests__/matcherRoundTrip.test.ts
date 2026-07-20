import { describe, it, expect } from 'vitest';
import { matcherFromExpectation } from '../components/ComposerView';
import { buildExpectationJson, type StandardMatcher, type StandardActionPayload } from '../lib/standardCodegen';

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '',
    method: 'POST',
    path: '/api/test',
    headers: '',
    queryString: '',
    cookies: '',
    pathParams: '',
    body: '',
    bodyBinary: false,
    bodyMatcherType: 'string',
    priority: 0,
    times: 0,
    ...overrides,
  };
}

const staticAction: StandardActionPayload = {
  type: 'static',
  static: { statusCode: 200, body: '{"ok":true}', contentType: 'application/json' },
};

/**
 * The Composer's edit-existing flow writes an expectation with `buildExpectationJson`
 * and later reads it back with `matcherFromExpectation`. These must agree on the wire
 * field names for every body matcher type, or editing silently loses the body.
 */
describe('matcherFromExpectation round-trips body matcher types written by buildExpectationJson', () => {
  function roundTrip(matcher: StandardMatcher) {
    const json = buildExpectationJson(matcher, staticAction);
    return matcherFromExpectation({ key: 'k', value: json as Record<string, unknown> });
  }

  it('GraphQL query survives the round-trip (regression: reader used `graphql`, writer emits `query`)', () => {
    const result = roundTrip(baseMatcher({ bodyMatcherType: 'graphql', body: '{ hero { name } }' }));
    expect(result.bodyMatcherType).toBe('graphql');
    expect(result.body).toBe('{ hero { name } }');
  });

  it('WASM moduleName survives the round-trip (regression: reader had no WASM branch)', () => {
    const result = roundTrip(baseMatcher({ bodyMatcherType: 'wasm', body: 'my-module' }));
    expect(result.bodyMatcherType).toBe('wasm');
    expect(result.body).toBe('my-module');
  });

  it('still round-trips the previously-working types (regex, json-path, binary)', () => {
    const regex = roundTrip(baseMatcher({ bodyMatcherType: 'regex', body: '^a.*z$' }));
    expect(regex.bodyMatcherType).toBe('regex');
    expect(regex.body).toBe('^a.*z$');

    const jsonPath = roundTrip(baseMatcher({ bodyMatcherType: 'json-path', body: '$.store.book[0]' }));
    expect(jsonPath.bodyMatcherType).toBe('json-path');
    expect(jsonPath.body).toBe('$.store.book[0]');

    const binary = roundTrip(baseMatcher({ bodyMatcherType: 'binary', body: 'aGVsbG8=' }));
    expect(binary.bodyMatcherType).toBe('binary');
    expect(binary.body).toBe('aGVsbG8=');
  });

  it('JSON body matcher survives the round-trip with default matchType', () => {
    const result = roundTrip(baseMatcher({ bodyMatcherType: 'json', body: '{"name":"test"}' }));
    expect(result.bodyMatcherType).toBe('json');
    // Parsed JSON comes back pretty-printed
    expect(JSON.parse(result.body)).toEqual({ name: 'test' });
    expect(result.jsonMatchType).toBe('ONLY_MATCHING_FIELDS');
  });

  it('JSON body matcher survives the round-trip with STRICT matchType', () => {
    const result = roundTrip(baseMatcher({ bodyMatcherType: 'json', body: '{"x":1}', jsonMatchType: 'STRICT' }));
    expect(result.bodyMatcherType).toBe('json');
    expect(JSON.parse(result.body)).toEqual({ x: 1 });
    expect(result.jsonMatchType).toBe('STRICT');
  });

  it('STRING body with subString=true survives the round-trip', () => {
    const result = roundTrip(baseMatcher({ bodyMatcherType: 'string', body: 'partial', bodySubString: true }));
    expect(result.bodyMatcherType).toBe('string');
    expect(result.body).toBe('partial');
    expect(result.bodySubString).toBe(true);
  });

  it('STRING body with subString=false stays as plain string', () => {
    const result = roundTrip(baseMatcher({ bodyMatcherType: 'string', body: 'exact' }));
    expect(result.bodyMatcherType).toBe('string');
    expect(result.body).toBe('exact');
    expect(result.bodySubString).toBe(false);
  });

  it('reads a server-serialised bare JSON object body (default ONLY_MATCHING_FIELDS) as a JSON matcher', () => {
    // The server omits the {type:"JSON"} wrapper when matchType is the default,
    // emitting the JSON value directly. Editing such an expectation must come
    // back as a JSON matcher, not an exact-string body.
    const result = matcherFromExpectation({ key: 'k', value: { httpRequest: { body: { name: 'test' } } } });
    expect(result.bodyMatcherType).toBe('json');
    expect(JSON.parse(result.body)).toEqual({ name: 'test' });
    expect(result.jsonMatchType).toBe('ONLY_MATCHING_FIELDS');
  });
});

// ---------------------------------------------------------------------------
// Round-trip tests for static response action fields
// ---------------------------------------------------------------------------

describe('static response action fields round-trip via buildExpectationJson + matcherFromExpectation', () => {
  function roundTripAction(action: StandardActionPayload) {
    const json = buildExpectationJson(baseMatcher(), action);
    // The action fields live in the top-level expectation JSON, not the matcher
    return json;
  }

  it('delay round-trips in httpResponse', () => {
    const json = roundTripAction({
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', delayValue: 500, delayUnit: 'MILLISECONDS' },
    });
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp['delay']).toEqual({ timeUnit: 'MILLISECONDS', value: 500 });
  });

  it('reasonPhrase round-trips in httpResponse', () => {
    const json = roundTripAction({
      type: 'static',
      static: { statusCode: 404, body: '', contentType: '', reasonPhrase: 'Not Found' },
    });
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp['reasonPhrase']).toBe('Not Found');
  });

  it('cookies round-trip in httpResponse', () => {
    const json = roundTripAction({
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', cookies: 'session=abc\nfoo=bar' },
    });
    const resp = json['httpResponse'] as Record<string, unknown>;
    expect(resp['cookies']).toEqual({ session: 'abc', foo: 'bar' });
  });
});

describe('matcherFromExpectation round-trips JWT and allOf', () => {
  function roundTrip(matcher: StandardMatcher) {
    const json = buildExpectationJson(matcher, staticAction);
    return matcherFromExpectation({ key: 'k', value: json as Record<string, unknown> });
  }

  it('round-trips a JWT matcher (claims, issuer, audience, algorithm, header, scheme)', () => {
    const result = roundTrip(baseMatcher({
      jwt: { claims: 'sub=user-1\nscope=!guest', issuer: 'https://issuer', audience: 'aud', algorithm: 'RS256', header: 'x-token', scheme: 'Token' },
    }));
    expect(result.jwt).toEqual({
      header: 'x-token',
      scheme: 'Token',
      claims: 'sub=user-1\nscope=!guest',
      issuer: 'https://issuer',
      audience: 'aud',
      algorithm: 'RS256',
    });
  });

  it('omits default header/scheme on the round-trip (server drops them)', () => {
    const result = roundTrip(baseMatcher({ jwt: { claims: 'sub=abc', header: 'authorization', scheme: 'Bearer' } }));
    expect(result.jwt).toEqual({ claims: 'sub=abc' });
  });

  it('an enabled-but-empty JWT round-trips to no jwt', () => {
    const result = roundTrip(baseMatcher({ jwt: {} }));
    expect(result.jwt).toBeUndefined();
  });

  it('round-trips an allOf composite body preserving each sub-matcher', () => {
    const result = roundTrip(baseMatcher({
      bodyMatcherType: 'allOf',
      bodyAllOf: [
        { type: 'json', value: '{"a":1}' },
        { type: 'regex', value: '^x.*' },
        { type: 'string', value: 'plain' },
        { type: 'xpath', value: '/a/b' },
      ],
    }));
    expect(result.bodyMatcherType).toBe('allOf');
    expect(result.bodyAllOf).toEqual([
      // JSON is normalised through parse+stringify on the way out
      { type: 'json', value: '{\n  "a": 1\n}' },
      { type: 'regex', value: '^x.*' },
      { type: 'string', value: 'plain' },
      { type: 'xpath', value: '/a/b' },
    ]);
  });
});
