/**
 * Client for MockServer's request diff endpoint (`/mockserver/diff`).
 * Compares two captured requests and returns field-by-field diffs.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface FieldDiff {
  field: string;
  expectedValue?: string;
  actualValue?: string;
  diffType: 'ADDED' | 'REMOVED' | 'CHANGED';
}

export interface DiffResult {
  diffCount: number;
  identical: boolean;
  diffs: FieldDiff[];
}

/**
 * Pattern produced by the mismatch analyser for a single field difference, e.g.
 * "expected /api/users but was /api/items" or "expected POST but was GET".
 * Capturing groups: 1 = expected value, 2 = actual value.
 */
const EXPECTED_BUT_WAS = /^expected\s+([\s\S]*?)\s+but was\s+([\s\S]*)$/i;

/**
 * Convert a closest-expectation mismatch (`differences` = field -> human-readable
 * reason strings) into the {@link DiffResult} shape that {@link DiffPanel} renders,
 * so the unmatched-request flow can show a side-by-side "what the request contained
 * vs what the matcher expected" visual diff.
 *
 * Each `expected X but was Y` reason becomes a CHANGED row (expected=X, actual=Y).
 * Reasons that don't match that pattern are still surfaced — the raw reason text is
 * placed in the Expected column with a CHANGED type so no information is lost.
 *
 * `differences` may be undefined/empty (e.g. no closest match, or a closest match
 * with no field-level detail); in that case an identical/zero-diff result is returned
 * and the caller can fall back to the text reasons.
 */
export function mismatchDifferencesToDiffResult(
  differences: Record<string, string[]> | undefined,
): DiffResult {
  const diffs: FieldDiff[] = [];
  for (const [field, reasons] of Object.entries(differences ?? {})) {
    for (const reason of reasons) {
      const match = EXPECTED_BUT_WAS.exec(reason.trim());
      if (match) {
        diffs.push({
          field,
          expectedValue: match[1],
          actualValue: match[2],
          diffType: 'CHANGED',
        });
      } else {
        // Unparseable reason — keep the raw text rather than dropping it.
        diffs.push({
          field,
          expectedValue: reason,
          actualValue: undefined,
          diffType: 'CHANGED',
        });
      }
    }
  }
  return {
    diffCount: diffs.length,
    identical: diffs.length === 0,
    diffs,
  };
}

/**
 * Header names whose values routinely differ between two otherwise-identical
 * requests (timestamps, per-request correlation ids, trace context, computed
 * content length) and so are noise when diffing. Fiddler's "Compare" offers the
 * same idea via a header ignore-list. Comma-separated in the UI; matched
 * case-insensitively.
 */
export const DEFAULT_IGNORED_DIFF_HEADERS = ['date', 'x-request-id', 'traceparent', 'content-length'];

/** Parse a comma-separated ignore-list into a normalised (trimmed, lower-cased, de-duped) array. */
export function parseIgnoredHeaders(input: string): string[] {
  const seen = new Set<string>();
  for (const raw of input.split(',')) {
    const name = raw.trim().toLowerCase();
    if (name) seen.add(name);
  }
  return [...seen];
}

/**
 * Does a diff `field` reference one of the ignored header names?
 *
 * The diff is computed server-side (PUT /mockserver/diff), so the exact field
 * naming for a header is not fixed here — it can arrive as `headers.Date`,
 * `header:Date`, `headers[Date]`, or a bare `Date`. Split on the *structural*
 * separators only (`.`, `:`, `[`, `]`, whitespace) — never on `-`, so multi-word
 * header names such as `content-length` / `x-request-id` stay whole — then match
 * any resulting segment against the ignore-set. This keeps `date` from spuriously
 * matching a field like `headers.date-of-birth` (which tokenises to
 * `date-of-birth`, not `date`).
 */
function fieldMatchesIgnoredHeader(field: string, ignore: ReadonlySet<string>): boolean {
  const segments = field.split(/[.:[\]\s]+/).filter((segment) => segment !== '');
  // Only header fields are eligible — the server emits headers as
  // `header.<name>`/`headers.<name>`; query params (`queryParam.date`) and
  // cookies (`cookie.date`) must never be hidden by a HEADER ignore-list.
  const first = segments[0]?.toLowerCase();
  if (first !== 'header' && first !== 'headers') return false;
  return segments.slice(1).some((segment) => ignore.has(segment.trim().toLowerCase()));
}

/**
 * Drop diff rows whose field references an ignored header from an already-computed
 * {@link DiffResult}, recomputing `diffCount`/`identical`. Pure presentation-layer
 * filter: because the diff endpoint has no "ignore fields" parameter, the ignore
 * list is applied to the rendered result rather than pushed to the server. Returns
 * the input unchanged when nothing is ignored or nothing matched (referential
 * stability for memoised callers).
 */
export function filterIgnoredHeaderDiffs(result: DiffResult, ignoredHeaders: readonly string[]): DiffResult {
  if (ignoredHeaders.length === 0) return result;
  const ignore = new Set(ignoredHeaders.map((h) => h.trim().toLowerCase()).filter(Boolean));
  if (ignore.size === 0) return result;
  const diffs = result.diffs.filter((d) => !fieldMatchesIgnoredHeader(d.field, ignore));
  if (diffs.length === result.diffs.length) return result;
  return { diffs, diffCount: diffs.length, identical: diffs.length === 0 };
}

/** Diff two captured requests field-by-field. */
export async function diffRequests(
  params: ConnectionParams,
  expected: Record<string, unknown>,
  actual: Record<string, unknown>,
): Promise<DiffResult> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/diff`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ expected, actual }),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json();
}
