/**
 * REGRESSION GUARD: a push that changes ONE panel's data must re-render only
 * that panel.
 *
 * `DashboardGrid` subscribes to `recordedRequests` and `proxiedRequests`
 * itself, because it passes them to its two `RequestPanel` children. Zustand
 * therefore re-renders the GRID whenever either traffic array changes, and a
 * re-rendered parent re-renders all of its children — so before the panels were
 * memoized, a push that only changed the proxied traffic also re-rendered the
 * Log panel, the Expectations panel and the Received Requests panel, none of
 * whose data had moved. On a proxying server that is three wasted panel renders
 * every second, for as long as the dashboard is open.
 *
 * HOW THE RENDER COUNT IS TAKEN. All four panels call `useExpansion()` exactly
 * once, unconditionally, in their function bodies, so spying on that hook
 * counts panel renders precisely — 4 calls means the whole grid re-rendered, 1
 * means only the panel whose data changed did. This is a real render count, not
 * a proxy for one: `React.Profiler` was tried first and is useless here, because
 * its `onRender` fires for a subtree whose memoized root bailed out just as it
 * does for one that re-rendered.
 *
 * MEASURED (2026-09-21, jsdom, 100 items per panel, 400 B bodies, proxy-mode
 * workload — logs and proxied traffic churn, expectations and received traffic
 * static; mean ms per `applyMessage` with the whole grid mounted):
 *
 *   panels not memoized   50.06, 49.69 ms
 *   panels memoized       40.29, 39.77 ms      (-20%)
 *
 * Nothing displayed changes: each panel still subscribes to (or is handed)
 * exactly the state it renders, so its own data still re-renders it. The pair
 * of assertions below pins both halves of that — the panel whose data changed
 * MUST re-render, and the ones whose data did not MUST NOT.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, act, cleanup } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import DashboardGrid from '../components/DashboardGrid';
import * as expansionModule from '../hooks/useExpansion';
import { useDashboardStore } from '../store';
import type { WebSocketMessage } from '../types';
import { makeFrame, REST_BODY_BYTES } from '../__bench__/fixtures';

const PANEL_COUNT = 4; // Log, Expectations, Received Requests, Proxied Requests

/**
 * The three panels fed by TRAFFIC. `makeFrame` deliberately keeps
 * `activeExpectations` byte-identical across generations because expectations
 * are configuration, not traffic — they do not churn under load — so the
 * Expectations panel is expected to stay still even in the "everything moved"
 * case below.
 */
const TRAFFIC_PANELS = 3;

/**
 * Build a frame in which only the named panels carry new items; the rest are
 * byte-identical re-sends, which `reconcileByKey` collapses to the previous
 * array identity so their subscribers are not woken at all.
 */
function frameChanging(generation: number, churning: readonly string[]): WebSocketMessage {
  const moved = makeFrame(generation, 'all', REST_BODY_BYTES) as unknown as Record<string, unknown>;
  const still = makeFrame(0, 'idle', REST_BODY_BYTES) as unknown as Record<string, unknown>;
  const frame: Record<string, unknown> = {};
  for (const panel of ['logMessages', 'activeExpectations', 'recordedRequests', 'proxiedRequests']) {
    frame[panel] = churning.includes(panel) ? moved[panel] : still[panel];
  }
  return frame as unknown as WebSocketMessage;
}

function renderGrid(): void {
  render(
    <ThemeProvider theme={buildTheme('dark')}>
      <DashboardGrid />
    </ThemeProvider>,
  );
}

let panelRenders = 0;

beforeEach(() => {
  useDashboardStore.getState().clearUI();
  panelRenders = 0;
  const real = expansionModule.useExpansion;
  vi.spyOn(expansionModule, 'useExpansion').mockImplementation(() => {
    panelRenders++;
    return real();
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  cleanup();
});

const ALL_TRAFFIC = ['logMessages', 'recordedRequests', 'proxiedRequests'] as const;

describe('dashboard panel isolation', () => {
  it('a push that changes only the proxied traffic re-renders only that panel', () => {
    act(() => useDashboardStore.getState().applyMessage(frameChanging(0, [])));
    renderGrid();
    expect(panelRenders).toBe(PANEL_COUNT); // sanity: the counter sees all four

    panelRenders = 0;
    act(() => useDashboardStore.getState().applyMessage(frameChanging(1, ['proxiedRequests'])));

    // Exactly one: the Proxied Requests panel. Un-memoized this is 3 — the
    // grid's own subscription re-renders every child, and only the Expectations
    // panel escapes, because its data is static in this fixture.
    expect(panelRenders).toBe(1);
  });

  it('a push that changes nothing re-renders no panel', () => {
    act(() => useDashboardStore.getState().applyMessage(frameChanging(0, [])));
    renderGrid();

    panelRenders = 0;
    act(() => useDashboardStore.getState().applyMessage(frameChanging(0, [])));
    expect(panelRenders).toBe(0);
  });

  it('every panel whose data DID change still re-renders', () => {
    // The other half of the contract: memoization must not stop a panel
    // updating. Without this, "0 re-renders" could be satisfied by a panel that
    // has silently stopped showing new data.
    act(() => useDashboardStore.getState().applyMessage(frameChanging(0, [])));
    renderGrid();

    panelRenders = 0;
    act(() => useDashboardStore.getState().applyMessage(frameChanging(1, ALL_TRAFFIC)));
    expect(panelRenders).toBe(TRAFFIC_PANELS);
  });
});
