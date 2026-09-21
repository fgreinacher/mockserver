/**
 * REGRESSION GUARD: a byte-identical re-push must not re-render the panels.
 *
 * The server sends the FULL state of all four panels roughly once a second even
 * when nothing changed. `reconcileByKey` in `src/store/index.ts` detects that
 * case and returns the PREVIOUS array identity (the "L1 array-identity"
 * short-circuit), so a Zustand selector on the array short-circuits and the
 * whole subtree is skipped. Without it, an idle dashboard re-renders every
 * panel once a second forever.
 *
 * TWO ARMS, and the difference between them matters:
 *
 *   PRODUCT arm — drives the REAL `useDashboardStore.applyMessage` and asserts
 *                 on the real store's behaviour. This is the arm that guards
 *                 the shipped code. The previous version of this file had no
 *                 such arm: it drove a COPY of the reconcile function that
 *                 lived in `__bench__/legacy/reconcile.new.ts`, so it would
 *                 have stayed green through any regression in the store.
 *
 *   CONTROL arm — drives `legacy/reconcile.old.ts`, the frozen pre-optimisation
 *                 implementation, and asserts it DOES re-render. This is kept
 *                 deliberately: it is the built-in falsification evidence that
 *                 the PRODUCT assertion can fail. A guard whose assertion
 *                 cannot be made to fail proves nothing, and this arm makes the
 *                 failing case executable rather than a claim in a comment.
 *
 * MEASURED (2026-09-21): identical re-push → product 0 re-renders per push;
 * control 1 re-render per push.
 */
import { describe, it, expect, afterEach, beforeEach } from 'vitest';
import { render, act, cleanup } from '@testing-library/react';
import { create, type StoreApi, type UseBoundStore } from 'zustand';
import { reconcileByKeyOld, type ReconcileCache } from '../__bench__/legacy/reconcile.old';
import { useDashboardStore } from '../store';
import type { WebSocketMessage } from '../types';
import {
  makeItems,
  deepCloneItems,
  makeFrame,
  REST_BODY_BYTES,
  type BenchItem,
  PANEL_SIZE,
  SMALL_BODY_BYTES,
} from '../__bench__/fixtures';

afterEach(() => cleanup());

// ---------------------------------------------------------------------------
// PRODUCT arm — the real store
// ---------------------------------------------------------------------------

/**
 * Mount a component subscribed to one real store array, apply `frames` in
 * order, and return how many times it rendered per frame after the first.
 */
function productReRenders(frames: WebSocketMessage[]): number[] {
  let renders = 0;
  function Panel(): React.ReactElement {
    const items = useDashboardStore((s) => s.recordedRequests);
    renders++;
    return <div data-testid="count">{items.length}</div>;
  }
  render(<Panel />);
  const counts: number[] = [];
  let previous = renders;
  for (const frame of frames) {
    act(() => useDashboardStore.getState().applyMessage(frame));
    counts.push(renders - previous);
    previous = renders;
  }
  return counts;
}

describe('store reconcile — panel re-renders per WebSocket push (PRODUCT)', () => {
  beforeEach(() => {
    useDashboardStore.getState().clearUI();
  });

  it('an identical re-push causes 0 panel re-renders', () => {
    // Three pushes of the same generation: the first delivers the data, the
    // next two are byte-identical re-sends with fresh object references, which
    // is exactly what a real socket delivers on an idle server.
    const frames = [0, 0, 0].map(
      (g) => makeFrame(g, 'idle', REST_BODY_BYTES) as unknown as WebSocketMessage,
    );
    const [first, second, third] = productReRenders(frames);

    expect(first).toBe(1); // data arrived — one render is required
    expect(second).toBe(0);
    expect(third).toBe(0);
  });

  it('a push in which one row changed causes exactly 1 panel re-render', () => {
    const base = makeFrame(0, 'one', REST_BODY_BYTES) as unknown as WebSocketMessage;
    const oneNew = makeFrame(1, 'one', REST_BODY_BYTES) as unknown as WebSocketMessage;
    const [, changed] = productReRenders([base, oneNew]);
    expect(changed).toBe(1);
  });

  it('a push in which every row changed still causes exactly 1 panel re-render', () => {
    // The panel subscribes to the array, so N changed rows cost one render of
    // the panel (and N of the memoized rows) — never N panel renders.
    const base = makeFrame(0, 'all', REST_BODY_BYTES) as unknown as WebSocketMessage;
    const allNew = makeFrame(1, 'all', REST_BODY_BYTES) as unknown as WebSocketMessage;
    const [, changed] = productReRenders([base, allNew]);
    expect(changed).toBe(1);
  });
});

// ---------------------------------------------------------------------------
// CONTROL arm — falsification evidence
// ---------------------------------------------------------------------------

interface PanelStore {
  items: BenchItem[];
  push: (next: BenchItem[]) => void;
}

function makeLegacyStore(): UseBoundStore<StoreApi<PanelStore>> {
  const cache: ReconcileCache = new Map();
  return create<PanelStore>((set) => ({
    items: [],
    push: (next) => set((s) => ({ items: reconcileByKeyOld(s.items, next, cache) })),
  }));
}

describe('store reconcile — CONTROL: the pre-optimisation implementation re-renders', () => {
  it('without the array-identity short-circuit, every identical push re-renders', () => {
    const useStore = makeLegacyStore();
    let renders = 0;
    function Panel(): React.ReactElement {
      const items = useStore((s) => s.items);
      renders++;
      return <div>{items.length}</div>;
    }
    render(<Panel />);

    const base = makeItems(PANEL_SIZE, SMALL_BODY_BYTES);
    act(() => useStore.getState().push(deepCloneItems(base)));
    const afterFirst = renders;
    act(() => useStore.getState().push(deepCloneItems(base)));
    const second = renders - afterFirst;
    act(() => useStore.getState().push(deepCloneItems(base)));
    const third = renders - afterFirst - second;

    // This is the behaviour the PRODUCT assertions above exist to prevent. If
    // this arm ever reports 0, the control has stopped being a control and the
    // product assertions above have lost their falsifier.
    expect(second).toBe(1);
    expect(third).toBe(1);
  });
});
