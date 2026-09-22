import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useHeldItems } from '../hooks/useHeldItems';

interface Row { key: string }
const keyOf = (r: Row) => r.key;
/** Rows in DISPLAY order: oldest first, newest last, as a console renders them. */
const rows = (...ids: number[]): Row[] => ids.map((n) => ({ key: `r${n}` }));

/**
 * The defect here is EVICTION, and console order does not remove it — it moves
 * it. Appending the newest at the bottom means arrivals land below the reader and
 * nothing they are looking at moves. But the server still drops the OLDEST as new
 * rows arrive, and oldest-first puts those at the TOP: the rows above the reader
 * are exactly the ones being deleted, so the list collapses upward and everything
 * slides up the screen. Holding them is what keeps the view still.
 */
describe('useHeldItems (console order: oldest first, newest last)', () => {
  it('passes the live window straight through while following', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(1, 2, 3), active: false } },
    );
    expect(result.current.map(keyOf)).toEqual(['r1', 'r2', 'r3']);

    // Window slides: r1 evicted from the top, r4 appended at the bottom.
    rerender({ items: rows(2, 3, 4), active: false });
    expect(result.current.map(keyOf)).toEqual(['r2', 'r3', 'r4']);
  });

  it('holds evicted rows at the TOP so the list cannot collapse upward', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(1, 2, 3), active: true } },
    );
    // r1 and r2 drop off the top; r4 and r5 append at the bottom.
    rerender({ items: rows(3, 4, 5), active: true });

    // The held rows keep their place ABOVE, and the new ones are below — so
    // everything the reader had on screen is still exactly where it was.
    expect(result.current.map(keyOf)).toEqual(['r1', 'r2', 'r3', 'r4', 'r5']);
  });

  it('survives a complete turnover of the window', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(1, 2, 3), active: true } },
    );
    // Nothing the reader started with is still being sent.
    rerender({ items: rows(7, 8, 9), active: true });
    expect(result.current.map(keyOf)).toEqual(['r1', 'r2', 'r3', 'r7', 'r8', 'r9']);
  });

  it('releases the held rows when the reader follows again', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(1, 2, 3), active: true } },
    );
    rerender({ items: rows(4, 5, 6), active: true });
    expect(result.current).toHaveLength(6);

    // Following again: back to the plain live window, so an idle panel is as
    // light as it was before.
    rerender({ items: rows(4, 5, 6), active: false });
    expect(result.current.map(keyOf)).toEqual(['r4', 'r5', 'r6']);
  });

  it('does not hold the first snapshot forever across separate reads', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(1, 2, 3), active: true } },
    );
    rerender({ items: rows(1, 2, 3), active: false }); // released
    rerender({ items: rows(7, 8, 9), active: true });  // a new, later read
    rerender({ items: rows(9, 10, 11), active: true });

    // Only the SECOND read's rows are held; r1-r3 are long gone.
    expect(result.current.map(keyOf)).toEqual(['r7', 'r8', 'r9', 'r10', 'r11']);
  });

  it('does not duplicate a row that is both held and still live', () => {
    const { result, rerender } = renderHook(
      ({ items, active }) => useHeldItems(items, keyOf, active),
      { initialProps: { items: rows(1, 2, 3), active: true } },
    );
    rerender({ items: rows(2, 3, 4), active: true });
    const keys = result.current.map(keyOf);
    expect(keys).toEqual(['r1', 'r2', 'r3', 'r4']);
    expect(new Set(keys).size).toBe(keys.length);
  });
});
