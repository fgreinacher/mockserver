/**
 * Shared filter DSL — one operator vocabulary for every filter surface.
 *
 * Historically `lib/searchMatcher.ts` hard-coded its vocabulary: a
 * `KNOWN_FIELDS` set, a `fieldValue()` switch, and a `matchesOperator()` that
 * special-cased `path` for globbing. Adding a field meant editing three places
 * and every new call site inherited the traffic-shaped vocabulary whether or not
 * it could satisfy it.
 *
 * This module replaces that with a **field registry**: each field declares its
 * name, how to resolve a comparable value from an item, and its match semantics
 * (numeric-comparable / glob-capable / plain exact). Parsing, comparator
 * handling and glob compilation live here once, for all fields.
 *
 * A search term may combine:
 *   - free text — plain case-insensitive substring, or a regular expression when
 *     wrapped in slashes (`/^GET .*\/api/`, optional trailing flags);
 *   - field operators — `field:expr` tokens, e.g.
 *       `status:>=400`         numeric comparison against the response status
 *       `method:POST`          case-insensitive equality
 *       `path:/api/*`          glob (`*` → any run of characters)
 *       `host:*.example.com`   glob against the request `Host` header
 *       `operation:GetUser`    glob against the GraphQL operation name
 * Operators are ANDed with each other and with the free-text portion. A token
 * whose prefix is not a registered field (e.g. `http://example.com`) is plain
 * free text, so colons in ordinary terms keep working.
 *
 * **Scoping.** A call site declares the subset of fields it can actually satisfy
 * via {@link FilterOptions.fields}. A registered-but-unsupported operator is NOT
 * silently ignored — it is recorded on {@link ParsedTerm.unsupportedFields} and
 * makes the term match nothing, so the user sees an empty result plus the
 * explanation from {@link describeUnsupportedOperators} rather than a filter that
 * quietly does nothing.
 *
 * `lib/searchMatcher.ts` remains as a thin re-export shim over this module.
 */

// ---------------------------------------------------------------------------
// Field registry
// ---------------------------------------------------------------------------

/** The comparable value a field resolves to. `num` enables numeric comparators. */
export interface FieldValue {
  /** Text form, compared case-insensitively for equality / glob matching. */
  text: string;
  /** Numeric form, required for `>=` `<=` `>` `<` comparisons. */
  num?: number;
}

/**
 * Resolve a field's comparable value from an item value (an expectation, a
 * recorded request/response pair, …). Return `undefined` when the field is not
 * present on this item — an absent field never matches.
 */
export type FieldResolver = (value: Record<string, unknown>) => FieldValue | undefined;

/**
 * A registered filter field. This is the whole contract for adding an operator:
 * implement `resolve` and register it — the engine handles parsing, comparators,
 * globs and help text.
 */
export interface FilterField {
  /** Operator name as typed before the colon, lower-case (e.g. `status`). */
  name: string;
  /** Resolves the comparable value; `undefined` when the field is absent. */
  resolve: FieldResolver;
  /** True when `>=` `<=` `>` `<` are meaningful (requires `FieldValue.num`). */
  numeric?: boolean;
  /** True when an expression containing `*` is treated as a glob. */
  glob?: boolean;
  /** Canonical example shown in the search placeholder and help (`status:>=400`). */
  example: string;
  /** One-line human description shown in the search help. */
  description: string;
}

const FIELDS = new Map<string, FilterField>();

/**
 * Register (or replace) a filter field. Replacing is how a later feature swaps in
 * a richer resolver — e.g. a real GraphQL document parser for `operation:` —
 * without touching this engine:
 *
 * ```ts
 * registerFilterField({ ...getFilterField('operation')!, resolve: parseGraphqlOperation });
 * ```
 */
export function registerFilterField(field: FilterField): void {
  const name = field.name.toLowerCase();
  FIELDS.set(name, { ...field, name });
}

/**
 * Restore the registry to exactly the built-in fields, dropping anything
 * registered since. The registry is module-level mutable state, so a test (or a
 * teardown) that registers a field MUST reset afterwards — otherwise the leak is
 * invisible only for as long as the test runner isolates files per module.
 */
export function resetFilterFields(): void {
  FIELDS.clear();
  for (const field of BUILT_IN_FIELDS) registerFilterField(field);
}

/** Look up a registered field by (case-insensitive) name. */
export function getFilterField(name: string): FilterField | undefined {
  return FIELDS.get(name.toLowerCase());
}

/** All registered fields, in registration order. */
export function filterFields(): FilterField[] {
  return [...FIELDS.values()];
}

/** All registered field names, in registration order. */
export function filterFieldNames(): string[] {
  return [...FIELDS.keys()];
}

// ---------------------------------------------------------------------------
// Value helpers shared by the built-in resolvers
// ---------------------------------------------------------------------------

/** Narrow a value to a plain object (not an array), or `undefined`. */
function asObject(v: unknown): Record<string, unknown> | undefined {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
}

/** Unwrap a NottableString (`{ value: 'GET' }`) or a plain string. */
function denote(v: unknown): string | undefined {
  if (typeof v === 'string') return v;
  const o = asObject(v);
  if (o && typeof o['value'] === 'string') return o['value'];
  return undefined;
}

/** The item's `httpRequest`, when present as an object. */
function requestOf(value: Record<string, unknown>): Record<string, unknown> | undefined {
  return asObject(value['httpRequest']);
}

/** The item's `httpResponse`, when present as an object. */
function responseOf(value: Record<string, unknown>): Record<string, unknown> | undefined {
  return asObject(value['httpResponse']);
}

/**
 * Flatten MockServer-format headers (`[{name,values}]` or `{name:[values]}`)
 * into `[name, value]` pairs. Mirrors `TrafficInspector.headerPairs`.
 */
function headerPairs(headers: unknown): Array<[string, string]> {
  const out: Array<[string, string]> = [];
  if (Array.isArray(headers)) {
    for (const h of headers) {
      const o = asObject(h);
      const name = o?.['name'];
      const values = o?.['values'];
      if (typeof name === 'string' && Array.isArray(values)) {
        for (const v of values) out.push([name, String(v)]);
      }
    }
  } else {
    const o = asObject(headers);
    if (o) {
      for (const [k, v] of Object.entries(o)) {
        if (Array.isArray(v)) for (const x of v) out.push([k, String(x)]);
        else if (typeof v === 'string') out.push([k, v]);
      }
    }
  }
  return out;
}

/**
 * Read the `Host` header from either header shape, or `undefined` when absent.
 * Deliberately identical extraction to `TrafficInspector.hostFromHeaders` (which
 * returns `''` for "absent" because it falls back to the row summary) so the
 * `host:` operator and the Traffic host column always agree.
 */
export function hostFromHeaders(headers: unknown): string | undefined {
  for (const [name, value] of headerPairs(headers)) {
    if (name.toLowerCase() === 'host') return value;
  }
  return undefined;
}

/** Best-effort JSON parse; `undefined` when the text is not JSON. */
function tryParseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/**
 * Decode a MockServer request body into a JS value. Handles the raw-string form
 * and the `{ type, json }` / `{ type, string }` envelopes. Returns `undefined`
 * when the body is absent or not decodable — never throws.
 */
function decodeBody(body: unknown): unknown {
  if (body == null) return undefined;
  if (typeof body === 'string') return tryParseJson(body);
  const o = asObject(body);
  if (!o) return undefined;
  if ('json' in o) return typeof o['json'] === 'string' ? tryParseJson(o['json']) : o['json'];
  if (typeof o['string'] === 'string') return tryParseJson(o['string']);
  return o;
}

/**
 * Per-request decoded-body cache. Body-reading resolvers run inside the
 * per-item loop of `matchesItemSearch`, i.e. once per row on every keystroke and
 * on every ~1/sec WebSocket push — the same hot path `searchTextCache` exists
 * for. `reconcileByKey` preserves the `httpRequest` object reference while its
 * content is unchanged, so keying on that reference parses each body at most
 * once. The result is boxed so a cached `undefined` is still a cache hit.
 */
const decodedBodyCache = new WeakMap<object, { body: unknown }>();

/**
 * Memoised decoded request body for an item, or `undefined` when there is no
 * decodable body. Exported so that a resolver replacing a built-in field (e.g. a
 * real GraphQL document parser swapped in for `operation:`) inherits the
 * memoisation instead of re-parsing the body per row per keystroke.
 */
export function decodedRequestBody(value: Record<string, unknown>): unknown {
  const req = requestOf(value);
  if (!req) return undefined;
  const hit = decodedBodyCache.get(req);
  if (hit) return hit.body;
  const body = decodeBody(req['body']);
  decodedBodyCache.set(req, { body });
  return body;
}

// ---------------------------------------------------------------------------
// Built-in fields
// ---------------------------------------------------------------------------

/**
 * The fields this module ships with. `resetFilterFields()` restores exactly
 * these, so anything a feature registers later is additive and reversible.
 */
const BUILT_IN_FIELDS: readonly FilterField[] = [
  {
    name: 'status',
    numeric: true,
    example: 'status:>=400',
    description: 'response status (comparators >= <= > < =)',
    resolve: (value) => {
      const sc = responseOf(value)?.['statusCode'];
      return typeof sc === 'number' ? { text: String(sc), num: sc } : undefined;
    },
  },
  {
    name: 'method',
    example: 'method:POST',
    description: 'request method (case-insensitive)',
    resolve: (value) => {
      const m = denote(requestOf(value)?.['method']);
      return m != null ? { text: m } : undefined;
    },
  },
  {
    name: 'path',
    glob: true,
    example: 'path:/api/*',
    description: 'request path glob (* = any characters)',
    resolve: (value) => {
      const p = denote(requestOf(value)?.['path']);
      return p != null ? { text: p } : undefined;
    },
  },
  {
    name: 'host',
    glob: true,
    example: 'host:*.example.com',
    description: 'request Host header glob (* = any characters)',
    resolve: (value) => {
      const h = hostFromHeaders(requestOf(value)?.['headers']);
      return h ? { text: h } : undefined;
    },
  },
  {
    name: 'operation',
    glob: true,
    example: 'operation:GetUser',
    description: 'request body operationName glob (GraphQL operation)',
    // Resolves from the ONE place an operation name is already available without
    // parsing a GraphQL document: the `operationName` member of a JSON request
    // body — the shape a GraphQL-over-HTTP POST uses (`{ query, operationName,
    // variables }`). It therefore matches any JSON body carrying an
    // `operationName` string, GraphQL or not, and resolves to `undefined` for an
    // anonymous operation or a name that only appears inside the `query`
    // document, rather than guessing. A later feature swaps in a real document
    // parser by re-registering this field with a different `resolve`; nothing in
    // the engine changes, and `decodedRequestBody` keeps the body parse memoised.
    resolve: (value) => {
      const body = asObject(decodedRequestBody(value));
      const name = body?.['operationName'];
      return typeof name === 'string' && name.length > 0 ? { text: name } : undefined;
    },
  },
];

resetFilterFields();

// ---------------------------------------------------------------------------
// Term parsing — split a raw search box value into field operators + free text
// ---------------------------------------------------------------------------

type Comparator = '>=' | '<=' | '>' | '<' | '=';

export interface FieldOperator {
  /** Lower-cased field name (e.g. 'status', 'method', 'path'). */
  field: string;
  /** Numeric comparator when the expression is a `>=400` style comparison. */
  comparator?: Comparator;
  /** Raw expression text (after any comparator). */
  expr: string;
  /**
   * Set only when the field is registered but outside the call site's declared
   * subset. Such an operator can never match — it is surfaced, not dropped.
   */
  unsupported?: true;
}

export interface ParsedTerm {
  operators: FieldOperator[];
  /** Remaining free-text portion (joined with single spaces). */
  text: string;
  /** Registered field names used here but not supported by this call site. */
  unsupportedFields: string[];
}

/** Restricts the operator vocabulary a call site advertises and can satisfy. */
export interface FilterOptions {
  /**
   * Field names this call site supports. Omit for every registered field; pass
   * `[]` for a surface that supports no field operators at all (e.g. log rows).
   */
  fields?: readonly string[];
}

/** Parse a raw search term into field operators plus the leftover free text. */
export function parseSearchTerm(raw: string, options?: FilterOptions): ParsedTerm {
  const supported = options?.fields
    ? new Set(options.fields.map((f) => f.toLowerCase()))
    : undefined;
  const operators: FieldOperator[] = [];
  const textParts: string[] = [];
  const unsupportedFields: string[] = [];

  for (const token of raw.trim().split(/\s+/).filter(Boolean)) {
    const colon = token.indexOf(':');
    const field = colon > 0 ? token.slice(0, colon).toLowerCase() : '';
    if (colon > 0 && FIELDS.has(field)) {
      const rest = token.slice(colon + 1);
      const cmp = rest.match(/^(>=|<=|>|<|=)(.*)$/);
      const op: FieldOperator = cmp
        ? { field, comparator: cmp[1] as Comparator, expr: cmp[2] ?? '' }
        : { field, expr: rest };
      if (supported && !supported.has(field)) {
        op.unsupported = true;
        if (!unsupportedFields.includes(field)) unsupportedFields.push(field);
      }
      operators.push(op);
    } else {
      textParts.push(token);
    }
  }

  return { operators, text: textParts.join(' '), unsupportedFields };
}

/**
 * Human-readable explanation for operators the call site cannot satisfy, or
 * `null` when the term is fully supported. Surfaced next to the search input so
 * an unsupported operator is visibly wrong rather than silently inert.
 */
export function describeUnsupportedOperators(
  parsed: ParsedTerm,
  options?: FilterOptions,
): string | null {
  if (parsed.unsupportedFields.length === 0) return null;
  const bad = parsed.unsupportedFields.map((f) => `${f}:`).join(', ');
  // Only advertise names that are actually registered, so a call site that
  // declares a typo'd or since-removed field cannot offer the user an operator
  // that does not exist.
  const allowed = (options?.fields ?? filterFieldNames())
    .filter((f) => FIELDS.has(f.toLowerCase()))
    .map((f) => `${f.toLowerCase()}:`)
    .join(', ');
  const supportedText = allowed.length > 0
    ? `Supported here: ${allowed}.`
    : 'No field operators are supported here.';
  return `${bad} not supported here — nothing will match. ${supportedText}`;
}

// ---------------------------------------------------------------------------
// Matching
// ---------------------------------------------------------------------------

/**
 * Compile a free-text term into a predicate over a single string. A term
 * wrapped in slashes (`/pattern/` or `/pattern/flags`) is treated as a regular
 * expression (defaulting to case-insensitive); everything else is a plain,
 * case-insensitive substring match.
 */
function makeTextPredicate(text: string): (s: string) => boolean {
  const re = /^\/(.+)\/([a-z]*)$/.exec(text);
  if (re) {
    let compiled: RegExp | null = null;
    try {
      // Default to case-insensitive unless the user supplied flags explicitly.
      compiled = new RegExp(re[1]!, re[2] || 'i');
    } catch {
      // An invalid regex matches nothing rather than throwing on every keystroke.
      compiled = null;
    }
    return (s: string) => (compiled ? compiled.test(s) : false);
  }
  const lower = text.toLowerCase();
  return (s: string) => s.toLowerCase().includes(lower);
}

/** Convert a glob expression (only `*` is special) into a case-insensitive RegExp. */
function globToRegExp(glob: string): RegExp {
  const escaped = glob.replace(/[.*+?^${}()|[\]\\]/g, (c) => (c === '*' ? '.*' : `\\${c}`));
  return new RegExp(`^${escaped}$`, 'i');
}

/**
 * Evaluate a single field operator against an item value. An unknown field, an
 * unsupported field, or a field absent from the item all fail to match.
 */
export function matchesFieldOperator(value: Record<string, unknown>, op: FieldOperator): boolean {
  if (op.unsupported) return false;
  const field = getFilterField(op.field);
  if (!field) return false;
  const fv = field.resolve(value);
  if (!fv) return false;

  if (op.comparator && op.comparator !== '=') {
    if (!field.numeric) return false;
    const target = Number(op.expr);
    if (Number.isNaN(target) || fv.num == null) return false;
    switch (op.comparator) {
      case '>=': return fv.num >= target;
      case '<=': return fv.num <= target;
      case '>': return fv.num > target;
      case '<': return fv.num < target;
    }
  }

  // '=' or a bare expression — glob when the field is glob-capable and the
  // expression uses `*`, otherwise case-insensitive equality.
  if (field.glob && op.expr.includes('*')) {
    return globToRegExp(op.expr).test(fv.text);
  }
  return fv.text.toLowerCase() === op.expr.toLowerCase();
}

// ---------------------------------------------------------------------------
// Searchable-field extraction (free-text index)
// ---------------------------------------------------------------------------

/**
 * Extract searchable text fields from a generic item value object.
 * Walks one level of known keys and collects string values.
 */
export function extractSearchableFields(value: Record<string, unknown>): string[] {
  const fields: string[] = [];

  const addString = (v: unknown) => {
    if (typeof v === 'string' && v.length > 0) fields.push(v);
  };

  const addNumber = (v: unknown) => {
    if (typeof v === 'number') fields.push(String(v));
  };

  // Top-level string/number fields
  addString(value['id']);
  addString(value['description']);
  addString(value['scenarioName']);
  addString(value['scenarioState']);
  addString(value['newScenarioState']);

  // httpRequest fields
  const req = value['httpRequest'];
  if (req && typeof req === 'object' && !Array.isArray(req)) {
    const r = req as Record<string, unknown>;
    addString(r['method']);
    addString(r['path']);
    // Handle NottableString for method/path
    if (r['method'] && typeof r['method'] === 'object') {
      addString((r['method'] as Record<string, unknown>)['value']);
    }
    if (r['path'] && typeof r['path'] === 'object') {
      addString((r['path'] as Record<string, unknown>)['value']);
    }
  }

  // httpResponse fields
  const res = value['httpResponse'];
  if (res && typeof res === 'object' && !Array.isArray(res)) {
    const r = res as Record<string, unknown>;
    addNumber(r['statusCode']);
    addString(r['reasonPhrase']);
  }

  // Action type keys
  for (const key of ['httpResponse', 'httpForward', 'httpLlmResponse', 'httpSseResponse', 'httpError', 'httpClassCallback', 'httpObjectCallback']) {
    if (key in value) fields.push(key);
  }

  // LLM-specific fields
  const llm = value['httpLlmResponse'];
  if (llm && typeof llm === 'object' && !Array.isArray(llm)) {
    const l = llm as Record<string, unknown>;
    addString(l['provider']);
    addString(l['model']);
    const completion = l['completion'];
    if (completion && typeof completion === 'object') {
      addString((completion as Record<string, unknown>)['text']);
    }
  }

  return fields;
}

// The style colour MockServer stamps on FORWARDED_REQUEST log entries. It is
// not overridden per-theme, so an exact match reliably identifies a forwarded
// log row (used by the Log panel's "Show forwarded" visibility toggle).
const FORWARDED_REQUEST_COLOR = 'rgb(152, 208, 255)';

/**
 * True when a log message is a forwarded-request entry. Walks a log group's
 * children so a group whose header (or any child) is forwarded counts.
 */
export function isForwardedLogEntry(
  message: { value?: unknown; group?: unknown; key: string },
): boolean {
  const entryIsForwarded = (entry: unknown): boolean => {
    if (!entry || typeof entry !== 'object') return false;
    const style = (entry as Record<string, unknown>)['style'];
    if (style && typeof style === 'object') {
      const color = (style as Record<string, unknown>)['color'];
      if (typeof color === 'string' && color === FORWARDED_REQUEST_COLOR) return true;
    }
    return false;
  };

  // Log group: { group: LogEntry, value: LogEntry[] }
  const group = (message as Record<string, unknown>)['group'];
  if (group && typeof group === 'object') {
    if (entryIsForwarded((group as Record<string, unknown>)['value'])) return true;
    const children = (message as Record<string, unknown>)['value'];
    if (Array.isArray(children)) {
      return children.some((c) => entryIsForwarded((c as Record<string, unknown>)?.['value'] ?? c));
    }
    return false;
  }

  // Plain log entry: { value: LogEntryValue }
  return entryIsForwarded((message as Record<string, unknown>)['value']);
}

/**
 * Per-object JSON-text cache for the deep-search fallback.
 *
 * `matchesItemSearch` / `matchesLogSearch` fall back to `JSON.stringify(obj)`
 * for nested values the field extractor misses. Without a cache that ran once
 * per row on every keystroke AND on every ~1/sec WebSocket push while a search
 * term is active. The store's `reconcileByKey` preserves each unchanged item's
 * object reference across pushes, so a WeakMap keyed on that reference serializes
 * each item at most once until its content actually changes (a changed item is
 * delivered as a fresh reference and re-serializes). Mirrors TrafficInspector's
 * `searchTextCache`. The raw (non-lowercased) JSON is stored because
 * `makeTextPredicate` applies its own case handling (a regex term may be
 * case-sensitive).
 */
const searchTextCache = new WeakMap<object, string>();

function cachedJsonText(obj: object): string {
  const hit = searchTextCache.get(obj);
  if (hit !== undefined) return hit;
  const text = JSON.stringify(obj);
  searchTextCache.set(obj, text);
  return text;
}

/**
 * Check if a generic item matches a search term by comparing against
 * extracted searchable fields. Field operators (`status:>=400`, `method:POST`,
 * `path:/api/*`) are ANDed together with the free-text portion. Free text falls
 * back to JSON.stringify only if no extracted field matched.
 *
 * Pass `options.fields` to restrict the vocabulary to what this surface can
 * satisfy; an operator outside that subset matches nothing.
 */
export function matchesItemSearch(
  value: Record<string, unknown>,
  term: string,
  options?: FilterOptions,
): boolean {
  const parsed = parseSearchTerm(term, options);

  // Every field operator must match (AND semantics).
  for (const op of parsed.operators) {
    if (!matchesFieldOperator(value, op)) return false;
  }

  // No free text left → operators alone decide the match.
  if (parsed.text.length === 0) {
    return parsed.operators.length > 0;
  }

  const pred = makeTextPredicate(parsed.text);
  const fields = extractSearchableFields(value);
  if (fields.some((f) => pred(f))) return true;
  // Fallback to full JSON for deep nested values the field extractor misses
  return pred(cachedJsonText(value));
}

/**
 * Log rows carry no request/response, so they support no field operators at all.
 * Declaring that explicitly (rather than silently dropping operators) is what
 * makes `status:>=400` in the Log panel visibly match nothing.
 */
export const LOG_FILTER_OPTIONS: FilterOptions = { fields: [] };

/**
 * Check if a log message matches a search term. Log messages have a different
 * shape (array of strings or objects with group structure). Field operators are
 * not meaningful for log rows, so any operator makes the term match nothing and
 * only the free-text portion can match.
 */
export function matchesLogSearch(
  message: { value?: unknown; key: string; messages?: unknown[] },
  term: string,
): boolean {
  const parsed = parseSearchTerm(term, LOG_FILTER_OPTIONS);
  // Log rows have no httpRequest/httpResponse to compare operators against, so
  // every operator is unsupported here and can never be satisfied. Keyed off
  // `unsupported` rather than a bare operator count so this stays consistent
  // with what LogPanel advertises: both derive from LOG_FILTER_OPTIONS, and
  // adding a log-capable field there would otherwise make the panel offer an
  // operator this function still rejected wholesale.
  if (parsed.operators.some((op) => op.unsupported)) return false;
  if (parsed.text.length === 0) return false;

  const pred = makeTextPredicate(parsed.text);
  // Check key
  if (pred(message.key)) return true;
  // Check value (log entry text)
  if (message.value != null) {
    if (typeof message.value === 'string' && pred(message.value)) return true;
    if (typeof message.value === 'object') {
      const v = message.value as Record<string, unknown>;
      for (const field of ['message', 'description', 'type', 'logLevel']) {
        const fv = v[field];
        if (typeof fv === 'string' && pred(fv)) return true;
      }
    }
  }
  // Fallback to JSON stringify
  return pred(cachedJsonText(message));
}
