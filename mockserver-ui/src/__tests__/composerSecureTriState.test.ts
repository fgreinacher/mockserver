/**
 * `secure` is a tri-state request matcher, and the only fixture that proves it is `false`.
 *
 * On the server `secure` is a `BooleanMatcher`: `true` matches HTTPS only, `false` matches HTTP
 * only, and an absent field matches either. The Composer previously modelled it as a two-state
 * switch and emitted it only when truthy, so an expectation carrying `secure: false` loaded as OFF
 * and was re-emitted with the field ABSENT — silently widening an HTTP-only matcher into a
 * wildcard that also matches HTTPS. Merely opening such an expectation and saving it changed what
 * it matched.
 *
 * **Fixture discipline.** Every assertion here uses `false`. `true` and absent both round-trip
 * correctly with or without the fix, so a suite built on them passes either way and proves nothing
 * — the same trap that let the `closeConnection` defect survive (see
 * composerAsymmetricBooleanLoad.test.tsx).
 *
 * **Per-language literals, not a shape check.** The shared builder and the nine language emitters
 * fail independently: with the shared builder fixed but the emitters untouched, java/json/curl/
 * node/ruby carry `secure: false` while python/go/csharp/rust drop it. A golden-file or
 * "field is present somewhere" assertion cannot separate those, so each language asserts the exact
 * literal it should produce.
 */
import { describe, it, expect } from 'vitest';
import { matcherFromExpectation } from '../components/ComposerView';
import {
  buildExpectationJson,
  standardToNode, standardToPython, standardToGo, standardToCsharp,
  standardToRuby, standardToRust, standardToJava, standardToCurl,
  type StandardActionPayload, type StandardMatcher,
} from '../lib/standardCodegen';

const action: StandardActionPayload = {
  type: 'static',
  static: { statusCode: 200, body: '', contentType: 'application/json' },
};

const base: StandardMatcher = {
  id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
  pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
  priority: 0, times: 0,
};

function roundTrip(secure: boolean | undefined) {
  const httpRequest: Record<string, unknown> = { method: 'GET', path: '/api' };
  if (secure !== undefined) httpRequest['secure'] = secure;
  const loaded = matcherFromExpectation({ value: { httpRequest } } as never);
  const emitted = buildExpectationJson(loaded, action);
  return { loaded, request: emitted['httpRequest'] as Record<string, unknown> };
}

describe('secure tri-state round-trip', () => {
  it('preserves an explicit HTTP-only matcher through load and re-emit', () => {
    const { loaded, request } = roundTrip(false);
    expect(loaded.secure).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(request, 'secure')).toBe(true);
    expect(request['secure']).toBe(false);
  });

  it('preserves an explicit HTTPS-only matcher', () => {
    const { loaded, request } = roundTrip(true);
    expect(loaded.secure).toBe(true);
    expect(request['secure']).toBe(true);
  });

  it('leaves an absent matcher absent rather than inventing a constraint', () => {
    const { loaded, request } = roundTrip(undefined);
    expect(loaded.secure).toBeUndefined();
    expect(Object.prototype.hasOwnProperty.call(request, 'secure')).toBe(false);
  });

  it('distinguishes HTTP-only from absent', () => {
    // the whole defect in one assertion: these two must not produce the same JSON
    expect(roundTrip(false).request['secure']).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(roundTrip(undefined).request, 'secure')).toBe(false);
  });
});

describe('secure=false reaches every language emitter', () => {
  const httpOnly: StandardMatcher = { ...base, secure: false };
  const url = 'http://localhost:1080';

  type Emitter = (m: StandardMatcher, a: StandardActionPayload, u: string) => string;

  // Each entry is the exact literal that language must emit for an HTTP-only matcher.
  const cases: Array<[string, Emitter, string]> = [
    ['java', standardToJava, '.withSecure(false)'],
    ['curl', standardToCurl, '"secure":false'],
    ['node', standardToNode, '"secure": false'],
    ['python', standardToPython, 'secure=False'],
    ['go', standardToGo, 'Secure(false)'],
    ['csharp', standardToCsharp, 'Secure = false'],
    ['ruby', standardToRuby, 'secure: false'],
    ['rust', standardToRust, '.secure(false)'],
  ];

  it.each(cases)('%s emits the HTTP-only literal', (_lang, emit, literal) => {
    expect(emit(httpOnly, action, url)).toContain(literal);
  });

  it('the JSON tab carries a literal false, not an omission', () => {
    const request = buildExpectationJson(httpOnly, action)['httpRequest'] as Record<string, unknown>;
    expect(request['secure']).toBe(false);
  });

  it('no language emits an HTTPS-only literal for an HTTP-only matcher', () => {
    // guards the inverse mistake: emitting the field but hard-coding `true`
    for (const [lang, emit] of cases) {
      const out = emit(httpOnly, action, url);
      expect(out, `${lang} must not claim HTTPS`).not.toMatch(/secure["'\s]*[:=(]\s*["']?true/i);
      expect(out, `${lang} must not claim HTTPS`).not.toContain('withSecure(true)');
    }
  });
});
