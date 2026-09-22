import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';

interface ProgressiveListProps {
  /** Number of rows. */
  count: number;
  /** Stable React key for the row at `index`. */
  getKey: (index: number) => string;
  /** Render the row at `index`. */
  renderRow: (index: number) => ReactNode;
  /** Estimated row height (px) used before a row is measured. */
  estimateSize?: number;
  /** Extra rows rendered above/below the viewport so scrolling stays smooth. */
  overscan?: number;
  /**
   * Rows rendered on the very first paint, before the scroll viewport has been
   * discovered — enough to fill the visible area cheaply without mounting the
   * whole list. Once the viewport is known the list windows instead.
   */
  initial?: number;
}

/**
 * Renders a long list with true viewport virtualization (windowing): only the
 * rows in (or near) the visible area are mounted in the DOM, regardless of how
 * many rows the list contains. A 50k-entry log therefore mounts a few dozen row
 * elements rather than 50k, so scrolling, filtering and appending stay smooth.
 *
 * The data contract is unchanged from the previous idle-batched implementation —
 * callers still pass `count`, a stable `getKey(index)` and `renderRow(index)`,
 * and ordering/selection/append semantics are driven entirely by the caller's
 * data. Only the *rendering* strategy changed: instead of progressively mounting
 * every row, the list windows them.
 *
 * Rows may have variable height (entries expand/collapse), so heights are
 * measured dynamically via `measureElement` rather than assumed fixed.
 *
 * The list scrolls inside the nearest scrollable ancestor (the panel's scroll
 * area), which it discovers on mount via a stable probe element — it does not
 * introduce its own scroll container, so the panel's auto-scroll-to-top
 * behaviour keeps working.
 *
 * Render strategy by phase:
 *  - First paint (scroll viewport not yet resolved): render only `initial` rows,
 *    so the first mount is cheap even for a 50k-entry list. A layout effect then
 *    resolves the scroll ancestor synchronously before the browser paints.
 *  - Viewport resolved with a usable height: window the rows.
 *  - No scrollable ancestor, or a zero-height viewport (non-layout/headless
 *    environment such as jsdom, or a panel laid out at 0px): render every row so
 *    the full set stays reachable when windowing cannot run.
 */
function findScrollParent(el: HTMLElement | null): HTMLElement | null {
  let node: HTMLElement | null = el?.parentElement ?? null;
  while (node) {
    const style = typeof getComputedStyle === 'function' ? getComputedStyle(node) : null;
    const overflowY = style?.overflowY;
    if (overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') {
      return node;
    }
    node = node.parentElement;
  }
  return null;
}

// Matches Panel's AT_TOP_THRESHOLD_PX. Below this offset the reader counts as
// parked at the top, where following the newest rows is the wanted behaviour and
// anchoring would work against it.
const ANCHOR_MIN_OFFSET_PX = 8;

export default function ProgressiveList({
  count,
  getKey,
  renderRow,
  estimateSize = 56,
  overscan = 8,
  initial = 20,
}: ProgressiveListProps) {
  // A stable, always-present probe used only to locate the scroll ancestor. It
  // is never swapped between render branches, so the ref stays attached and
  // re-discovery never races a branch switch.
  const probeRef = useRef<HTMLDivElement>(null);
  const [scrollParent, setScrollParent] = useState<HTMLElement | null>(null);

  // Discover the panel's scroll container synchronously after the first commit
  // and before paint, so the browser never paints the cheap first-render slice
  // for long lists — it goes straight to the windowed render.
  useLayoutEffect(() => {
    const parent = findScrollParent(probeRef.current);
    setScrollParent((prev) => (prev === parent ? prev : parent));
  }, []);

  const virtualizer = useVirtualizer({
    count,
    getScrollElement: () => scrollParent,
    estimateSize: () => estimateSize,
    overscan,
    getItemKey: (index) => getKey(index),
  });

  // --- Scroll anchoring on prepend ------------------------------------------
  //
  // These lists are newest-first, so a live update inserts rows at the FRONT.
  // Every existing row's offset grows by the height of what arrived, while
  // `scrollTop` does not — so the content the reader is looking at slides down
  // and, once it leaves the window, unmounts. That reads as an opened item
  // "closing", and it made the live panels unusable.
  //
  // Two properties of the real data make the obvious fixes wrong:
  //
  //  * The list LENGTH does not change. The server caps a dashboard panel at 100
  //    rows and evicts the oldest as it prepends the newest, so on any server
  //    that has handled more than 100 entries the count is constant forever.
  //    Anything gated on the count growing is inert exactly when traffic is live.
  //  * The anchor row can be UNMOUNTED by the same update. After a prepend the
  //    window is recomputed from the unchanged `scrollTop`, which now points at
  //    different rows, so the row we want to hold onto is frequently not in the
  //    DOM by the time a layout effect could measure it.
  //
  // So the anchor is resolved from the virtualizer's measurements instead of the
  // DOM: it measures every row, mounted or not, keyed by the caller's stable
  // `getKey`. We remember which row was at the top of the viewport and how far
  // its top sat above it, then restore that relationship after the update.
  //
  // CSS `overflow-anchor` cannot do this: the rows are `position: absolute`,
  // which browsers exclude from native scroll anchoring, and windowed rows leave
  // the DOM entirely.
  const anchorRef = useRef<{ key: string; gap: number } | null>(null);

  const captureAnchor = useCallback(() => {
    const el = scrollParent;
    if (!el) {
      anchorRef.current = null;
      return;
    }
    const offset = el.scrollTop;
    // At (or within a hair of) the top there is nothing to hold: the reader
    // wants the newest rows, which is what arriving rows give them. Anchoring
    // here would push them DOWN away from the top on every update.
    if (offset <= ANCHOR_MIN_OFFSET_PX) {
      anchorRef.current = null;
      return;
    }
    // `measurementsCache` is the public measurement surface and covers EVERY row,
    // not just the mounted window, so a capture taken immediately after a fast
    // scroll still finds the row now at the top. (`getMeasurements()` is typed
    // private; reaching into it would work today and break silently on a library
    // bump — the same class of quiet failure this whole fix is about.)
    const measurements = virtualizer.measurementsCache;
    for (let i = 0; i < measurements.length; i++) {
      const m = measurements[i];
      if (m && m.end > offset) {
        // `gap` is negative when the row is partly scrolled off the top, which
        // is the common case and is exactly what we want to preserve.
        anchorRef.current = { key: String(m.key), gap: m.start - offset };
        return;
      }
    }
    anchorRef.current = null;
  }, [scrollParent, virtualizer]);

  // The reader's own scrolling redefines the anchor: wherever they stopped is
  // the position the next update has to preserve.
  useEffect(() => {
    const el = scrollParent;
    if (!el) return;
    const onScroll = () => captureAnchor();
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, [scrollParent, captureAnchor]);

  // Deliberately NO dependency array — this must run after EVERY commit, because
  // the update that shifts the reader's position need not change `count` (see
  // above). It runs before `Panel`'s tail-following effect (child effects run
  // before parent effects), so a reader parked at the top is still snapped to 0
  // by Panel and never fights this.
  useLayoutEffect(() => {
    const el = scrollParent;
    const anchor = anchorRef.current;
    if (!el || !anchor) return;
    // The reader is at the top NOW, whatever the anchor says. This is not
    // redundant with captureAnchor's own top check: a scroll event is delivered
    // ASYNCHRONOUSLY, so when Panel's tail-following sets scrollTop = 0 a commit
    // can land before the listener re-captures, leaving an anchor that points at
    // wherever the reader used to be. Correcting towards it then throws them back
    // down the list — observed on the live dashboard as scrolling to the top
    // snapping the panel to the very bottom.
    if (el.scrollTop <= ANCHOR_MIN_OFFSET_PX) {
      anchorRef.current = null;
      return;
    }
    // Where did the anchor row end up? Its INDEX has changed (everything shifts
    // when rows are prepended), so find it by the caller's stable key, then ask
    // the virtualizer for that index's offset. `getOffsetForIndex(i, 'start')`
    // is the public accessor and answers for any row, mounted or not — which is
    // the whole point: after a prepend the anchor row is usually outside the
    // window and therefore absent from the DOM.
    let index = -1;
    for (let i = 0; i < count; i++) {
      if (getKey(i) === anchor.key) {
        index = i;
        break;
      }
    }
    if (index === -1) {
      // The anchor row was evicted from the list entirely. There is nothing to
      // hold onto, so leave the position alone and re-anchor below.
      captureAnchor();
      return;
    }
    const offsetInfo = virtualizer.getOffsetForIndex(index, 'start');
    if (offsetInfo) {
      const want = offsetInfo[0] - anchor.gap;
      if (Math.abs(want - el.scrollTop) > 0.5) {
        el.scrollTop = want;
      }
    }
    captureAnchor();
  });

  // The probe sits at the top of the list in every branch so findScrollParent
  // always has a stable anchor into the panel's DOM.
  const probe = <div ref={probeRef} style={{ height: 0 }} aria-hidden />;

  const viewportHeight = scrollParent ? scrollParent.offsetHeight : 0;

  // Windowing is possible only once a scrollable ancestor with a real height is
  // known. Until then (first paint) render a cheap bounded slice; if no usable
  // viewport ever resolves (headless / 0px), render the full list.
  if (!scrollParent || viewportHeight === 0) {
    const rendered = scrollParent ? count : Math.min(initial, count);
    return (
      <>
        {probe}
        {Array.from({ length: rendered }, (_, i) => (
          <div key={getKey(i)} data-vrow={i}>
            {renderRow(i)}
          </div>
        ))}
      </>
    );
  }

  const items = virtualizer.getVirtualItems();

  return (
    <>
      {probe}
      <div style={{ height: virtualizer.getTotalSize(), width: '100%', position: 'relative' }}>
        {items.map((item) => (
          <div
            key={item.key as string}
            data-index={item.index}
            data-vrow={item.index}
            ref={virtualizer.measureElement}
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: '100%',
              transform: `translateY(${item.start}px)`,
            }}
          >
            {renderRow(item.index)}
          </div>
        ))}
      </div>
    </>
  );
}
