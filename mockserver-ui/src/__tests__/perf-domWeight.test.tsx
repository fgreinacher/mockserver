/**
 * REGRESSION GUARD: how much DOM the dashboard mounts for a full dataset.
 *
 * WHY THIS IS THE MEASUREMENT THAT MATTERS. The server caps each panel at 100
 * items and throttles to ~1 push/sec, so the client never accumulates an
 * unbounded list and the store's per-push CPU is bounded (measured in
 * `src/__bench__/storePush.bench.ts`: 1.8 ms at REST body sizes, 12.5 ms at
 * 8 kB bodies). What is NOT bounded is the DOM: a panel that mounts one row
 * element per item mounts ~11-22 elements per row, and re-renders all of them
 * whenever the items change. At 200 rows that is ~2,200 elements rebuilt on
 * every push under load — an order of magnitude more work than the store does.
 *
 * DOM ELEMENT COUNT, not milliseconds, is what this file asserts on. Wall-clock
 * numbers under jsdom on a shared CI agent are not reproducible enough to gate;
 * element counts are exact, deterministic, and are the thing that makes the
 * page heavy. The timings below are recorded for orientation only and are NOT
 * asserted.
 *
 * MEASURED (2026-09-21, macOS arm64, node v22.21.1, jsdom, 100 items per panel,
 * 400 B bodies; mean ms for one `applyMessage` with the panel mounted):
 *
 *   Panel                       rows   DOM elements   idle push   1 new   all new
 *   TrafficInspector (no        200    2,234          4.5 ms      37.7    126.0
 *     virtualization)
 *   LogPanel, all rows mounted  100    2,220          -           59.0    190.8
 *   LogPanel, windowed          100      220          -           16.1     41.0
 *
 * The LogPanel rows are the SAME data rendered by the SAME component; the only
 * difference is whether `ProgressiveList` could resolve a scroll viewport. That
 * is the size of the virtualization lever: 10x the DOM and ~4x the per-push
 * render cost.
 *
 * jsdom reports `offsetHeight` as 0 and `getComputedStyle().overflowY` as
 * 'visible' for every element, so `ProgressiveList` cannot discover a scroll
 * ancestor and deliberately falls back to rendering every row (see its
 * "No scrollable ancestor, or a zero-height viewport" branch). The windowed
 * numbers above, and the windowing guard below, therefore stub those two
 * layout APIs — which is the only way to exercise the windowed branch outside a
 * real browser. The stub is scoped to this file and removed in `afterAll`.
 */
import { describe, it, expect, beforeEach, afterEach, afterAll } from 'vitest';
import { render, act, cleanup } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import LogPanel from '../components/LogPanel';
import TrafficInspector from '../components/TrafficInspector';
import { useDashboardStore } from '../store';
import type { WebSocketMessage } from '../types';
import { makeFrame, REST_BODY_BYTES, UI_UPDATE_ITEM_LIMIT } from '../__bench__/fixtures';

function elementCount(container: HTMLElement): number {
  return container.querySelectorAll('*').length;
}

function applyFrame(generation: number, count: number): void {
  act(() => {
    useDashboardStore
      .getState()
      .applyMessage(
        makeFrame(generation, 'all', REST_BODY_BYTES, count) as unknown as WebSocketMessage,
      );
  });
}

beforeEach(() => {
  useDashboardStore.getState().clearUI();
});
afterEach(() => cleanup());

// ---------------------------------------------------------------------------
// 1. Un-virtualized list: DOM grows with the dataset
// ---------------------------------------------------------------------------

describe('DOM weight — TrafficInspector request list', () => {
  /**
   * TrafficInspector renders `filtered.map(...)` directly: one `TrafficRow` per
   * captured request, with no windowing. Its list is therefore proportional to
   * the dataset, which is a deliberate, recorded fact rather than an accident —
   * this test pins the per-row cost so the list cannot get quietly heavier, and
   * is the before/after instrument if the list is ever virtualized.
   */
  it('mounts a bounded number of elements PER ROW (pins the per-row cost)', () => {
    const smallRows = 10;
    applyFrame(0, smallRows);
    const small = render(
      <ThemeProvider theme={buildTheme('dark')}>
        <TrafficInspector />
      </ThemeProvider>,
    );
    const smallCount = elementCount(small.container);
    cleanup();

    useDashboardStore.getState().clearUI();
    const largeRows = UI_UPDATE_ITEM_LIMIT;
    applyFrame(0, largeRows);
    const large = render(
      <ThemeProvider theme={buildTheme('dark')}>
        <TrafficInspector />
      </ThemeProvider>,
    );
    const largeCount = elementCount(large.container);

    // Both frames populate recordedRequests AND proxiedRequests, so the list
    // holds 2x the per-panel count.
    const perRow = (largeCount - smallCount) / ((largeRows - smallRows) * 2);

    // Measured 2026-09-21: 10.9 elements per row (254 elements at 20 rows,
    // 2,234 at 200). Budget deliberately just above the measured value — this
    // catches a row gaining structure, which is how an un-virtualized list
    // turns from heavy into unusable.
    expect(perRow).toBeLessThanOrEqual(13);
    expect(perRow).toBeGreaterThan(0); // the rows really are being mounted
  });
});

// ---------------------------------------------------------------------------
// 2. Virtualized list: DOM does NOT grow with the dataset
// ---------------------------------------------------------------------------

const realGetComputedStyle = globalThis.getComputedStyle;
let offsetHeightPatched = false;

/**
 * Give jsdom just enough layout for `ProgressiveList` to find a scroll ancestor
 * with a real height, so its windowed branch runs. Without this it takes its
 * documented "no usable viewport" fallback and renders every row, and a DOM
 * budget asserted here would silently be a budget on the FALLBACK — a guard
 * that passes while proving nothing about virtualization.
 */
function enableLayout(): void {
  if (!offsetHeightPatched) {
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get() {
        return 600;
      },
    });
    offsetHeightPatched = true;
  }
  globalThis.getComputedStyle = ((element: Element, pseudo?: string | null) => {
    const style = realGetComputedStyle(element, pseudo ?? undefined);
    if ((element as HTMLElement).dataset?.['scrollhost'] !== undefined) {
      return { ...style, overflowY: 'auto' } as CSSStyleDeclaration;
    }
    return style;
  }) as typeof globalThis.getComputedStyle;
}

afterAll(() => {
  globalThis.getComputedStyle = realGetComputedStyle;
  if (offsetHeightPatched) {
    delete (HTMLElement.prototype as unknown as Record<string, unknown>)['offsetHeight'];
  }
});

function renderWindowedLogPanel(): HTMLElement {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <div data-scrollhost style={{ height: 600, overflowY: 'auto' }}>
        <LogPanel />
      </div>
    </ThemeProvider>,
  ).container;
}

describe('DOM weight — LogPanel is windowed', () => {
  it('mounts roughly the same number of elements for 25 rows as for 100', () => {
    enableLayout();

    applyFrame(0, 25);
    const fewCount = elementCount(renderWindowedLogPanel());
    cleanup();

    useDashboardStore.getState().clearUI();
    applyFrame(0, UI_UPDATE_ITEM_LIMIT);
    const manyCount = elementCount(renderWindowedLogPanel());

    // Measured 2026-09-21: 220 elements at 100 rows with windowing active,
    // against 2,220 without it. Windowing means the count is driven by the
    // VIEWPORT, not the dataset, so growing the dataset 4x must not grow the
    // DOM 4x.
    //
    // The assertion is a RATIO, not an absolute count, so it survives row
    // markup changing; what it cannot survive is the list mounting every row.
    // Un-windowed, `manyCount / fewCount` is ~4 (the dataset ratio).
    expect(manyCount / fewCount).toBeLessThan(2);

    // And an absolute ceiling, so "windowing" that mounts a 400-row overscan
    // still fails. 100 rows x ~22 elements = 2,200 un-windowed.
    expect(manyCount).toBeLessThan(900);
  });
});
