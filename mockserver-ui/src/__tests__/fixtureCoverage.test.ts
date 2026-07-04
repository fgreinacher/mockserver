/// <reference types="vite/client" />
// Meta-test / coverage gate for the cross-language client round-trip fidelity fixtures.
//
// It asserts that the canonical fixture set at repo-root test-fixtures/expectations/*.json
// collectively EXERCISES every server + composer feature dimension:
//   1. every top-level key in the server Expectation JSON schema (expectation.json),
//   2. every ACTION_FAMILY_KEYS member (the mutually-exclusive action/response slot),
//   3. every StandardActionType (composer action kind) — via its JSON key,
//   4. every BodyMatcherType (composer body matcher) — via its server body `type`.
//
// So when a new server feature / action / body matcher lands, this test fails in CI until a
// fixture covers it — which in turn makes the per-language fidelity tests exercise it. The
// Record<Union, ...> maps below are exhaustiveness-checked by tsc, so adding a new
// StandardActionType / BodyMatcherType also fails the build until mapped and covered.
//
// Fixtures + schema are loaded with Vite's import.meta.glob (eager) so this needs no Node
// fs types and works identically under `vitest run` and the `tsc --noEmit` typecheck gate.

import { describe, it, expect } from 'vitest';
import {
  ACTION_FAMILY_KEYS,
  type StandardActionType,
  type BodyMatcherType,
} from '../lib/standardCodegen';

const MANIFEST = 'known-gaps.json';

// Eager glob of every fixture JSON (each module's default export is the parsed object).
const fixtureModules = import.meta.glob('../../../test-fixtures/expectations/*.json', {
  eager: true,
  import: 'default',
}) as Record<string, Record<string, unknown>>;

// The authoritative server Expectation schema (single file).
const schemaModules = import.meta.glob(
  '../../../mockserver/mockserver-core/src/main/resources/org/mockserver/model/schema/expectation.json',
  { eager: true, import: 'default' },
) as Record<string, { properties: Record<string, unknown> }>;

const fixtures = Object.entries(fixtureModules)
  .filter(([p]) => !p.endsWith(MANIFEST))
  .sort(([a], [b]) => a.localeCompare(b))
  .map(([p, json]) => ({ name: p.split('/').pop()!, json }));

const schema = Object.values(schemaModules)[0]!;

/** Every top-level key that appears across all fixtures. */
function topLevelKeys(): Set<string> {
  const keys = new Set<string>();
  for (const { json } of fixtures) for (const k of Object.keys(json)) keys.add(k);
  return keys;
}

/** Every value of a `type` property anywhere in the fixture tree (captures body matcher types). */
function collectTypeValues(): Set<string> {
  const out = new Set<string>();
  const walk = (v: unknown): void => {
    if (Array.isArray(v)) {
      v.forEach(walk);
    } else if (v && typeof v === 'object') {
      for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
        if (k === 'type' && typeof val === 'string') out.add(val);
        walk(val);
      }
    }
  };
  fixtures.forEach((f) => walk(f.json));
  return out;
}

// StandardActionType -> the top-level JSON action key it emits (tsc-exhaustive).
const ACTION_TYPE_TO_JSON_KEY: Record<StandardActionType, string> = {
  static: 'httpResponse',
  forward: 'httpForward',
  forward_override: 'httpOverrideForwardedRequest',
  forward_fallback: 'httpForwardWithFallback',
  callback: 'httpResponseClassCallback',
  template: 'httpResponseTemplate',
  error: 'httpError',
  websocket: 'httpWebSocketResponse',
  sse: 'httpSseResponse',
  binary_response: 'binaryResponse',
  dns_response: 'dnsResponse',
  forward_template: 'httpForwardTemplate',
  forward_class_callback: 'httpForwardClassCallback',
  grpc_stream: 'grpcStreamResponse',
};

// BodyMatcherType -> the server body `type` discriminator it emits (tsc-exhaustive).
const BODY_MATCHER_TO_SERVER_TYPE: Record<BodyMatcherType, string> = {
  string: 'STRING',
  json: 'JSON',
  graphql: 'GRAPHQL',
  binary: 'BINARY',
  'json-schema': 'JSON_SCHEMA',
  'json-path': 'JSON_PATH',
  xml: 'XML',
  'xml-schema': 'XML_SCHEMA',
  xpath: 'XPATH',
  regex: 'REGEX',
  allOf: 'ALL_OF',
  parameters: 'PARAMETERS',
  wasm: 'WASM',
};

describe('client fidelity fixtures — coverage gate', () => {
  it('has a non-trivial fixture set and excludes the gap manifest', () => {
    expect(fixtures.length).toBeGreaterThanOrEqual(40);
    expect(fixtures.map((f) => f.name)).not.toContain(MANIFEST);
    expect(schema, 'expectation.json schema not found via glob').toBeTruthy();
  });

  it('exercises every top-level key in the server Expectation schema', () => {
    const schemaKeys = Object.keys(schema.properties);
    const covered = topLevelKeys();
    const missing = schemaKeys.filter((k) => !covered.has(k));
    expect(missing, `server Expectation keys not exercised by any fixture: ${missing.join(', ')}`).toEqual([]);
  });

  it('exercises every ACTION_FAMILY_KEYS member', () => {
    const covered = topLevelKeys();
    const missing = ACTION_FAMILY_KEYS.filter((k) => !covered.has(k));
    expect(missing, `ACTION_FAMILY_KEYS not exercised by any fixture: ${missing.join(', ')}`).toEqual([]);
  });

  it('exercises every StandardActionType (via its JSON action key)', () => {
    const covered = topLevelKeys();
    const missing = Object.entries(ACTION_TYPE_TO_JSON_KEY)
      .filter(([, jsonKey]) => !covered.has(jsonKey))
      .map(([t]) => t);
    expect(missing, `StandardActionType kinds not exercised: ${missing.join(', ')}`).toEqual([]);
  });

  it('exercises every BodyMatcherType (via its server body type)', () => {
    const types = collectTypeValues();
    const missing = Object.entries(BODY_MATCHER_TO_SERVER_TYPE)
      .filter(([, serverType]) => !types.has(serverType))
      .map(([t]) => t);
    expect(missing, `BodyMatcherType variants not exercised: ${missing.join(', ')}`).toEqual([]);
  });
});
