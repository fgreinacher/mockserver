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

// ---------------------------------------------------------------------------
// Realistic dashboard WebSocket frame
// ---------------------------------------------------------------------------

/**
 * The server contract these benchmarks model (see
 * `mockserver/mockserver-netty/.../dashboard/DashboardWebSocketHandler.java`):
 *
 *  - Every push carries the FULL current state of all four panels, not a delta.
 *  - Each panel is capped at `UI_UPDATE_ITEM_LIMIT = 100` items.
 *  - Pushes are throttled by a semaphore released on a 1-second schedule, so
 *    the client sees at most roughly one push per second.
 *
 * So the client's per-push work is bounded in ITEM COUNT but not in BYTES, and
 * the variable that actually moves is CHURN: how many of the 100 items are new
 * since the previous push. On an idle server the same 100 items are re-sent
 * verbatim; under load every item is new every second.
 */
export const UI_UPDATE_ITEM_LIMIT = 100;

/** How many of the `count` items are new in a given generation. */
export type Churn = 'idle' | 'one' | 'all';

function churnCount(churn: Churn, count: number): number {
  return churn === 'idle' ? 0 : churn === 'one' ? 1 : count;
}

/** A JSON body of roughly `bytes` characters, carrying a deep-only search needle. */
function jsonBody(bytes: number, seed: string): string {
  return JSON.stringify({
    model: 'claude-sonnet-4-20250514',
    content: [{ type: 'text', text: fillerText(bytes, `NEEDLE_${seed}`) }],
    usage: { input_tokens: 10, output_tokens: 500 },
    stop_reason: 'end_turn',
  });
}

export interface Frame {
  logMessages: unknown[];
  activeExpectations: unknown[];
  recordedRequests: unknown[];
  proxiedRequests: unknown[];
}

/**
 * Build one full-state dashboard frame, shaped like the real
 * `DashboardWebSocketHandler` output (validated against
 * `src/__fixtures__/dashboardFrameContract.json`).
 *
 * `generation` distinguishes successive pushes; the first `churnCount` items of
 * each panel get generation-scoped keys (they are NEW this push) and the rest
 * keep stable keys and byte-identical content (they are re-sends). That is
 * exactly the shape the store's `reconcileByKey` is written against.
 */
export function makeFrame(
  generation: number,
  churn: Churn,
  bodyBytes: number,
  count: number = UI_UPDATE_ITEM_LIMIT,
): Frame {
  const n = churnCount(churn, count);
  const id = (i: number): string => (i < n ? `g${generation}-${i}` : `stable-${i}`);
  const exchange = (i: number): Record<string, unknown> => ({
    httpRequest: {
      method: 'POST',
      path: '/v1/messages',
      headers: [{ name: 'host', values: ['api.anthropic.com'] }],
      body: { type: 'JSON', json: jsonBody(200, id(i)) },
    },
    httpResponse: {
      statusCode: 200,
      body: { type: 'JSON', json: jsonBody(bodyBytes, id(i)) },
    },
  });
  return {
    // Log rows arrive as correlation-id GROUPS carrying the entries for one
    // exchange (the server rolls them up before sending).
    logMessages: Array.from({ length: count }, (_, i) => ({
      key: `${id(i)}_log_group`,
      group: {
        key: `${id(i)}_log`,
        value: {
          timestamp: '2026-07-23 00:00:00.001',
          description: '00:00:00.001 INFO   ',
          style: { color: 'rgb(59,122,87)', whiteSpace: 'nowrap' },
          messageParts: [{ key: `${id(i)}_head`, value: 'received request' }],
        },
      },
      value: Array.from({ length: 3 }, (_, j) => ({
        key: `${id(i)}_log_${j}`,
        value: {
          timestamp: '2026-07-23 00:00:00.001',
          description: '00:00:00.001 INFO   ',
          style: { color: 'rgb(59,122,87)', whiteSpace: 'nowrap' },
          messageParts: [
            { key: `${id(i)}_${j}msg`, value: 'received request' },
            { key: `${id(i)}_${j}body`, value: jsonBody(bodyBytes, id(i)), json: true },
          ],
        },
      })),
    })),
    // Expectations are configuration, not traffic: they do not churn with load.
    activeExpectations: Array.from({ length: count }, (_, i) => ({
      key: `exp-${i}`,
      description: `exp-${i}:   /some/path/${i}`,
      value: {
        httpRequest: { path: `/some/path/${i}` },
        httpResponse: { statusCode: 200, reasonPhrase: 'OK', body: 'hello' },
        id: `exp-${i}`,
        priority: 0,
        timeToLive: { unlimited: true },
        times: { unlimited: true },
      },
    })),
    recordedRequests: Array.from({ length: count }, (_, i) => ({
      key: `${id(i)}_request`,
      description: `  /v1/messages`,
      value: exchange(i),
    })),
    proxiedRequests: Array.from({ length: count }, (_, i) => ({
      key: `${id(i)}_proxied`,
      value: exchange(i),
    })),
  };
}

/** Body scales used across the benches and the perf guards. */
export const REST_BODY_BYTES = 400;
export const LLM_BODY_BYTES = 200 * 1024;

// ---------------------------------------------------------------------------
// Dead-code-elimination sink
// ---------------------------------------------------------------------------

/**
 * Somewhere for a benchmark to put its result so V8 cannot delete the work.
 *
 * THIS IS NOT DEFENSIVE TIDINESS — it was measured. A bench arm that calls a
 * pure function in a loop and discards the result is, after a few thousand
 * iterations, optimised away entirely, and vitest then reports a number for
 * work that never ran. On 2026-09-21, the identical warm `matchesItemSearch`
 * loop over 100 x 200 kB items measured:
 *
 *   result discarded                 0.0377 ms   <- fiction
 *   result consumed via this sink    4.4719 ms   <- real
 *   manual timing, 50 iterations     4.1228 ms   <- corroborates the real one
 *
 * 108x. The discarded arm looked like the fastest thing in the table. Every arm
 * in every bench file under `src/__bench__/` must therefore feed `consume`.
 *
 * `consume` is deliberately opaque to the optimiser: it writes to an exported
 * mutable binding, which cannot be proven dead.
 */
export const benchSink = { value: 0 };

export function consume(result: unknown): void {
  // Cheap, total, and unremovable: every branch touches the exported object.
  if (result === undefined || result === null) benchSink.value += 1;
  else if (typeof result === 'boolean') benchSink.value += result ? 1 : 2;
  else if (typeof result === 'number') benchSink.value += result;
  else if (typeof result === 'string') benchSink.value += result.length;
  else if (Array.isArray(result)) benchSink.value += result.length;
  else benchSink.value += 1;
}
