/**
 * FROZEN PRE-OPTIMIZATION COPY of src/lib/searchMatcher.ts at git commit
 * 7dae8c885 (parent of e09495682) — for benchmarking only. The ONLY difference
 * from the shipped version is the deep-search fallback: this OLD copy calls
 * `JSON.stringify(value)` directly on every call, where the NEW version caches
 * it in a WeakMap keyed on the item reference. Imported only by bench files.
 */

/**
 * Shared search-matching utilities. Match against extracted searchable fields
 * rather than raw JSON.stringify, which matches structural keys (e.g. "value",
 * "type") and re-serialises on every keystroke.
 *
 * The TrafficInspector already used a field-based approach; this module
 * generalises it for the request / expectation / log panels.
 *
 * On top of plain substring matching the term can opt into:
 *   - regex matching, by wrapping the free-text portion in slashes —
 *       `/^GET .*\/api/`  (optional trailing flags, e.g. `/foo/i`); and
 *   - field-prefix operators embedded in the term itself, e.g.
 *       `status:>=400`   (numeric comparison against the response status code)
 *       `method:POST`    (exact, case-insensitive field equality)
 *       `path:/api/*`    (glob match, `*` → any run of characters)
 * Anything that is not a recognised `field:expr` token is treated as a free-text
 * term and matched against the extracted searchable fields as before.
 */

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
}

export interface ParsedTerm {
  operators: FieldOperator[];
  /** Remaining free-text portion (joined with single spaces). */
  text: string;
}

// Recognised field-operator names. Anything else (e.g. a URL like "http://x")
// is NOT treated as an operator so colons in ordinary terms still work.
const KNOWN_FIELDS = new Set(['status', 'method', 'path']);

/** Parse a raw search term into field operators plus the leftover free text. */
export function parseSearchTerm(raw: string): ParsedTerm {
  const operators: FieldOperator[] = [];
  const textParts: string[] = [];

  for (const token of raw.trim().split(/\s+/).filter(Boolean)) {
    const colon = token.indexOf(':');
    const field = colon > 0 ? token.slice(0, colon).toLowerCase() : '';
    if (colon > 0 && KNOWN_FIELDS.has(field)) {
      const rest = token.slice(colon + 1);
      const cmp = rest.match(/^(>=|<=|>|<|=)(.*)$/);
      operators.push(
        cmp
          ? { field, comparator: cmp[1] as Comparator, expr: cmp[2] ?? '' }
          : { field, expr: rest },
      );
    } else {
      textParts.push(token);
    }
  }

  return { operators, text: textParts.join(' ') };
}

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

/** Read the comparable string/number value for a known operator field. */
function fieldValue(value: Record<string, unknown>, field: string): { text: string; num?: number } | undefined {
  const req = (value['httpRequest'] && typeof value['httpRequest'] === 'object' && !Array.isArray(value['httpRequest']))
    ? (value['httpRequest'] as Record<string, unknown>) : undefined;
  const res = (value['httpResponse'] && typeof value['httpResponse'] === 'object' && !Array.isArray(value['httpResponse']))
    ? (value['httpResponse'] as Record<string, unknown>) : undefined;

  const denote = (v: unknown): string | undefined => {
    if (typeof v === 'string') return v;
    if (v && typeof v === 'object' && typeof (v as Record<string, unknown>)['value'] === 'string') {
      return (v as Record<string, unknown>)['value'] as string;
    }
    return undefined;
  };

  switch (field) {
    case 'status': {
      const sc = res?.['statusCode'];
      if (typeof sc === 'number') return { text: String(sc), num: sc };
      return undefined;
    }
    case 'method': {
      const m = denote(req?.['method']);
      return m != null ? { text: m } : undefined;
    }
    case 'path': {
      const p = denote(req?.['path']);
      return p != null ? { text: p } : undefined;
    }
    default:
      return undefined;
  }
}

/** Evaluate a single field operator against an item value. */
function matchesOperator(value: Record<string, unknown>, op: FieldOperator): boolean {
  const fv = fieldValue(value, op.field);
  if (!fv) return false;

  if (op.comparator && op.comparator !== '=') {
    const target = Number(op.expr);
    if (Number.isNaN(target) || fv.num == null) return false;
    switch (op.comparator) {
      case '>=': return fv.num >= target;
      case '<=': return fv.num <= target;
      case '>': return fv.num > target;
      case '<': return fv.num < target;
    }
  }

  // '=' or bare expression — exact match for status, glob/exact for path,
  // case-insensitive equality for method.
  if (op.field === 'path' && op.expr.includes('*')) {
    return globToRegExp(op.expr).test(fv.text);
  }
  return fv.text.toLowerCase() === op.expr.toLowerCase();
}

/**
 * Check if a generic item matches a search term by comparing against
 * extracted searchable fields. Field operators (`status:>=400`, `method:POST`,
 * `path:/api/*`) are ANDed together with the free-text portion. Free text falls
 * back to JSON.stringify only if no extracted field matched.
 */
export function matchesItemSearch(value: Record<string, unknown>, term: string): boolean {
  const parsed = parseSearchTerm(term);

  // Every field operator must match (AND semantics).
  for (const op of parsed.operators) {
    if (!matchesOperator(value, op)) return false;
  }

  // No free text left → operators alone decide the match.
  if (parsed.text.length === 0) {
    return parsed.operators.length > 0;
  }

  const pred = makeTextPredicate(parsed.text);
  const fields = extractSearchableFields(value);
  if (fields.some((f) => pred(f))) return true;
  // Fallback to full JSON for deep nested values the field extractor misses
  return pred(JSON.stringify(value));
}

/**
 * Check if a log message matches a search term. Log messages have a different
 * shape (array of strings or objects with group structure). Field operators are
 * not meaningful for log rows, so only the free-text portion is applied.
 */
export function matchesLogSearch(
  message: { value?: unknown; key: string; messages?: unknown[] },
  term: string,
): boolean {
  const parsed = parseSearchTerm(term);
  // Log rows have no httpRequest/httpResponse to compare operators against; if
  // the user typed only operators, nothing here can satisfy them.
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
  return pred(JSON.stringify(message));
}
