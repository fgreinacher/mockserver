import { useState } from 'react';

/**
 * Keeps the rows a reader is looking at alive while they are reading them, even
 * after the server has stopped sending those rows.
 *
 * WHY THIS EXISTS. The dashboard lists show a live window, not a history:
 * `DashboardWebSocketHandler` sends at most `DEFAULT_LOG_UPDATE_ITEM_LIMIT` (100)
 * rows and drops the oldest as new ones arrive. Under sustained traffic that
 * window turns over quickly — measured against a real server at roughly 10
 * requests a second, an opened row was **deleted out from under the reader after
 * ten seconds**:
 *
 *     t=1s   inDom=true   top=621
 *     t=10s  inDom=true   top=621
 *     t=11s  inDom=false  <- evicted from the feed
 *
 * Note `top=621` holding steady throughout: scroll anchoring was working the whole
 * time. The row did not move — it ceased to exist. That is why two earlier fixes
 * aimed at scrolling changed nothing a reader could feel. No scroll strategy can
 * hold a row the data no longer contains.
 *
 * WHAT IT DOES. While `active` is true — the reader has scrolled away from the
 * top, or has a row open or selected — the rows present at that moment are HELD:
 * they stay in the list even once the server stops sending them. New rows still
 * arrive and prepend above (scroll anchoring keeps the viewport still), so nothing
 * the reader is looking at moves, and the new rows are already in place when they
 * scroll back up. When `active` goes false the held set is released and the list
 * returns to the plain live window, so an idle list stays exactly as light as it
 * was before.
 *
 * MEMORY. The held set is captured once, when interaction begins, and never grows
 * while held — it is bounded by the same 100-row server cap. It is dropped as soon
 * as the reader returns to the top with nothing open.
 *
 * IMPLEMENTATION NOTE. The snapshot lives in state and is adjusted during render
 * (React's documented "adjusting state when a prop changes" pattern) rather than
 * in a ref. A ref would be the obvious spelling, but reading or writing one during
 * render is exactly what the `react-hooks/refs` compiler rule forbids, and an
 * effect-based snapshot would be a render late — showing the reader one frame of
 * the un-held list at the moment they start interacting.
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
    // Interaction just started (snapshot what is on screen) or just ended (release).
    snapshot = active ? items : null;
    setPrevActive(active);
    setHeld(snapshot);
  }

  if (!active || snapshot === null) {
    return items;
  }

  const live = new Set(items.map(getKey));
  // Held rows the server has since dropped. Order is preserved, and they are older
  // than everything still live, so they belong after it: these lists are
  // newest-first and new rows prepend at the front.
  const survivors = snapshot.filter((h) => !live.has(getKey(h)));
  return survivors.length === 0 ? items : [...items, ...survivors];
}
