/**
 * Item 1 (L1 array-identity) real-world effect — a RENDER-COUNT measurement,
 * not a microbench.
 *
 * The reconcile microbench shows OLD and NEW do equal CPU work on an idle push;
 * the actual value of Item 1 is that returning the PREVIOUS array identity lets
 * a Zustand selector on that array short-circuit, so subscribed panels do not
 * re-render on a byte-identical re-push. This test proves that: it subscribes a
 * component to `store(s => s.items)` and counts how many times it renders when
 * an identical push arrives, under OLD vs NEW reconcile semantics.
 *
 * Expectation: NEW → 0 extra renders per identical push; OLD → 1 per push.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, act, cleanup } from '@testing-library/react';
import { create, type StoreApi, type UseBoundStore } from 'zustand';
import { reconcileByKeyOld, type ReconcileCache } from '../__bench__/legacy/reconcile.old';
import { reconcileByKeyNew } from '../__bench__/legacy/reconcile.new';
import { makeItems, deepCloneItems, type BenchItem, PANEL_SIZE, SMALL_BODY_BYTES } from '../__bench__/fixtures';

type Reconcile = (prev: BenchItem[], next: BenchItem[], cache: ReconcileCache) => BenchItem[];

interface PanelStore {
  items: BenchItem[];
  push: (next: BenchItem[]) => void;
}

function makeStore(reconcile: Reconcile): UseBoundStore<StoreApi<PanelStore>> {
  const cache: ReconcileCache = new Map();
  return create<PanelStore>((set) => ({
    items: [],
    push: (next) => set((s) => ({ items: reconcile(s.items, next, cache) })),
  }));
}

/**
 * Mount a panel subscribed to `store(s => s.items)`, feed it three pushes of the
 * SAME content (fresh references each time, as a real WebSocket push produces),
 * and return the number of extra panel renders caused by pushes 2 and 3.
 */
function measureIdenticalPushReRenders(reconcile: Reconcile): { push2: number; push3: number } {
  const useStore = makeStore(reconcile);
  let renders = 0;
  function Panel() {
    const items = useStore((s) => s.items);
    renders++;
    return <div data-testid="count">{items.length}</div>;
  }
  render(<Panel />);

  const base = makeItems(PANEL_SIZE, SMALL_BODY_BYTES);
  act(() => useStore.getState().push(deepCloneItems(base))); // first data arrives
  const afterFirst = renders;
  act(() => useStore.getState().push(deepCloneItems(base))); // identical re-push #2
  const afterSecond = renders;
  act(() => useStore.getState().push(deepCloneItems(base))); // identical re-push #3
  const afterThird = renders;

  return { push2: afterSecond - afterFirst, push3: afterThird - afterSecond };
}

afterEach(() => cleanup());

describe('reconcile L1 array-identity — panel re-renders on an identical push', () => {
  it('NEW causes 0 re-renders per identical push; OLD causes 1', () => {
    const oldCounts = measureIdenticalPushReRenders(reconcileByKeyOld);
    cleanup();
    const newCounts = measureIdenticalPushReRenders(reconcileByKeyNew);

    // Surface the measured numbers in the test output for the report.
    // eslint-disable-next-line no-console
    console.log(
      `[render-count] identical push → OLD re-renders per push: ${oldCounts.push2}, ${oldCounts.push3}` +
        ` | NEW re-renders per push: ${newCounts.push2}, ${newCounts.push3}`,
    );

    expect(oldCounts.push2).toBe(1);
    expect(oldCounts.push3).toBe(1);
    expect(newCounts.push2).toBe(0);
    expect(newCounts.push3).toBe(0);
  });

  it('NEW still re-renders exactly once when one row actually changes', () => {
    const useStore = makeStore(reconcileByKeyNew);
    let renders = 0;
    function Panel() {
      const items = useStore((s) => s.items);
      renders++;
      return <div>{items.length}</div>;
    }
    render(<Panel />);

    const base = makeItems(PANEL_SIZE, SMALL_BODY_BYTES);
    act(() => useStore.getState().push(deepCloneItems(base)));
    const afterFirst = renders;
    // Change one row's content, keep its key.
    const changed = deepCloneItems(base);
    changed[0] = { key: base[0]!.key, value: { ...changed[0]!.value, marker: 'CHANGED' } };
    act(() => useStore.getState().push(changed));
    expect(renders - afterFirst).toBe(1); // a real change must still render
  });
});
