/**
 * Tests for the typed C# emitter (`standardToCsharp`).
 *
 * The emitter was rewritten to construct strongly-typed MockServer.Client objects
 * (an `Expectation` object initializer with typed request/response/action graphs
 * and fluent `WithX` builders) instead of deserialising an embedded JSON blob.
 *
 * Coverage here is three-fold:
 *  1. a byte-exact golden over the shared `combos` fixture (freezes output drift);
 *  2. structural guarantees — typed construction, and crucially NO whole-payload
 *     `JsonSerializer.Deserialize<Expectation>` (the pattern this rewrite removes);
 *  3. edit-only passthrough features not present in `combos` — namespace, response
 *     sequences, response-mode and preserved LLM actions.
 *
 * The authoritative correctness proof (that the emitted typed C# compiles and
 * serialises to byte-identical JSON vs. `buildExpectationJson`) lives in the
 * .NET client test project — see
 * `mockserver-client-dotnet/test/MockServer.Client.Tests/ComposerCodegenEquivalenceTests.cs`.
 */
import { describe, it, expect } from 'vitest';
import { standardToCsharp, type StandardMatcher, type StandardActionPayload } from '../standardCodegen';
import { combos } from './extractParityCases';
import { csharpGolden } from './__fixtures__/csharpGolden';

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
    secure: false, priority: 0, times: 0, ...overrides,
  };
}

const BASE_URL = 'http://localhost:1080';

describe('standardToCsharp — golden byte-identity over shared combos', () => {
  for (const combo of combos) {
    it(`${combo.name}`, () => {
      const actual = standardToCsharp(combo.matcher, combo.action, combo.baseUrl);
      expect(actual).toBe(csharpGolden[combo.name]);
    });
  }
});

describe('standardToCsharp — structural guarantees', () => {
  it('emits typed Expectation construction and Upsert, not a JSON blob', () => {
    const code = standardToCsharp(baseMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'hello', contentType: 'text/plain', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    }, BASE_URL);
    expect(code).toContain('using MockServer.Client;');
    expect(code).toContain('using MockServer.Client.Models;');
    expect(code).toContain('using var client = new MockServerClient("localhost", 1080);');
    expect(code).toContain('client.Upsert(new Expectation');
    expect(code).toContain('HttpRequest = new HttpRequest');
    expect(code).toContain('HttpResponse = new HttpResponse');
  });

  it('never emits a whole-payload JsonSerializer.Deserialize<Expectation> for any combo', () => {
    for (const combo of combos) {
      const code = standardToCsharp(combo.matcher, combo.action, combo.baseUrl);
      expect(code).not.toContain('JsonSerializer.Deserialize<Expectation>');
      expect(code).not.toContain('Deserialize<Expectation>');
    }
  });

  it('models the JWT request matcher as a typed Jwt object (not a raw fragment)', () => {
    const code = standardToCsharp(baseMatcher({
      jwt: { header: 'x-auth', scheme: 'Token', claims: 'sub=user1', issuer: 'iss', audience: 'aud', algorithm: 'RS256' },
    }), {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    }, BASE_URL);
    expect(code).toContain('Jwt = new Jwt');
    expect(code).toContain('Header = "x-auth"');
    expect(code).toContain('Claims = new Dictionary<string, string>');
    expect(code).toContain('["sub"] = "user1"');
    expect(code).toContain('Algorithm = "RS256"');
    expect(code).not.toContain('Deserialize<Jwt>');
  });

  it('models an ALL_OF body via typed Body factories', () => {
    const code = standardToCsharp(baseMatcher({
      bodyMatcherType: 'allOf',
      bodyAllOf: [{ type: 'xpath', value: '/a/b' }, { type: 'regex', value: '.*x.*' }],
    }), { type: 'static', static: { statusCode: 200, body: '', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' } }, BASE_URL);
    expect(code).toContain('Body = Body.OfAllOf(');
    expect(code).toContain('Body.OfXPath("/a/b")');
    expect(code).toContain('Body.OfRegex(".*x.*")');
  });

  it('escapes C# string literals (quotes, backslashes, newlines)', () => {
    const code = standardToCsharp(baseMatcher({ path: '/a"b\\c' }), {
      type: 'static',
      static: { statusCode: 200, body: 'line1\nline2 "q"', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    }, BASE_URL);
    expect(code).toContain('Path = "/a\\"b\\\\c"');
    expect(code).toContain('Body = "line1\\nline2 \\"q\\""');
  });

  it('defaults the port to 443 for https and 1080 on a parse failure', () => {
    const action: StandardActionPayload = {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    };
    expect(standardToCsharp(baseMatcher(), action, 'https://mock.example.com')).toContain('new MockServerClient("mock.example.com", 443)');
    expect(standardToCsharp(baseMatcher(), action, 'not a url')).toContain('new MockServerClient("localhost", 1080)');
  });
});

describe('standardToCsharp — edit-only passthrough features', () => {
  // These keys never appear on the fresh-compose path; they arrive via an edit
  // overlay (`editOriginal`) and must still be covered by the emitter.
  const editCase: StandardActionPayload = {
    type: 'static',
    static: { statusCode: 200, body: '', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    editActionModeled: false, // preserve the original's action family (sequence + LLM)
    editOriginal: {
      httpRequest: { path: '/api', method: 'GET' },
      namespace: 'tenant-a',
      httpResponses: [
        { statusCode: 200, body: 'primary' },
        { statusCode: 500, body: 'fallback' },
      ],
      responseMode: 'SEQUENTIAL',
      httpLlmResponse: { provider: 'openai', model: 'gpt-4o' },
    },
  };

  it('covers namespace, response sequences, response mode and preserved LLM', () => {
    const code = standardToCsharp(baseMatcher(), editCase, BASE_URL);
    expect(code).toContain('Namespace = "tenant-a"');
    expect(code).toContain('HttpResponses = new List<HttpResponse>');
    expect(code).toContain('ResponseMode = ResponseMode.SEQUENTIAL');
    // LLM is an edit-only action the composer never authors — preserved verbatim
    // into its typed property (never a whole-Expectation blob).
    expect(code).toContain('HttpLlmResponse = JsonSerializer.Deserialize<HttpLlmResponse>(');
    expect(code).not.toContain('Deserialize<Expectation>');
  });
});
