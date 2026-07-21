/**
 * Translate a `lib/filterDSL` scope term into MockServer request-matcher fields.
 *
 * The filter DSL is a **client-side predicate over observed items**; a breakpoint
 * matcher and a verification matcher are **server-side `httpRequest` matchers**.
 * The two only agree if the translation is exact, so this module is deliberately
 * conservative: it converts the three operators whose semantics survive the round
 * trip (`method:`, `path:`, `host:`) and refuses everything else with a reason,
 * rather than emitting a matcher that quietly matches the wrong traffic.
 *
 * **Why `path:`/`host:` need translating at all.** MockServer matches `path` and
 * header values as *exact-or-regex* (full match). The DSL's `path:`/`host:` are
 * *globs* where only `*` is special. Passing the glob through untouched would be
 * the classic silent mismatch: `path:/api/*` as a regex means "/api" followed by
 * zero-or-more "/" — it would NOT match `/api/orders`, and nothing would tell the
 * user. So a glob is compiled to the equivalent anchored regex source
 * (`/api/*` -> `/api/.*`), with every other regex metacharacter escaped so a
 * literal term stays literal (`/api/v1.0` -> `/api/v1\.0`).
 *
 * **Why the vocabulary stops at three operators.**
 * - `status:` has nowhere to land in these forms. The *server* would cope with the
 *   whole operator — `StatusCodeMatcher` supports an exact code, a class range
 *   (`"2XX"`) and numeric operators (`">= 400"`) via `statusCodeRange`, and
 *   `BreakpointMatcher` carries `responseStatusCodeMin`/`Max` — but neither UI form
 *   has a field for a range: `VerificationView`'s response matcher exposes a single
 *   exact `statusCode`, and `lib/breakpoints.ts` sends only
 *   `{httpRequest, phases, clientId, skipCount}` with no response condition at all.
 *   So "Apply scope" has no destination for `status:>=400`, and the operator is
 *   flagged unsupported rather than honoured only in its degenerate `status:200`
 *   form. Adding a `statusCodeRange` / `responseStatusCodeMin`/`Max` field to either
 *   form is what would make `status:` viable here.
 * - `operation:` resolves from a decoded JSON request body's `operationName`.
 *   Neither matcher has a JSON-member body matcher wired to it here, so it is out.
 *
 * Callers pass {@link REQUEST_SCOPE_FIELDS} to `OperatorSearchField`, so the
 * placeholder, the help tooltip, the input's error state and this translation all
 * derive from the same list and cannot drift apart.
 */
import { parseSearchTerm } from './filterDSL';
import type { KeyToMultiValue } from '../types';

/**
 * The filter-DSL operators a server-side `httpRequest` matcher can genuinely
 * honour. Passed to `OperatorSearchField`'s `fields` prop so anything else is
 * flagged in the input instead of being silently dropped.
 */
export const REQUEST_SCOPE_FIELDS = ['method', 'path', 'host'] as const;

/** Matcher fields derived from a scope term. `path`/`host` are regex sources. */
export interface RequestScope {
  method?: string;
  path?: string;
  host?: string;
}

export interface ParsedScope {
  /** The matcher fields the term contributes. Empty when nothing is applicable. */
  scope: RequestScope;
  /**
   * Registered operators outside {@link REQUEST_SCOPE_FIELDS}. `OperatorSearchField`
   * already explains these in its own helper text, so a caller should not repeat
   * the message — it only needs to refuse to apply.
   */
  unsupportedFields: string[];
  /**
   * Any *other* reason the term cannot be applied (leftover free text, a numeric
   * comparator, a `*` on the non-glob `method:`). Null when the term is clean.
   */
  error: string | null;
}

/**
 * Compile a glob (only `*` is special) to a regex *source* string that MockServer
 * will full-match. Mirrors `filterDSL`'s internal `globToRegExp` escaping exactly,
 * so a term matches the same set in a search box and in a matcher.
 */
export function globToRegexSource(glob: string): string {
  return glob.replace(/[.*+?^${}()|[\]\\]/g, (c) => (c === '*' ? '.*' : `\\${c}`));
}

/**
 * Parse a scope term into matcher fields, or into the reason it cannot be applied.
 * Never returns a partially-applied scope alongside an error — a refused term
 * contributes nothing.
 */
export function parseRequestScope(term: string): ParsedScope {
  const parsed = parseSearchTerm(term, { fields: REQUEST_SCOPE_FIELDS });
  const scope: RequestScope = {};

  if (parsed.text.length > 0) {
    return {
      scope: {},
      unsupportedFields: parsed.unsupportedFields,
      error: `"${parsed.text}" is not a scope operator - use ${REQUEST_SCOPE_FIELDS.map((f) => `${f}:`).join(', ')}, or type it into the fields below.`,
    };
  }

  for (const op of parsed.operators) {
    if (op.unsupported) continue; // reported via unsupportedFields
    if (op.comparator && op.comparator !== '=') {
      return {
        scope: {},
        unsupportedFields: parsed.unsupportedFields,
        error: `${op.field}:${op.comparator}${op.expr} - comparators (>= <= > <) need a numeric operator, and this scope has none.`,
      };
    }
    if (op.expr.length === 0) {
      return {
        scope: {},
        unsupportedFields: parsed.unsupportedFields,
        error: `${op.field}: has no value.`,
      };
    }
    // The search box ANDs repeated operators, so `path:/a path:/b` selects
    // nothing there. A matcher has one field per name, so the best this could do
    // is silently keep the last — which would mean the same term scopes the box
    // and the matcher differently. Refuse instead.
    if (scope[op.field as keyof RequestScope] != null) {
      return {
        scope: {},
        unsupportedFields: parsed.unsupportedFields,
        error: `${op.field}: appears more than once - a matcher has a single ${op.field}.`,
      };
    }
    if (op.field === 'method') {
      if (op.expr.includes('*')) {
        return {
          scope: {},
          unsupportedFields: parsed.unsupportedFields,
          error: 'method: matches one exact method, so * is not supported there.',
        };
      }
      scope.method = op.expr.toUpperCase();
    } else if (op.field === 'path') {
      scope.path = globToRegexSource(op.expr);
    } else if (op.field === 'host') {
      scope.host = globToRegexSource(op.expr);
    }
  }

  return { scope, unsupportedFields: parsed.unsupportedFields, error: null };
}

/** True when the term is clean AND contributes at least one matcher field. */
export function isApplicableScope(parsed: ParsedScope): boolean {
  return (
    parsed.error == null &&
    parsed.unsupportedFields.length === 0 &&
    Object.keys(parsed.scope).length > 0
  );
}

// ---------------------------------------------------------------------------
// Host -> Host-header matcher
// ---------------------------------------------------------------------------
//
// Neither the breakpoint matcher endpoint nor the verification endpoint takes a
// host: both take a MockServer `httpRequest`, whose only representation of a host
// is the `Host` header. `host:` therefore lands in the form's existing headers
// control, where it stays visible and editable rather than becoming hidden state.

/**
 * Set the `Host` header row to `hostRegex` in a `KeyToMultiValue[]` headers
 * control: replacing an existing (case-insensitive) Host row, otherwise reusing
 * the first blank row, otherwise appending.
 */
export function withHostHeaderRow(rows: KeyToMultiValue[], hostRegex: string): KeyToMultiValue[] {
  const existing = rows.findIndex((r) => r.name.trim().toLowerCase() === 'host');
  if (existing >= 0) {
    return rows.map((r, i) => (i === existing ? { name: r.name, values: [hostRegex] } : r));
  }
  const blank = rows.findIndex((r) => r.name.trim() === '' && r.values.every((v) => v.trim() === ''));
  const row: KeyToMultiValue = { name: 'Host', values: [hostRegex] };
  if (blank >= 0) return rows.map((r, i) => (i === blank ? row : r));
  return [...rows, row];
}

/**
 * Set the `Host` header in a `Name: value` per-line headers textarea, replacing an
 * existing (case-insensitive) Host line and preserving every other line and their
 * order. Matches `standardCodegen.parseKeyValueLines`, which is what the
 * verification form parses this textarea with.
 */
export function withHostHeaderLine(text: string, hostRegex: string): string {
  const line = `Host: ${hostRegex}`;
  const lines = text.split(/\r?\n/);
  const existing = lines.findIndex((l) => {
    const idx = l.indexOf(':');
    return idx > 0 && l.slice(0, idx).trim().toLowerCase() === 'host';
  });
  if (existing >= 0) return lines.map((l, i) => (i === existing ? line : l)).join('\n');
  // Append symmetrically with the replace branch: keep the user's lines as they are
  // and only drop the trailing blank an empty/newline-terminated textarea leaves.
  const kept = [...lines];
  while (kept.length > 0 && kept[kept.length - 1]!.trim() === '') kept.pop();
  return [...kept, line].join('\n');
}
