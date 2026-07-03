/**
 * FROZEN PRE-OPTIMIZATION COPY — for benchmarking only.
 *
 * Verbatim `reconcileByKey` + `ReconcileCache` from the store as of git commit
 * 7dae8c885 (the direct parent of e09495682, the commit under measurement).
 *
 * This version already preserved each unchanged ENTRY's reference across pushes,
 * but always allocated a NEW result array via `next.map(...)` — so the array
 * identity changed on every push, re-rendering every subscribed panel. It also
 * computed `JSON.stringify(n)` before the `p === n` short-circuit.
 *
 * Imported only by bench files under `src/__bench__/`; never by app code.
 */

export type ReconcileCache = Map<string, { ref: unknown; str: string }>;

export function reconcileByKeyOld<T extends { key: string }>(
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
  const result = next.map((n) => {
    const p = prevByKey.get(n.key);
    if (!p) {
      nextCache.set(n.key, { ref: n, str: JSON.stringify(n) });
      return n;
    }
    const nStr = JSON.stringify(n);
    if (p === n) {
      // Same reference already held; no identity to preserve, but keep it cached.
      nextCache.set(n.key, { ref: n, str: nStr });
      return n;
    }
    // Trust the cached string only when it was recorded for *this* reference;
    // otherwise (cold/stale cache after a direct setState) re-serialize `p`.
    const cached = cache.get(n.key);
    const pStr = cached && cached.ref === p ? cached.str : JSON.stringify(p);
    if (pStr === nStr) {
      // Semantically unchanged — preserve the previous reference and its string.
      nextCache.set(n.key, { ref: p, str: pStr });
      return p;
    }
    nextCache.set(n.key, { ref: n, str: nStr });
    return n;
  });
  cache.clear();
  for (const [k, v] of nextCache) cache.set(k, v);
  return result;
}
