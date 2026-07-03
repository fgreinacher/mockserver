/**
 * Shared, deterministic fixtures for the dashboard performance benchmarks.
 *
 * These builders are imported ONLY by files under `src/__bench__/` (bench files
 * and the perf tests). They are never imported by application code, so they are
 * tree-shaken out of the production bundle (`vite build` only bundles the graph
 * reachable from `index.html`).
 *
 * Two payload scales are modelled, per the project's never-regress requirement
 * that an optimisation must hold for BOTH small and large payloads:
 *   - SMALL: ~200-byte request/response bodies (a plain REST-ish exchange)
 *   - LARGE: ~200 kB LLM-like response bodies (a long streamed completion)
 */

export interface BenchItem {
  key: string;
  value: Record<string, unknown>;
}

/** A run of printable characters of approximately `bytes` length, ending in a
 *  deep-only search needle so a fallback JSON.stringify scan has to reach it. */
function fillerText(bytes: number, needle: string): string {
  const padLen = Math.max(0, bytes - needle.length - 1);
  return 'x'.repeat(padLen) + ' ' + needle;
}

/**
 * Build one MockServer proxied request/response pair shaped like real Anthropic
 * traffic, so `parseTraffic` classifies it as `anthropic` and `groupBySession`
 * treats it as LLM traffic. `bodyBytes` sizes the assistant completion text.
 */
export function makeLlmItem(index: number, bodyBytes: number): BenchItem {
  const needle = `NEEDLE_${index}`;
  const completionText = fillerText(bodyBytes, needle);
  return {
    key: `proxied-${index}`,
    value: {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        headers: [{ name: 'host', values: ['api.anthropic.com'] }],
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'claude-sonnet-4-20250514',
            max_tokens: 1024,
            stream: false,
            messages: [{ role: 'user', content: `turn ${index}` }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'claude-sonnet-4-20250514',
            content: [{ type: 'text', text: completionText }],
            usage: { input_tokens: 10, output_tokens: 500 },
            stop_reason: 'end_turn',
          }),
        },
      },
    },
  };
}

/** A full panel's worth of items (100 entries by default) at a given body scale. */
export function makeItems(count: number, bodyBytes: number): BenchItem[] {
  return Array.from({ length: count }, (_, i) => makeLlmItem(i, bodyBytes));
}

/**
 * Deep-clone an item array via JSON round-trip. This is exactly what every
 * WebSocket push produces: brand-new object references with identical content,
 * so entry references never match across pushes (the case the optimisations
 * target). Done once, OUTSIDE any timed region.
 */
export function deepCloneItems(items: BenchItem[]): BenchItem[] {
  return JSON.parse(JSON.stringify(items)) as BenchItem[];
}

/** Clone `items` and change exactly one entry's content (the "1 row changed"
 *  case: a single new event arrived, the other 99 are byte-identical re-sends). */
export function cloneWithOneChanged(items: BenchItem[], bodyBytes: number): BenchItem[] {
  const clone = deepCloneItems(items);
  const idx = Math.floor(clone.length / 2);
  clone[idx] = makeLlmItem(idx + 100000, bodyBytes); // different content, same key slot
  clone[idx]!.key = items[idx]!.key; // keep the key so it reconciles as "changed", not "added"
  return clone;
}

export const PANEL_SIZE = 100;
export const SMALL_BODY_BYTES = 200;
export const LARGE_BODY_BYTES = 200 * 1024;
