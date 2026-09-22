import { useCallback, useMemo, useState } from 'react';

/**
 * Tracks which dashboard rows are expanded, keyed by each row's stable `key`.
 *
 * Expand state has to live ABOVE the row components because the panels are
 * virtualized: a row scrolled out of view is unmounted, so per-row local
 * `useState` would be lost (a row expanded, scrolled away, then back would
 * reset to collapsed). Holding the expanded keys in the panel keeps the state
 * alive across that unmount/remount.
 *
 * `toggle` is referentially stable so it can be passed to memoized rows without
 * defeating their `React.memo`. Read the per-row boolean with `isExpanded(key)`
 * in the parent and pass that down, rather than passing `isExpanded` itself
 * (which changes identity whenever the set changes).
 */
export interface Expansion {
  isExpanded: (key: string) => boolean;
  toggle: (key: string) => void;
  /**
   * True when ANY row is expanded. The panels combine this with "scrolled away
   * from the top" to decide whether the reader is mid-read and the live window
   * must not be allowed to delete what they are looking at (see `useHeldItems`).
   */
  anyExpanded: boolean;
}

export function useExpansion(): Expansion {
  const [keys, setKeys] = useState<Set<string>>(() => new Set());

  const toggle = useCallback((key: string) => {
    setKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  const isExpanded = useCallback((key: string) => keys.has(key), [keys]);
  const anyExpanded = keys.size > 0;

  return useMemo(
    () => ({ isExpanded, toggle, anyExpanded }),
    [isExpanded, toggle, anyExpanded],
  );
}
