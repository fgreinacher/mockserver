/**
 * `matchesItemSearch` deep-search fallback — the `cachedJsonText` WeakMap in
 * `src/lib/filterDSL.ts`.
 *
 * When a search term is not satisfied by an extracted field, the matcher falls
 * back to scanning `JSON.stringify(value)`. The fixture forces that path: the
 * term `needle` appears only deep inside the response body, never in an
 * extracted field, so every item must be serialized.
 *
 * BOTH ARMS ARE CURRENT CODE, for the same reason as `parseTraffic.bench.ts`:
 *
 *   WARM — the item reference survived the push (`reconcileByKey` preserved
 *          it), so the serialized text is already in the WeakMap. This is the
 *          keystroke case on a quiet server, and the re-filter that every push
 *          triggers while a term is active.
 *   COLD — the item is new, so it is serialized in full. Under load this is
 *          EVERY item on EVERY push, for every panel with an active search.
 *
 * The previous version of this file compared against
 * `legacy/searchMatcher.old.ts`, a copy frozen at commit 7dae8c885. That file
 * has since been rewritten and relocated (`src/lib/searchMatcher.ts` is now a
 * re-export of `src/lib/filterDSL.ts`, which gained field-operator parsing and
 * the `options.fields` vocabulary restriction). The frozen copy was therefore
 * no longer a like-for-like model of the shipped matcher, and its number was
 * not comparable to anything current — so it was deleted rather than kept as a
 * floor. The cold path is modelled directly instead, by handing the CURRENT
 * matcher a fresh reference in an untimed per-iteration `beforeEach`.
 *
 * MEASURED (2026-09-21, macOS arm64, node v22.21.1, vitest 5.0.1 — mean ms for
 * 100 items, i.e. one full panel, one keystroke or one push):
 *
 *                     small (100 x 200 B)   large (100 x 200 kB)
 *   WARM                        0.0690                 5.3744
 *   COLD                        0.2085                55.6656
 *
 * READ IT AS: with an active search term, a single push over a panel of
 * LLM-sized bodies costs ~56 ms of serialization when the items are new, and
 * the dashboard has four such panels. The WARM number is not small either —
 * 5.4 ms is the cost of the predicate scan over 20 MB of already-cached text,
 * which no cache removes. A search box that re-filters on every keystroke
 * without debouncing pays the WARM number per keystroke at minimum.
 *
 * THE WARM NUMBER IS THE ONE THAT CAUGHT US OUT. This exact arm, written
 * without `consume()`, reported 0.0377 ms — 140x too fast, and top of the
 * table — because V8 deleted a loop whose pure result was discarded. Manual
 * timing in the same file said 4.12 ms; routing the result through the
 * fixtures' `consume()` brought the bench to 4.47 ms and then 5.37 ms here.
 * Every arm in every bench file must consume its result.
 */
import { test } from 'vitest';
import { matchesItemSearch } from '../lib/searchMatcher';
import {
  consume,
  makeItems,
  deepCloneItems,
  type BenchItem,
  PANEL_SIZE,
  SMALL_BODY_BYTES,
  LARGE_BODY_BYTES,
} from './fixtures';

const TERM = 'needle'; // matches only the deep body needle → forces JSON fallback

for (const [scale, bytes] of [
  ['small', SMALL_BODY_BYTES],
  ['large', LARGE_BODY_BYTES],
] as const) {
  const items: BenchItem[] = makeItems(PANEL_SIZE, bytes);
  // Pre-warm the production WeakMap so the WARM arm measures steady-state hits.
  for (const it of items) matchesItemSearch(it.value, TERM);

  let fresh: BenchItem[] = deepCloneItems(items);

  test(`matchesItemSearch · ${scale} (100 × ${bytes}B) · deep-fallback`, async ({ bench }) => {
    await bench.compare(
      bench(
        'COLD — items new this push (serialize every row)',
        { beforeEach: () => { fresh = deepCloneItems(items); } },
        () => {
          for (const it of fresh) consume(matchesItemSearch(it.value, TERM));
        },
      ),
      bench('WARM — references preserved (scan cached text)', () => {
        for (const it of items) consume(matchesItemSearch(it.value, TERM));
      }),
    );
  });
}
