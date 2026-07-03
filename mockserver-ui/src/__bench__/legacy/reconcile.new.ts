/**
 * FROZEN COPY OF THE SHIPPED (NEW) IMPLEMENTATION — for benchmarking only.
 *
 * Verbatim `reconcileByKey` from `src/store/index.ts` as of git commit
 * e09495682 (the commit under measurement). The production function is
 * module-private (not exported), so it is copied here rather than imported;
 * this file is functionally identical to the production body (comments may differ).
 *
 * The two changes over `reconcile.old.ts`:
 *   L1 — returns the ORIGINAL `prev` array identity when the reconciled result
 *        is element-for-element identical (so subscribed panels skip re-render);
 *   L2 — moves `JSON.stringify(n)` AFTER the `p === n` check and reuses the
 *        cached string for that reference.
 *
 * Imported only by bench files under `src/__bench__/`; never by app code.
 */

export type ReconcileCache = Map<string, { ref: unknown; str: string }>;

export function reconcileByKeyNew<T extends { key: string }>(
  prev: T[],
  next: T[],
  cache: ReconcileCache,
): T[] {
  if (next.length === 0) {
    cache.clear();
    return next;
  }
  if (prev.length === 0) {
    cache.clear();
    for (const n of next) cache.set(n.key, { ref: n, str: JSON.stringify(n) });
    return next;
  }
  const prevByKey = new Map(prev.map((p) => [p.key, p] as const));
  const nextCache: ReconcileCache = new Map();
  let changed = next.length !== prev.length;
  const result = next.map((n, i) => {
    const p = prevByKey.get(n.key);
    if (!p) {
      changed = true;
      nextCache.set(n.key, { ref: n, str: JSON.stringify(n) });
      return n;
    }
    if (p === n) {
      const cached = cache.get(n.key);
      const str = cached && cached.ref === n ? cached.str : JSON.stringify(n);
      nextCache.set(n.key, { ref: n, str });
      if (prev[i] !== n) changed = true; // same ref but reordered
      return n;
    }
    const nStr = JSON.stringify(n);
    const cached = cache.get(n.key);
    const pStr = cached && cached.ref === p ? cached.str : JSON.stringify(p);
    if (pStr === nStr) {
      nextCache.set(n.key, { ref: p, str: pStr });
      if (prev[i] !== p) changed = true; // preserved ref but reordered
      return p;
    }
    changed = true;
    nextCache.set(n.key, { ref: n, str: nStr });
    return n;
  });
  if (!changed) {
    return prev;
  }
  cache.clear();
  for (const [k, v] of nextCache) cache.set(k, v);
  return result;
}
