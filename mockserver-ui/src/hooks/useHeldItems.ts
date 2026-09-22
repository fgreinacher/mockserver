import { useState } from 'react';

/**
 * Keeps the rows above a reader from vanishing while they read, for lists
 * rendered in console order (oldest first, newest appended at the bottom).
 *
 * WHY CONSOLE ORDER ALONE IS NOT ENOUGH. Appending at the bottom is what makes
 * these panels stable: a new row lands BELOW the viewport, so nothing a reader is
 * looking at moves, and no scroll compensation is needed. That removes the
 * disruption from arrivals. It does not remove the disruption from EVICTION.
 *
 * The server sends a window of at most DEFAULT_LOG_UPDATE_ITEM_LIMIT (100) rows
 * and drops the OLDEST as new ones arrive. Oldest-first means the oldest are at
 * the TOP — so under load the rows above the reader are exactly the ones being
 * deleted, the list collapses upward, and everything they are reading slides up
 * the screen. Measured against a real server at ~10 req/s, the whole window turns
 * over in about ten seconds.
 *
 * WHAT THIS DOES. While `active` — the reader is not following the tail, so they
 * are reading rather than watching — rows that were on screen are HELD even once
 * the server stops sending them. They keep their place at the top, new rows keep
 * appending at the bottom, and nothing between moves. When the reader resumes
 * following, the held rows are released and the list is the plain live window
 * again, so an idle panel is exactly as light as before.
 *
 * ORDER. `items` and the returned list are both in DISPLAY order, oldest first.
 * Held rows that the server has dropped are older than everything still live, so
 * they go at the FRONT.
 *
 * MEMORY. The snapshot is taken once, when the reader stops following, and never
 * grows while held — bounded by the same 100-row window. It is dropped as soon as
 * they follow again.
 *
 * IMPLEMENTATION NOTE. The snapshot is adjusted during render (React's documented
 * "adjusting state when a prop changes" pattern) rather than held in a ref:
 * reading or writing a ref during render is what `react-hooks/refs` forbids, and
 * an effect would be a render late — one frame of the collapsing list exactly when
 * the reader stops to read.
 */
export function useHeldItems<T>(
  items: readonly T[],
  getKey: (item: T) => string,
  active: boolean,
): readonly T[] {
  const [prevActive, setPrevActive] = useState(active);
  const [held, setHeld] = useState<readonly T[] | null>(active ? items : null);

  let snapshot = held;
  if (active !== prevActive) {
    snapshot = active ? items : null;
    setPrevActive(active);
    setHeld(snapshot);
  }

  if (!active || snapshot === null) {
    return items;
  }

  const live = new Set(items.map(getKey));
  const survivors = snapshot.filter((h) => !live.has(getKey(h)));
  return survivors.length === 0 ? items : [...survivors, ...items];
}
