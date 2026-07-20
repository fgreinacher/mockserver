/**
 * Asymmetric-default booleans — round-trip fidelity across every language tab.
 *
 * This guards a DEFECT CLASS, not one field. A non-optional boolean guarded on
 * truthiness (`if (x.flag)`) silently drops `false`. That is only cosmetic when
 * the server treats an ABSENT field the same as `false`. For these fields it does
 * not — absent and `false` mean OPPOSITE things:
 *
 *   closeConnection
 *     - HttpSseResponseActionHandler:       `== null || value`  → absent = CLOSE
 *     - HttpWebSocketResponseActionHandler: `== null || value`  → absent = CLOSE
 *     - GrpcStreamResponseActionHandler:    `!= null && value`  → absent = DON'T CLOSE
 *   fallbackOnTimeout
 *     - HttpForwardWithFallbackActionHandler: `== null || value` → absent = FALL BACK
 *
 * So dropping an explicit `false` generates code that does the OPPOSITE of what
 * the user selected in the dashboard. No schema declares a default, so the
 * handlers above are the only authority.
 *
 * The `false` case is the whole point of this file. A `true` fixture passes
 * identically with or without the fix — which is exactly why this shipped twice:
 * the SSE parity fixture used `closeConnection: true` and the forward-fallback
 * parity fixture uses `fallbackOnTimeout: true`. Every assertion below is driven
 * from a `false` fixture; `true` is asserted only to stop a "fix" that hardcodes
 * the literal.
 *
 * NOT every non-optional boolean belongs here — but `secure` is no longer the
 * counter-example it once was, and the reasoning is worth keeping because it shows
 * how a control's LABEL decides what its emitter must do.
 *
 * `secure` used to be excluded on the grounds that its switch was labelled "HTTPS
 * only", so with only two states OFF genuinely meant "match either" and omitting the
 * field was correct — emitting `secure: false` would have wrongly narrowed it to
 * "HTTP only". That reasoning was sound for a two-state switch. The control is now
 * tri-state (Any / HTTPS only / HTTP only), so `false` is a state the user has to
 * choose deliberately and it means HTTP only, exactly as BooleanMatcher reads it.
 * Omitting it there is the bug, not the fix: it silently widens an HTTP-only matcher
 * into a wildcard. `secure` is covered by composerSecureTriState.test.ts rather than
 * here, because its three states need round-trip coverage this file does not model.
 */
import { describe, it, expect } from 'vitest';
import {
  standardToJava,
  standardToJson,
  standardToCurl,
  standardToNode,
  standardToPython,
  standardToGo,
  standardToCsharp,
  standardToRuby,
  standardToRust,
  type StandardMatcher,
  type StandardActionPayload,
} from '../standardCodegen';

const URL = 'http://localhost:1080';

function m(): StandardMatcher {
  return {
    id: '', method: 'GET', path: '/api', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
    priority: 0, times: 0,
  } as unknown as StandardMatcher;
}

/** The three streaming actions that carry closeConnection, parameterised by value. */
const ACTIONS: Array<{ name: string; build: (close: boolean) => StandardActionPayload }> = [
  {
    name: 'sse',
    build: (closeConnection) => ({
      type: 'sse',
      sse: { statusCode: 200, headers: '', events: [{ event: 'e', data: 'd', id: '', retry: '' }], closeConnection },
    }) as unknown as StandardActionPayload,
  },
  {
    name: 'websocket',
    build: (closeConnection) => ({
      type: 'websocket',
      websocket: { subprotocol: '', messages: 'hi', closeConnection, matchers: [] },
    }) as unknown as StandardActionPayload,
  },
  {
    name: 'grpc_stream',
    build: (closeConnection) => ({
      type: 'grpc_stream',
      grpcStream: { statusName: 'OK', statusMessage: '', headers: '', messages: '{"a":1}', closeConnection },
    }) as unknown as StandardActionPayload,
  },
];

/**
 * Per-language literal for closeConnection at a given value. Each entry is the
 * exact source text that language's client library requires — so a language whose
 * emitter omits the field, or emits the wrong value, fails.
 */
const EXPECTED: Array<{
  lang: string;
  emit: (a: StandardActionPayload) => string;
  literal: (v: boolean) => string;
}> = [
  { lang: 'java', emit: (a) => standardToJava(m(), a), literal: (v) => `.withCloseConnection(${v})` },
  { lang: 'node', emit: (a) => standardToNode(m(), a, URL), literal: (v) => `"closeConnection": ${v}` },
  { lang: 'python', emit: (a) => standardToPython(m(), a, URL), literal: (v) => `close_connection=${v ? 'True' : 'False'}` },
  { lang: 'go', emit: (a) => standardToGo(m(), a, URL), literal: (v) => `CloseConnection: ptr(${v})` },
  { lang: 'csharp', emit: (a) => standardToCsharp(m(), a, URL), literal: (v) => `CloseConnection = ${v}` },
  { lang: 'ruby', emit: (a) => standardToRuby(m(), a, URL), literal: (v) => `close_connection: ${v}` },
  { lang: 'rust', emit: (a) => standardToRust(m(), a, URL), literal: (v) => `.close_connection(${v})` },
  { lang: 'json', emit: (a) => standardToJson(m(), a), literal: (v) => `"closeConnection": ${v}` },
  { lang: 'curl', emit: (a) => standardToCurl(m(), a, URL), literal: (v) => `"closeConnection":${v}` },
];

describe('closeConnection: false survives codegen in every language', () => {
  for (const { name, build } of ACTIONS) {
    for (const { lang, emit, literal } of EXPECTED) {
      it(`${lang} emits an explicit false for ${name}`, () => {
        const code = emit(build(false));
        expect(code).toContain(literal(false));
        // And never silently degrades to the opposite selection.
        expect(code).not.toContain(literal(true));
      });
    }
  }
});

describe('closeConnection: true still emits true in every language', () => {
  for (const { name, build } of ACTIONS) {
    for (const { lang, emit, literal } of EXPECTED) {
      it(`${lang} emits an explicit true for ${name}`, () => {
        const code = emit(build(true));
        expect(code).toContain(literal(true));
        expect(code).not.toContain(literal(false));
      });
    }
  }
});

// ---------------------------------------------------------------------------
// fallbackOnTimeout — the second instance of the same defect.
// ---------------------------------------------------------------------------

const forwardFallback = (fallbackOnTimeout: boolean): StandardActionPayload => ({
  type: 'forward_fallback',
  forwardFallback: {
    scheme: 'HTTP', host: 'primary.example.com', port: 80,
    fallbackStatusCode: 503, fallbackBody: 'unavailable',
    fallbackOnStatusCodes: '500,502,503', fallbackOnTimeout,
  },
}) as unknown as StandardActionPayload;

const FALLBACK_EXPECTED: Array<{
  lang: string;
  emit: (a: StandardActionPayload) => string;
  literal: (v: boolean) => string;
}> = [
  { lang: 'java', emit: (a) => standardToJava(m(), a), literal: (v) => `.withFallbackOnTimeout(${v})` },
  { lang: 'node', emit: (a) => standardToNode(m(), a, URL), literal: (v) => `"fallbackOnTimeout": ${v}` },
  { lang: 'python', emit: (a) => standardToPython(m(), a, URL), literal: (v) => `fallback_on_timeout=${v ? 'True' : 'False'}` },
  { lang: 'go', emit: (a) => standardToGo(m(), a, URL), literal: (v) => `FallbackOnTimeout: ptr(${v})` },
  { lang: 'csharp', emit: (a) => standardToCsharp(m(), a, URL), literal: (v) => `FallbackOnTimeout = ${v}` },
  { lang: 'ruby', emit: (a) => standardToRuby(m(), a, URL), literal: (v) => `fallback_on_timeout: ${v}` },
  { lang: 'rust', emit: (a) => standardToRust(m(), a, URL), literal: (v) => `.fallback_on_timeout(${v})` },
  { lang: 'json', emit: (a) => standardToJson(m(), a), literal: (v) => `"fallbackOnTimeout": ${v}` },
  { lang: 'curl', emit: (a) => standardToCurl(m(), a, URL), literal: (v) => `"fallbackOnTimeout":${v}` },
];

describe('fallbackOnTimeout: false survives codegen in every language', () => {
  for (const { lang, emit, literal } of FALLBACK_EXPECTED) {
    it(`${lang} emits an explicit false`, () => {
      const code = emit(forwardFallback(false));
      expect(code).toContain(literal(false));
      expect(code).not.toContain(literal(true));
    });
  }
});

describe('fallbackOnTimeout: true still emits true in every language', () => {
  for (const { lang, emit, literal } of FALLBACK_EXPECTED) {
    it(`${lang} emits an explicit true`, () => {
      const code = emit(forwardFallback(true));
      expect(code).toContain(literal(true));
      expect(code).not.toContain(literal(false));
    });
  }
});
