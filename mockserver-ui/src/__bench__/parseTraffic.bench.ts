/**
 * `cachedParseTraffic` — the WeakMap in front of `parseTraffic`, used by
 * `summarizeTraffic` (Traffic inspector) and by `groupBySession` (Trace view).
 *
 * BOTH ARMS ARE CURRENT CODE. This is not an old-vs-new proof: `parseTraffic`
 * is still exported and is exactly what `cachedParseTraffic` runs on a MISS. So
 * the two arms are the two live outcomes of a single shipped function:
 *
 *   WARM — the item's `value` reference is unchanged since the last push, so
 *          `reconcileByKey` preserved it and the WeakMap hits. This is the
 *          idle-re-push and re-render case.
 *   COLD — the item is new (or its content changed), so it arrives as a fresh
 *          reference and the parse runs in full. This is the case that
 *          dominates under load, when every item is new every second, and it
 *          is the one the previous version of this file never measured: it
 *          labelled the uncached call "OLD" and read its cost as historical.
 *
 * The cold arm allocates its fresh reference in an UNTIMED per-iteration
 * `beforeEach`, so the timed region contains only the parse, not the clone.
 *
 * MEASURED (2026-09-21, macOS arm64, node v22.21.1, vitest 5.0.1 — mean ms for
 * 100 items, i.e. one full panel):
 *
 *                                small (100 x 200 B)   large (100 x 200 kB)
 *   cachedParseTraffic  WARM               0.0059                0.0065
 *   cachedParseTraffic  COLD               0.1831                9.8549
 *   parseTraffic (uncached reference)      0.1733               10.7886
 *   groupBySession      WARM               0.0172                0.0163
 *   groupBySession      COLD               0.1912                9.1098
 *
 * The COLD arm landing on the uncached reference arm is the proof that the
 * untimed `beforeEach` really does hand it a never-seen reference; if COLD ever
 * collapses towards WARM, the fixture has stopped being fresh and the number is
 * meaningless.
 *
 * READ IT AS: the cache is worth ~30x at REST scale and ~1500x at LLM scale,
 * but only while references are stable. Under sustained load the WARM column
 * is unreachable and the real per-push cost is the COLD one — ~9.9 ms per panel
 * at LLM body sizes. A change that shortens reference lifetime (anything that
 * stops `reconcileByKey` preserving references) moves every reader from the
 * WARM row to the COLD row without touching this file.
 *
 * Every arm feeds `consume()` from the fixtures. That is load-bearing, not
 * style — see its doc comment: a discarded pure result is optimised away and
 * the bench then reports a number for work that never ran.
 */
import { test } from 'vitest';
import { parseTraffic, cachedParseTraffic } from '../lib/llmTraffic';
import { groupBySession } from '../lib/sessionGrouping';
import {
  consume,
  makeItems,
  deepCloneItems,
  type BenchItem,
  PANEL_SIZE,
  SMALL_BODY_BYTES,
  LARGE_BODY_BYTES,
} from './fixtures';

for (const [scale, bytes] of [
  ['small', SMALL_BODY_BYTES],
  ['large', LARGE_BODY_BYTES],
] as const) {
  const items: BenchItem[] = makeItems(PANEL_SIZE, bytes);
  // Pre-warm the production WeakMap so the WARM arms measure steady-state hits.
  for (const it of items) cachedParseTraffic(it.value);

  // A fresh, never-cached clone per iteration, built outside the timed region.
  let fresh: BenchItem[] = deepCloneItems(items);

  test(`parseTraffic · ${scale} (100 × ${bytes}B) · one panel`, async ({ bench }) => {
    await bench.compare(
      bench(
        'COLD — item is new this push (cache miss)',
        { beforeEach: () => { fresh = deepCloneItems(items); } },
        () => {
          for (const it of fresh) consume(cachedParseTraffic(it.value).kind);
        },
      ),
      bench('WARM — reference preserved across pushes (cache hit)', () => {
        for (const it of items) consume(cachedParseTraffic(it.value).kind);
      }),
      bench('reference: uncached parseTraffic (what a miss runs)', () => {
        for (const it of items) consume(parseTraffic(it.value).kind);
      }),
    );
  });

  test(`groupBySession · ${scale} (100 × ${bytes}B) · one panel`, async ({ bench }) => {
    await bench.compare(
      bench(
        'COLD — every session member is new (cache miss)',
        { beforeEach: () => { fresh = deepCloneItems(items); } },
        () => {
          consume(groupBySession(fresh, []));
        },
      ),
      bench('WARM — references preserved (cache hit)', () => {
        consume(groupBySession(items, []));
      }),
    );
  });
}
