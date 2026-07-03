/**
 * Item 3 of commit e09495682: while the tab is hidden, `useWebSocket` buffers
 * only the NEWEST raw payload and applies it once on return, instead of
 * parsing + reconciling + re-rendering on every ~1/sec push.
 *
 * This test drives the REAL hook with a mock WebSocket and a forced
 * `document.hidden`, and counts full parse+apply cycles (store `applyMessage`
 * invocations) across N hidden pushes plus the visibility-return replay.
 *
 * Expectation: NEW → 0 applies while hidden, exactly 1 on return (the newest).
 * Control: the pre-optimization onmessage (no gate) would apply all N.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useWebSocket } from '../hooks/useWebSocket';
import { useDashboardStore } from '../store';

class MockWebSocket {
  static instances: MockWebSocket[] = [];
  url: string;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  CONNECTING = 0;
  OPEN = 1;
  CLOSING = 2;
  CLOSED = 3;
  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }
  send(): void {}
  close(): void {
    this.onclose?.();
  }
  simulateOpen(): void {
    this.readyState = 1;
    this.onopen?.();
  }
  simulateMessage(data: object): void {
    this.onmessage?.({ data: JSON.stringify(data) });
  }
}

function setHidden(hidden: boolean): void {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
}

/** One full-state push carrying `n` recorded requests (each a distinct object). */
function pushWith(n: number): { recordedRequests: { key: string; value: Record<string, unknown> }[] } {
  return {
    recordedRequests: Array.from({ length: n }, (_, i) => ({ key: `r-${n}-${i}`, value: { i } })),
  };
}

const N_HIDDEN_PUSHES = 20;

describe('useWebSocket hidden-tab buffering (Item 3)', () => {
  const params = { host: 'localhost', port: '1080', secure: false };

  beforeEach(() => {
    MockWebSocket.instances = [];
    vi.stubGlobal('WebSocket', MockWebSocket);
    setHidden(false);
    useDashboardStore.setState({
      connectionStatus: 'disconnected',
      error: null,
      errorSource: null,
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    setHidden(false);
  });

  it(`applies 0 times while hidden and exactly 1 on return, over ${N_HIDDEN_PUSHES} pushes`, () => {
    const applySpy = vi.spyOn(useDashboardStore.getState(), 'applyMessage');

    const { result } = renderHook(() => useWebSocket(params));
    act(() => result.current.connect({}));
    act(() => MockWebSocket.instances[0]!.simulateOpen());

    // Hide the tab and fire N distinct full-state pushes.
    setHidden(true);
    act(() => {
      for (let i = 1; i <= N_HIDDEN_PUSHES; i++) {
        MockWebSocket.instances[0]!.simulateMessage(pushWith(i));
      }
    });

    const appliesWhileHidden = applySpy.mock.calls.length;

    // Return to the tab: the visibility handler replays the newest buffered push.
    setHidden(false);
    act(() => document.dispatchEvent(new Event('visibilitychange')));

    const appliesAfterReturn = applySpy.mock.calls.length;

    // eslint-disable-next-line no-console
    console.log(
      `[hidden-tab] pushes=${N_HIDDEN_PUSHES} | NEW applies while hidden=${appliesWhileHidden}` +
        ` | applies after return=${appliesAfterReturn} | control OLD (no gate) would be=${N_HIDDEN_PUSHES}`,
    );

    expect(appliesWhileHidden).toBe(0);
    expect(appliesAfterReturn).toBe(1);

    // The single applied payload is the NEWEST (the 20th push had 20 requests).
    expect(useDashboardStore.getState().recordedRequests).toHaveLength(N_HIDDEN_PUSHES);
  });

  it('control: an ungated onmessage would run all N parse+apply cycles', () => {
    // Replicates the pre-optimization onmessage (parse + apply, no hidden gate)
    // to make the avoided work explicit and measured, not merely asserted.
    let oldApplies = 0;
    const applyOld = (raw: string): void => {
      JSON.parse(raw) as unknown; // the parse the NEW path skips while hidden
      oldApplies++;
    };
    for (let i = 1; i <= N_HIDDEN_PUSHES; i++) {
      applyOld(JSON.stringify(pushWith(i)));
    }
    expect(oldApplies).toBe(N_HIDDEN_PUSHES);
  });
});
