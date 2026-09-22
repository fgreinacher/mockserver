import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useHeldItems } from '../hooks/useHeldItems';

interface Row { key: string }
const keyOf = (r: Row) => r.key;
const rows = (...ids: number[]): Row[] => ids.map((n) => ({ key: `r${n}` }));

/**
 * The defect these cover is EVICTION, not scrolling. The dashboard lists are a
 * live window capped at 100 rows server-side; under load the row a reader has
 * open is dropped from the feed within seconds. Two earlier fixes targeted scroll
 * position and changed nothing a reader could feel, because the row was not
 * moving — it was being deleted.
 */
describe('useHeldItems', () => {
  it('passes the live list straight through while idle', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(3, 2, 1), active: false } },
    );
    expect(result.current.map(keyOf)).toEqual(['r3', 'r2', 'r1']);

    // The window slides: r1 evicted, r4 arrives. Idle, so nothing is held.
    rerender({ items: rows(4, 3, 2), active: false });
    expect(result.current.map(keyOf)).toEqual(['r4', 'r3', 'r2']);
  });

  it('keeps a row the server has dropped while the reader is mid-read', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(3, 2, 1), active: true } },
    );
    expect(result.current.map(keyOf)).toEqual(['r3', 'r2', 'r1']);

    // r1 and r2 fall out of the server's window; r4 and r5 arrive.
    rerender({ items: rows(5, 4, 3), active: true });

    // New rows are at the FRONT (they prepend above the reader, where scroll
    // anchoring absorbs them) and the dropped rows are still present.
    expect(result.current.map(keyOf)).toEqual(['r5', 'r4', 'r3', 'r2', 'r1']);
  });

  it('survives a complete turnover of the window', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(3, 2, 1), active: true } },
    );
    // Nothing the reader started with is still being sent — the exact case the
    // live measurement hit at t=11s.
    rerender({ items: rows(9, 8, 7), active: true });
    expect(result.current.map(keyOf)).toEqual(['r9', 'r8', 'r7', 'r3', 'r2', 'r1']);
  });

  it('releases the held rows when the reader returns to the top', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(3, 2, 1), active: true } },
    );
    rerender({ items: rows(6, 5, 4), active: true });
    expect(result.current).toHaveLength(6);

    // Back to the top with nothing open: the list is the plain live window again,
    // so an idle panel stays as light as it was before.
    rerender({ items: rows(6, 5, 4), active: false });
    expect(result.current.map(keyOf)).toEqual(['r6', 'r5', 'r4']);
  });

  it('does not hold the FIRST snapshot forever across separate reads', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(3, 2, 1), active: true } },
    );
    rerender({ items: rows(3, 2, 1), active: false }); // released
    rerender({ items: rows(9, 8, 7), active: true });  // a new, later read
    rerender({ items: rows(11, 10, 9), active: true });

    // Only the SECOND read's rows are held; r1-r3 are long gone.
    expect(result.current.map(keyOf)).toEqual(['r11', 'r10', 'r9', 'r8', 'r7']);
  });

  it('does not duplicate a row that is both held and still live', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(3, 2, 1), active: true } },
    );
    rerender({ items: rows(4, 3, 2), active: true });
    const keys = result.current.map(keyOf);
    expect(keys).toEqual(['r4', 'r3', 'r2', 'r1']);
    expect(new Set(keys).size).toBe(keys.length);
  });
});
