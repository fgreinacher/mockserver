/**
 * GraphQL operation awareness for captured traffic.
 *
 * A GraphQL request is an opaque `POST /graphql` everywhere in the dashboard:
 * all the signal — the operation name and whether it reads or writes — lives
 * inside the body. This module extracts that signal, so the Traffic list, the
 * log rows and the `operation:` search operator can talk about `GetUser` and
 * `CreateOrder` instead of about `/graphql`.
 *
 * ## What counts as GraphQL
 *
 * The parse is a heuristic over a captured body, so it is deliberately strict —
 * a false positive would stamp a GraphQL badge on ordinary JSON traffic. ALL of
 * the following must hold:
 *
 * 1. **Content type** (only checked when the caller supplies request headers):
 *    a `Content-Type` header, if present, must mention `json` or `graphql`.
 *    GraphQL over HTTP is only ever sent as `application/json`,
 *    `application/graphql` or `application/graphql+json`; an absent header is
 *    allowed, anything else (`multipart/form-data`, `application/octet-stream`,
 *    `image/png`, …) is rejected outright.
 * 2. **Body shape**: the body decodes either to a JSON object with a **string**
 *    `query` member (the GraphQL-over-HTTP POST payload
 *    `{ query, operationName, variables }`), or — when it is not JSON at all —
 *    to raw text (the `application/graphql` document form). An object-valued
 *    `query` (Elasticsearch, Mongo-style query DSLs) is not GraphQL.
 * 3. **Parseable document**: that candidate text must look like a GraphQL
 *    executable document — see {@link looksLikeGraphqlDocument}. `"SELECT * FROM
 *    users"`, `"widgets"` and `"{}"` all fail; crucially so does anything that
 *    parses as JSON, which is what stops a nested JSON string in a `query` field
 *    being read as a GraphQL shorthand selection set.
 * 4. **An operation definition** must actually be found by the top-level scanner.
 *
 * ## Degradation and bounds
 *
 * Parsing is client-side only — MockServer does not push a parsed operation name
 * on the log event — so a body the dashboard never receives in full cannot be
 * analysed. Every such case degrades to `null` (no badge, no `operation:` match)
 * and NEVER to a throw or a blank row: BINARY / compressed / streamed bodies
 * (which arrive base64-encoded, if at all) are not decoded, and a body over
 * {@link MAX_BODY_CHARS} is not analysed at all. The document scanner is bounded
 * in input length, in the number of operations it will collect, and is written
 * as a single forward pass with no backtracking regexes, so a malformed,
 * enormous or binary body can neither hang it nor blow the stack.
 */

import { registerFilterField, getFilterField } from './filterDSL';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type GraphqlOperationType = 'query' | 'mutation' | 'subscription';

export interface GraphqlOperation {
  /** Operation name, or `null` for an anonymous operation. */
  operationName: string | null;
  /** Operation type, or `null` when the document declared none. */
  operationType: GraphqlOperationType | null;
}

// ---------------------------------------------------------------------------
// Bounds — every one of these exists to keep a hostile or huge body cheap
// ---------------------------------------------------------------------------

/** Bodies larger than this are not analysed at all (they degrade to `null`). */
const MAX_BODY_CHARS = 256 * 1024;

/**
 * Only this much of a document is scanned for its operation definitions. An
 * executable document declares its operations at the top level, so the leading
 * slice is where they are; a document whose first operation is further in than
 * this is pathological and degrades to `null`.
 */
const MAX_SCAN_CHARS = 8 * 1024;

/** Cap on operations collected from one document (a name lookup needs few). */
const MAX_OPERATIONS = 16;

/** How far past the opening brace we look for a field name (emptiness check). */
const SELECTION_PROBE_CHARS = 512;

// ---------------------------------------------------------------------------
// Small shared helpers
// ---------------------------------------------------------------------------

function asObject(v: unknown): Record<string, unknown> | undefined {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
}

function tryParseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function parsesAsJson(text: string): boolean {
  try {
    JSON.parse(text);
    return true;
  } catch {
    return false;
  }
}

/**
 * Read a header value from MockServer's array (`[{name,values}]`) or object
 * (`{name:[values]}`) header shape, case-insensitively. Local to this module
 * because it reads `Content-Type`; the `Host` header has a single shared
 * extraction (`filterDSL.hostFromHeaders` / `llmTraffic.getHeaderValue`) that
 * the host facet and the `host:` operator both go through.
 */
function headerValue(headers: unknown, name: string): string | undefined {
  const wanted = name.toLowerCase();
  if (Array.isArray(headers)) {
    for (const h of headers) {
      const o = asObject(h);
      const n = o?.['name'];
      const values = o?.['values'];
      if (typeof n === 'string' && n.toLowerCase() === wanted && Array.isArray(values) && values.length > 0) {
        return String(values[0]);
      }
    }
    return undefined;
  }
  const o = asObject(headers);
  if (!o) return undefined;
  for (const [k, v] of Object.entries(o)) {
    if (k.toLowerCase() !== wanted) continue;
    if (typeof v === 'string') return v;
    if (Array.isArray(v) && v.length > 0) return String(v[0]);
  }
  return undefined;
}

// ---------------------------------------------------------------------------
// Body decoding — bounded, never throws, never base64-decodes
// ---------------------------------------------------------------------------

/**
 * Raw text of a MockServer body, when it carries one as text. BINARY bodies
 * (`base64Bytes`) are deliberately NOT decoded: a base64 blob is either a
 * compressed/streamed body we cannot meaningfully parse, or a genuinely binary
 * one, and decoding megabytes of it per row to discover that is exactly the cost
 * this module must not pay.
 */
function rawBodyText(body: unknown): string | undefined {
  if (typeof body === 'string') return body;
  const o = asObject(body);
  if (!o) return undefined;
  if (typeof o['string'] === 'string') return o['string'];
  if (typeof o['json'] === 'string') return o['json'];
  return undefined;
}

/**
 * Decode a MockServer body into the two forms this parser can use: a JSON
 * `payload` (for the `{ query, operationName }` POST shape) and the raw `text`
 * (for the `application/graphql` document shape). Either may be absent.
 */
function decodeBody(body: unknown): { payload: Record<string, unknown> | undefined; text: string | undefined } {
  const text = rawBodyText(body);
  if (text !== undefined) {
    if (text.length > MAX_BODY_CHARS) return { payload: undefined, text: undefined };
    return { payload: asObject(tryParseJson(text)), text };
  }
  const o = asObject(body);
  if (!o) return { payload: undefined, text: undefined };
  // `{ type: 'JSON', json: {...} }` — already-parsed JSON envelope.
  if ('json' in o) return { payload: asObject(o['json']), text: undefined };
  // Any other typed envelope (BINARY, PARAMETERS, …) carries no text for us.
  if ('type' in o) return { payload: undefined, text: undefined };
  // A plain, already-parsed body object.
  return { payload: o, text: undefined };
}

// ---------------------------------------------------------------------------
// Document recognition + scanning
// ---------------------------------------------------------------------------

/** Index of the first character that is not whitespace, a comma, or a comment. */
function firstSignificantIndex(doc: string): number {
  let i = 0;
  const n = doc.length;
  while (i < n) {
    const c = doc[i]!;
    if (c === '#') {
      const nl = doc.indexOf('\n', i);
      i = nl < 0 ? n : nl + 1;
      continue;
    }
    // Commas are insignificant in GraphQL, exactly like whitespace.
    if (c === ',' || /\s/.test(c)) {
      i++;
      continue;
    }
    return i;
  }
  return n;
}

const DEFINITION_KEYWORD = /^(query|mutation|subscription|fragment)\b/;

/**
 * True when `doc` plausibly is a GraphQL executable document. This is the guard
 * that keeps ordinary JSON with a `query` key from being read as GraphQL:
 *
 * - it must start with an operation/fragment keyword or with `{` (the anonymous
 *   shorthand form) — which rejects `SELECT * FROM users`, `widgets`, `*`;
 * - it must contain a selection set with at least one field name — which rejects
 *   `query`, `{}` and `{   }`;
 * - it must NOT itself parse as JSON — which rejects `{"filter":"x"}`, the one
 *   realistic string that would otherwise pass as a shorthand selection set.
 */
export function looksLikeGraphqlDocument(doc: string): boolean {
  if (doc.length === 0 || doc.length > MAX_BODY_CHARS) return false;
  const start = firstSignificantIndex(doc);
  if (start >= doc.length) return false;
  const head = doc.slice(start, start + 32);
  const shorthand = head.startsWith('{');
  if (!shorthand && !DEFINITION_KEYWORD.test(head)) return false;
  const brace = doc.indexOf('{', start);
  if (brace < 0) return false;
  if (!/[_A-Za-z]/.test(doc.slice(brace + 1, brace + 1 + SELECTION_PROBE_CHARS))) return false;
  if (shorthand && parsesAsJson(doc)) return false;
  return true;
}

/** Skip a `"…"` or `"""…"""` string literal; always advances past `start`. */
function skipString(doc: string, start: number): number {
  if (doc.startsWith('"""', start)) {
    const end = doc.indexOf('"""', start + 3);
    return end < 0 ? doc.length : end + 3;
  }
  let i = start + 1;
  while (i < doc.length) {
    const c = doc[i]!;
    if (c === '\\') {
      i += 2;
      continue;
    }
    if (c === '"') return i + 1;
    i++;
  }
  return doc.length;
}

// Sticky (`y`) so each match is anchored at an explicit index — this keeps the
// scan a single O(n) forward pass instead of re-slicing the document per token.
const WORD = /[_A-Za-z][_0-9A-Za-z]*/y;
const LEADING_NAME = /[\s,]*([_A-Za-z][_0-9A-Za-z]*)/y;

/**
 * Collect the top-level operation definitions of a document, in source order.
 *
 * Brace depth is tracked so that a field happening to be called `query` inside a
 * selection set is not mistaken for an operation keyword, and string literals
 * are skipped so braces or `#` inside them cannot desynchronise the depth. A
 * `fragment` definition is recognised only to stop its selection set being read
 * as an anonymous shorthand query.
 */
function findOperations(document: string): GraphqlOperation[] {
  const doc = document.slice(0, MAX_SCAN_CHARS);
  const ops: GraphqlOperation[] = [];
  const n = doc.length;
  let i = 0;
  let depth = 0;
  let pendingFragment = false;

  while (i < n && ops.length < MAX_OPERATIONS) {
    const c = doc[i]!;
    if (c === '"') {
      i = skipString(doc, i);
      continue;
    }
    if (c === '#') {
      const nl = doc.indexOf('\n', i);
      i = nl < 0 ? n : nl + 1;
      continue;
    }
    if (c === '{') {
      // A top-level selection set that is not a fragment's and does not belong
      // to a named operation is the anonymous shorthand form: `{ user { id } }`.
      if (depth === 0 && !pendingFragment && ops.length === 0) {
        ops.push({ operationName: null, operationType: 'query' });
      }
      pendingFragment = false;
      depth++;
      i++;
      continue;
    }
    if (c === '}') {
      if (depth > 0) depth--;
      i++;
      continue;
    }
    if (depth > 0) {
      i++;
      continue;
    }
    WORD.lastIndex = i;
    const word = WORD.exec(doc)?.[0];
    if (word === undefined) {
      i++;
      continue;
    }
    i += word.length;
    if (word === 'fragment') {
      pendingFragment = true;
      continue;
    }
    if (word === 'query' || word === 'mutation' || word === 'subscription') {
      LEADING_NAME.lastIndex = i;
      const name = LEADING_NAME.exec(doc)?.[1];
      ops.push({ operationName: name ?? null, operationType: word });
    }
  }

  return ops;
}

/**
 * Pick the operation a request is "about": the one the payload's
 * `operationName` selects when it names one (that is the operation the server
 * will execute out of a multi-operation document), otherwise the first.
 */
function pickOperation(ops: GraphqlOperation[], declaredName: string | undefined): GraphqlOperation | null {
  if (ops.length === 0) return null;
  if (declaredName) {
    const named = ops.find((o) => o.operationName === declaredName);
    // A declared name that is not in the document still names the operation —
    // the type is then taken from the first definition rather than invented.
    return named ?? { operationName: declaredName, operationType: ops[0]!.operationType };
  }
  return ops[0]!;
}

// ---------------------------------------------------------------------------
// Public parsing API
// ---------------------------------------------------------------------------

/**
 * Extract the GraphQL operation from a captured request body, or `null` when the
 * body is not recognisably GraphQL. Accepts a raw string, MockServer's
 * `{ type, json }` / `{ type, string }` envelopes, and an already-parsed body
 * object. Never throws.
 */
export function parseGraphqlBody(body: unknown): GraphqlOperation | null {
  const { payload, text } = decodeBody(body);

  // GraphQL-over-HTTP POST payload: `{ query, operationName?, variables? }`.
  if (payload) {
    const query = payload['query'];
    if (typeof query !== 'string') return null;
    if (!looksLikeGraphqlDocument(query)) return null;
    const declared = payload['operationName'];
    return pickOperation(
      findOperations(query),
      typeof declared === 'string' && declared.length > 0 ? declared : undefined,
    );
  }

  // `application/graphql`: the body IS the document. Only reachable when the
  // text did not parse as JSON, so a JSON body can never take this branch.
  if (text !== undefined && looksLikeGraphqlDocument(text)) {
    return pickOperation(findOperations(text), undefined);
  }

  return null;
}

/** Content types GraphQL is actually sent with; anything else is not GraphQL. */
function contentTypeAllows(contentType: string | undefined): boolean {
  if (contentType === undefined) return true;
  return /json|graphql/i.test(contentType);
}

/**
 * Extract the GraphQL operation from a captured `httpRequest` object, applying
 * the `Content-Type` gate as well as the body checks. Never throws.
 */
export function graphqlOperationOfRequest(request: unknown): GraphqlOperation | null {
  const req = asObject(request);
  if (!req) return null;
  if (!contentTypeAllows(headerValue(req['headers'], 'content-type'))) return null;
  return parseGraphqlBody(req['body']);
}

/**
 * Per-request memo for {@link graphqlOperationOf}.
 *
 * The `operation:` resolver runs once per row on every keystroke and on every
 * ~1/sec WebSocket push, and the traffic row renders the result — so the parse
 * must be paid at most once per request AND must hand back a reference-stable
 * object, or `React.memo` on the row would see a new prop every render. The
 * store's `reconcileByKey` preserves the `httpRequest` object reference while
 * its content is unchanged, so keying on that reference gives both.
 *
 * This memoises the whole parse (decode + document scan), which subsumes
 * `filterDSL.decodedRequestBody`'s decode cache for this path: the document scan
 * is the part worth caching, and the parse needs the raw text and the headers,
 * not just the decoded JSON. The result is boxed so a cached `null` still hits.
 */
const operationCache = new WeakMap<object, { op: GraphqlOperation | null }>();

/**
 * Memoised GraphQL operation for a captured item value (`{ httpRequest, … }`),
 * or `null` when the request is not GraphQL. Returns the SAME object for the
 * same request reference, so it is safe to pass straight into a memoised row.
 */
export function graphqlOperationOf(value: Record<string, unknown>): GraphqlOperation | null {
  const req = asObject(value['httpRequest']);
  if (!req) return null;
  const hit = operationCache.get(req);
  if (hit) return hit.op;
  const op = graphqlOperationOfRequest(req);
  operationCache.set(req, { op });
  return op;
}

/** Display label for an operation: its name, or its type when anonymous. */
export function graphqlOperationLabel(op: GraphqlOperation): string {
  return op.operationName ?? op.operationType ?? 'GraphQL';
}

// ---------------------------------------------------------------------------
// Filter registry wiring
// ---------------------------------------------------------------------------

/**
 * Swap the real document parser into the shared `operation:` filter field.
 *
 * The built-in resolver can only see an `operationName` MEMBER, so it both
 * misses the common case (the name appears only inside the `query` document)
 * and matches any JSON body carrying that key, GraphQL or not. Re-registering
 * the field — the extension point `lib/filterDSL` exposes for exactly this —
 * makes `operation:` genuinely GraphQL-aware without the engine knowing what
 * GraphQL is.
 *
 * Called on import (below), and re-callable by tests after
 * `resetFilterFields()`.
 */
export function registerGraphqlOperationField(): void {
  const field = getFilterField('operation');
  if (!field) return;
  registerFilterField({
    ...field,
    description: 'GraphQL operation name glob (parsed from the request body)',
    resolve: (value) => {
      const op = graphqlOperationOf(value);
      // An anonymous operation has no name to compare against, so it never
      // matches — the same "absent field never matches" rule the engine applies.
      return op?.operationName ? { text: op.operationName } : undefined;
    },
  });
}

registerGraphqlOperationField();
