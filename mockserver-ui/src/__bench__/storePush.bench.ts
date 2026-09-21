/**
 * The dashboard's hottest client path: applying one full-state WebSocket push.
 *
 * This measures the CURRENT implementation — the real `useDashboardStore`
 * `applyMessage`, which runs `reconcileByKey` over all four panels. It is not
 * an old-vs-new comparison; it is a level to be compared against on the next
 * change. `reconcileByKey` is module-private to `src/store/index.ts`, so
 * driving it through `applyMessage` is the only way to benchmark the code that
 * actually ships (a copied implementation in a bench file would silently drift
 * from the product, which is exactly what happened to the previous
 * `reconcile.bench.ts`).
 *
 * WHAT MOVES THE NUMBER. The server caps each panel at 100 items and throttles
 * to ~1 push/sec, so item count is fixed and the two free variables are:
 *   - BODY SIZE   — drives `JSON.parse` of the frame and the per-entry
 *                   `JSON.stringify` that `reconcileByKey` uses for change
 *                   detection.
 *   - CHURN       — how many of the 100 items are new. At `idle` every entry
 *                   is a byte-identical re-send and the L1 array-identity
 *                   short-circuit returns the PREVIOUS array, so no subscribed
 *                   panel re-renders. At `all` nothing is reusable.
 *
 * MEASURED (2026-09-21, macOS arm64, node v22.21.1, vitest 5.0.1 — mean ms per
 * push; the frame is parsed inside the timed region because the real
 * `useWebSocket` path parses per push too):
 *
 *   REST bodies (400 B) — frame ~540 kB
 *     parse only                 0.98   (idle)     0.98 (one)     1.00 (all)
 *     parse + applyMessage       2.20   (idle)     2.31 (one)     2.48 (all)
 *
 *   Mid-size bodies (8 kB) — frame ~4.3 MB
 *     parse only                 3.80   (idle)     3.68 (one)     3.73 (all)
 *     parse + applyMessage      14.26   (idle)    14.09 (one)    15.03 (all)
 *
 * READ IT AS: `applyMessage` costs roughly 2-4x the `JSON.parse` of the same
 * frame, and is nearly insensitive to churn — the change-detection stringify
 * runs over every entry either way. Against a ~1 push/sec budget, even the
 * 4.3 MB frame spends 1.5% of a second in the store. The store is NOT where
 * the dashboard gets slow; see `src/__tests__/perf-domWeight.test.tsx` and
 * `src/__tests__/perf-panelIsolation.test.tsx` for the render side, which is
 * an order of magnitude larger and IS churn-sensitive.
 *
 * `applyMessage` mutates the store, so it cannot be optimised away; the
 * `JSON.parse` floor arm feeds `consume()` because a discarded pure result
 * can be (see the fixtures' `consume` doc comment).
 */
import { test } from 'vitest';
import { useDashboardStore } from '../store';
import type { WebSocketMessage } from '../types';
import { consume, makeFrame, REST_BODY_BYTES, type Churn } from './fixtures';

/**
 * Pre-serialise a rotation of successive pushes. Rotating rather than replaying
 * one frame matters: a single frame re-applied forever would keep hitting the
 * reconcile cache on references it already holds, which is not what a live
 * socket delivers (every push is a fresh `JSON.parse`).
 */
const GENERATIONS = 3;

/**
 * 8 kB bodies, not the 200 kB LLM scale the older benches used. At 200 kB a
 * single frame is ~40 MB, and holding three of them plus their parsed forms
 * exhausts the default V8 old space — the bench aborts with an OOM rather than
 * reporting a number. The 40 MB frame is still measured, once, in
 * `src/__tests__/perf-domWeight.test.tsx`, which holds only one at a time.
 */
const MID_BODY_BYTES = 8 * 1024;

for (const [scale, bytes] of [
  ['REST 400B', REST_BODY_BYTES],
  ['mid 8kB', MID_BODY_BYTES],
] as const) {
  for (const churn of ['idle', 'one', 'all'] as const satisfies readonly Churn[]) {
    const raws = Array.from({ length: GENERATIONS }, (_, g) =>
      JSON.stringify(makeFrame(g, churn, bytes)),
    );
    const frameKb = (raws[0]!.length / 1024).toFixed(0);
    let cursor = 0;
    const nextRaw = (): string => raws[(cursor = (cursor + 1) % raws.length)]!;

    test(`push · ${scale} (frame ${frameKb} kB) · churn=${churn}`, async ({ bench }) => {
      await bench.compare(
        bench('JSON.parse only (floor — unavoidable per push)', () => {
          consume((JSON.parse(nextRaw()) as { logMessages: unknown[] }).logMessages);
        }),
        bench('JSON.parse + store applyMessage (current)', () => {
          useDashboardStore.getState().applyMessage(JSON.parse(nextRaw()) as WebSocketMessage);
        }),
      );
    });
  }
}
