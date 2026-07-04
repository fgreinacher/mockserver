/**
 * Tests for the TYPED Ruby client-library emitter ({@link ./ruby.ts}).
 *
 * The emitter builds a `MockServer::Expectation` from keyword-argument model
 * constructors (no whole-payload `Expectation.from_hash` on an embedded JSON
 * string) and registers it with the gem's canonical `client.upsert(expectation)`.
 *
 * Two guards:
 *  1. Byte-identity against the committed golden ({@link ./__fixtures__/rubyGolden.ts}),
 *     regenerated from the emitter over the shared combos — pins output against drift.
 *  2. Structural assertions that the output is typed construction, not a JSON blob.
 *
 * Semantic equivalence to `buildExpectationJson` (running the emitted Ruby against
 * the real gem and deep-comparing `expectation.to_h`) is proven out-of-band by the
 * equivalence harness; it is not re-run here to keep this suite Ruby-toolchain-free.
 */
import { describe, it, expect } from 'vitest';
import { combos } from './extractParityCases';
import { standardToRuby } from './ruby';
import { rubyGolden } from './__fixtures__/rubyGolden';
import type { StandardMatcher, StandardActionPayload } from '../standardCodegen';

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
    secure: false, priority: 0, times: 0, ...overrides,
  };
}
const BASE_URL = 'http://localhost:1080';

describe('standardToRuby — typed construction', () => {
  for (const combo of combos) {
    it(`matches the golden for ${combo.name}`, () => {
      const actual = standardToRuby(combo.matcher, combo.action, combo.baseUrl);
      expect(actual).toBe(rubyGolden[combo.name]);
    });
  }

  it('registers via the canonical client.upsert idiom, not from_hash on JSON', () => {
    const code = standardToRuby(baseMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: 'hi', contentType: 'text/plain', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    }, BASE_URL);
    expect(code).toContain("require 'mockserver-client'");
    expect(code).toContain("MockServer::Client.new('localhost', 1080)");
    expect(code).toContain('client.upsert(');
    expect(code).toContain('MockServer::Expectation.new(');
    // typed leaves, not an embedded JSON payload
    expect(code).toContain('MockServer::HttpResponse.new(');
    expect(code).toContain('MockServer::KeyToMultiValue.new(');
    // NO whole-payload from_hash / heredoc / JSON.parse
    expect(code).not.toContain('from_hash');
    expect(code).not.toContain("<<~'JSON'");
    expect(code).not.toContain('JSON.parse');
  });

  it('builds a typed JSON body matcher with a Ruby hash literal (not a JSON string)', () => {
    const code = standardToRuby(baseMatcher({ body: '{"ok":true,"n":3}', bodyMatcherType: 'json', jsonMatchType: 'STRICT' }), {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    }, BASE_URL);
    expect(code).toContain('MockServer::Body.new(');
    expect(code).toContain('type: "JSON"');
    expect(code).toContain('match_type: "STRICT"');
    // JSON booleans render as native Ruby literals inside a Ruby hash
    expect(code).toContain('"ok" => true');
    expect(code).toContain('"n" => 3');
  });

  it('builds a typed Jwt matcher with keyword args', () => {
    const code = standardToRuby(baseMatcher({
      jwt: { header: 'x-auth', scheme: 'Token', claims: 'sub=user1', issuer: 'iss', audience: 'aud', algorithm: 'RS256' },
    }), {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    }, BASE_URL);
    expect(code).toContain('MockServer::Jwt.new(');
    expect(code).toContain('algorithm: "RS256"');
    expect(code).toContain('"sub" => "user1"');
  });

  it('falls back to a raw requestOverride hash for the forward-override model gap', () => {
    const action: StandardActionPayload = {
      type: 'forward_override',
      forwardOverride: {
        overrideMethod: 'PATCH', overrideHost: '', overrideScheme: 'HTTPS',
        overridePath: '/v2', overrideQueryString: '', overrideHeaders: '', overrideBody: '',
      },
    };
    const code = standardToRuby(baseMatcher(), action, BASE_URL);
    expect(code).toContain('http_override_forwarded_request: {');
    expect(code).toContain('"requestOverride" =>');
  });

  it('emits typed DnsRecord / DnsResponse and a DNS request matcher', () => {
    const code = standardToRuby(
      baseMatcher({ dns: { dnsName: 'example.com', dnsType: 'A', dnsClass: 'IN' } }),
      { type: 'dns_response', dnsResponse: { responseCode: 'NOERROR', answerRecords: '[{"name":"example.com","type":"A","ttl":300,"value":"1.2.3.4"}]' } },
      BASE_URL,
    );
    expect(code).toContain('MockServer::HttpRequest.new(');
    expect(code).toContain('dns_name: "example.com"');
    expect(code).toContain('MockServer::DnsResponse.new(');
    expect(code).toContain('MockServer::DnsRecord.new(');
  });

  it('derives host/port from an https base URL', () => {
    const code = standardToRuby(baseMatcher(), {
      type: 'static',
      static: { statusCode: 200, body: '', contentType: '', bodyFromFile: false, filePath: '', fileTemplateType: '' },
    }, 'https://mock.example.com');
    expect(code).toContain("MockServer::Client.new('mock.example.com', 443)");
  });
});
