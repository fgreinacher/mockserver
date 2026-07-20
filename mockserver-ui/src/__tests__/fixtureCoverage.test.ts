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

// Every schema file, so the gate can reach BELOW the top level. The original gate compared only
// depth-0 keys, which is why nested fields (templateType, primary, graphqlSubscriptionFilter) were
// expressible by zero clients while known-gaps.json read clean — no fixture ever probed them.
//
// Globbed wholesale and then filtered by ACTION_FAMILY_KEYS rather than named in a brace list: a
// hand-maintained list of schemas sitting next to the check that reads it is the exact shape of
// blind spot this gate exists to remove — it would silently stop covering any action added later.
const allSchemaModules = import.meta.glob(
  '../../../mockserver/mockserver-core/src/main/resources/org/mockserver/model/schema/*.json',
  { eager: true, import: 'default' },
) as Record<string, { properties?: Record<string, unknown> }>;

const schemaNameOf = (path: string) => path.split('/').pop()!.replace('.json', '');

const actionSchemaModules = Object.fromEntries(
  Object.entries(allSchemaModules).filter(
    ([path, schema]) =>
      ACTION_FAMILY_KEYS.includes(schemaNameOf(path)) &&
      // a handful of action-family keys are expectation-level constructs (httpResponses,
      // responseMode, responseWeights, switchAfter) with no schema file of their own
      schema && typeof schema.properties === 'object',
  ),
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

  // Depth-1 gate: for each action schema, every property it declares must appear as a key
  // somewhere under the matching action key in some fixture. Catches the class of gap where a
  // field exists server-side, no fixture sets it, so no client is ever forced to express it.
  it('exercises every property of every action schema, not just the top-level key', () => {
    const gaps: string[] = [];
    for (const [path, actionSchema] of Object.entries(actionSchemaModules)) {
      const actionKey = schemaNameOf(path);
      const seen = new Set<string>();
      for (const { json } of fixtures) {
        const action = json[actionKey];
        if (action && typeof action === 'object') {
          for (const k of Object.keys(action as Record<string, unknown>)) seen.add(k);
        }
      }
      // an action with no fixture at all is already caught by the ACTION_FAMILY_KEYS gate above
      if (seen.size === 0) continue;
      for (const property of Object.keys(actionSchema.properties)) {
        if (!seen.has(property)) gaps.push(`${actionKey}.${property}`);
      }
    }

    expect(gaps, `action schema properties not exercised by any fixture: ${gaps.join(', ')}`).toEqual([]);
  });

  // Booleans are the sharpest instance of the depth-0 blind spot: a fixture that only ever sets a
  // flag to `true` cannot catch a truthiness guard that silently drops `false` — which is exactly
  // how `closeConnection: false` was dropped by the dashboard codegen for all 8 languages, masked
  // by an SSE fixture that only ever used `true`.
  //
  // Scoped to booleans declared directly by an action schema. Those are the ones a client or the
  // codegen must round-trip faithfully in both directions; widening this to every boolean anywhere
  // in the tree is a worthwhile but much larger fixture-authoring job (see report).
  it('exercises both true and false for every action-level boolean', () => {
    const oneSided: string[] = [];
    for (const [path, actionSchema] of Object.entries(actionSchemaModules)) {
      const actionKey = schemaNameOf(path);
      const booleanProps = Object.entries(actionSchema.properties)
        .filter(([, spec]) => (spec as { type?: string }).type === 'boolean')
        .map(([name]) => name);

      for (const prop of booleanProps) {
        const seen = new Set<boolean>();
        for (const { json } of fixtures) {
          const action = json[actionKey] as Record<string, unknown> | undefined;
          if (action && typeof action === 'object' && typeof action[prop] === 'boolean') {
            seen.add(action[prop] as boolean);
          }
        }
        if (seen.size === 1) oneSided.push(`${actionKey}.${prop} (only ${[...seen][0]})`);
      }
    }

    expect(
      oneSided,
      `action-level booleans exercised with only one value — a truthiness bug in the unexercised direction would be invisible: ${oneSided.join(', ')}`,
    ).toEqual([]);
  });

  it('exercises every BodyMatcherType (via its server body type)', () => {
    const types = collectTypeValues();
    const missing = Object.entries(BODY_MATCHER_TO_SERVER_TYPE)
      .filter(([, serverType]) => !types.has(serverType))
      .map(([t]) => t);
    expect(missing, `BodyMatcherType variants not exercised: ${missing.join(', ')}`).toEqual([]);
  });
});
