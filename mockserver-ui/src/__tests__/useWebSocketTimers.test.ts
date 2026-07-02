import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useWebSocket } from '../hooks/useWebSocket';
import { useDashboardStore } from '../store';

class MockWebSocket {
  static instances: MockWebSocket[] = [];
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSING = 2;
  static CLOSED = 3;

  url: string;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  sentMessages: string[] = [];
  closed = false;

  CONNECTING = 0;
  OPEN = 1;
  CLOSING = 2;
  CLOSED = 3;

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  send(data: string) {
    this.sentMessages.push(data);
  }

  close() {
    this.closed = true;
    this.onclose?.();
  }

  simulateOpen() {
    this.readyState = 1;
    this.onopen?.();
  }

  simulateClose() {
    this.readyState = 3;
    this.onclose?.();
  }
}

describe('useWebSocket timer behaviour', () => {
  const defaultParams = { host: 'localhost', port: '1080', secure: false };

  beforeEach(() => {
    vi.useFakeTimers();
    MockWebSocket.instances = [];
    vi.stubGlobal('WebSocket', MockWebSocket);
    useDashboardStore.setState({
      connectionStatus: 'disconnected',
      error: null,
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('schedules a reconnect after onclose fires', () => {
    const { result } = renderHook(() => useWebSocket(defaultParams));

    act(() => {
      result.current.connect({});
    });
    const ws = MockWebSocket.instances[0]!;
    act(() => {
      ws.simulateOpen();
    });

    // Simulate server-side close
    act(() => {
      ws.simulateClose();
    });
    expect(useDashboardStore.getState().connectionStatus).toBe('disconnected');

    // Before the timer fires, no new WebSocket should be created
    expect(MockWebSocket.instances).toHaveLength(1);

    // Advance past the first reconnect delay (1 * 3000 ms)
    act(() => {
      vi.advanceTimersByTime(3000);
    });

    // A new WebSocket should have been created for the reconnect
    expect(MockWebSocket.instances).toHaveLength(2);
  });

  it('reconnects with the last filter set via sendFilter, not the stale initial filter', () => {
    const { result } = renderHook(() => useWebSocket(defaultParams));

    act(() => {
      result.current.connect({});
    });
    const first = MockWebSocket.instances[0]!;
    act(() => {
      first.simulateOpen();
    });
    // The initial open sends the empty filter.
    expect(JSON.parse(first.sentMessages[0]!)).toEqual({});

    // The user narrows the filter while the socket is OPEN; sendFilter updates the live filter
    // in place (no reconnect) rather than re-connecting.
    const filter = { path: '/api/.*' };
    act(() => {
      result.current.sendFilter(filter);
    });
    expect(JSON.parse(first.sentMessages[1]!)).toEqual(filter);

    // The server restarts: the socket drops and a reconnect is scheduled.
    act(() => {
      first.simulateClose();
    });
    act(() => {
      vi.advanceTimersByTime(3000);
    });

    // The reconnected socket must re-send the CURRENT filter — not the {} it first connected
    // with — otherwise the panels would silently stream unfiltered data after a restart.
    expect(MockWebSocket.instances).toHaveLength(2);
    const second = MockWebSocket.instances[1]!;
    act(() => {
      second.simulateOpen();
    });
    expect(JSON.parse(second.sentMessages[0]!)).toEqual(filter);
  });

  it('uses capped backoff: delay = min(count,5) * RECONNECT_DELAY_MS', () => {
    const { result } = renderHook(() => useWebSocket(defaultParams));

    act(() => {
      result.current.connect({});
    });

    // First close: reconnectCount becomes 1, delay = 1 * 3000
    act(() => {
      MockWebSocket.instances[0]!.simulateOpen();
    });
    act(() => {
      MockWebSocket.instances[0]!.simulateClose();
    });

    // Advance to just before the 3000ms mark — no reconnect yet
    act(() => {
      vi.advanceTimersByTime(2999);
    });
    expect(MockWebSocket.instances).toHaveLength(1);

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(MockWebSocket.instances).toHaveLength(2);

    // Second close: reconnectCount becomes 2, delay = 2 * 3000 = 6000
    act(() => {
      MockWebSocket.instances[1]!.simulateClose();
    });
    act(() => {
      vi.advanceTimersByTime(5999);
    });
    expect(MockWebSocket.instances).toHaveLength(2);
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(MockWebSocket.instances).toHaveLength(3);

    // Third close: reconnectCount becomes 3, delay = 3 * 3000 = 9000
    act(() => {
      MockWebSocket.instances[2]!.simulateClose();
    });
    act(() => {
      vi.advanceTimersByTime(9000);
    });
    expect(MockWebSocket.instances).toHaveLength(4);

    // Cap test: after 5+ closes, delay should cap at 5 * 3000 = 15000
    act(() => {
      MockWebSocket.instances[3]!.simulateClose();
    });
    act(() => {
      vi.advanceTimersByTime(12000); // 4 * 3000
    });
    expect(MockWebSocket.instances).toHaveLength(5);

    act(() => {
      MockWebSocket.instances[4]!.simulateClose();
    });
    // count = 5 → delay = 5 * 3000 = 15000
    act(() => {
      vi.advanceTimersByTime(14999);
    });
    expect(MockWebSocket.instances).toHaveLength(5);
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(MockWebSocket.instances).toHaveLength(6);

    // count = 6 → still capped at 5 * 3000 = 15000
    act(() => {
      MockWebSocket.instances[5]!.simulateClose();
    });
    act(() => {
      vi.advanceTimersByTime(14999);
    });
    expect(MockWebSocket.instances).toHaveLength(6);
    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(MockWebSocket.instances).toHaveLength(7);
  });

  it('shows error banner after the 2nd failed reconnect attempt', () => {
    const { result } = renderHook(() => useWebSocket(defaultParams));

    act(() => {
      result.current.connect({});
    });
    act(() => {
      MockWebSocket.instances[0]!.simulateOpen();
    });

    // First close — reconnectCount goes to 1, no error yet
    act(() => {
      MockWebSocket.instances[0]!.simulateClose();
    });
    expect(useDashboardStore.getState().error).toBeNull();

    // Reconnect fires
    act(() => {
      vi.advanceTimersByTime(3000);
    });

    // Second close — reconnectCount goes to 2, error should appear
    act(() => {
      MockWebSocket.instances[1]!.simulateClose();
    });
    expect(useDashboardStore.getState().error).toContain('Connection lost');
    expect(useDashboardStore.getState().error).toContain('localhost:1080');
  });

  it('clears error and resets counter when a reconnect succeeds', () => {
    const { result } = renderHook(() => useWebSocket(defaultParams));

    act(() => {
      result.current.connect({});
    });
    act(() => {
      MockWebSocket.instances[0]!.simulateOpen();
    });

    // Two closes to trigger the error banner
    act(() => {
      MockWebSocket.instances[0]!.simulateClose();
    });
    act(() => {
      vi.advanceTimersByTime(3000);
    });
    act(() => {
      MockWebSocket.instances[1]!.simulateClose();
    });
    expect(useDashboardStore.getState().error).toContain('Connection lost');

    // Reconnect fires, and this time it opens successfully
    act(() => {
      vi.advanceTimersByTime(6000);
    });
    act(() => {
      MockWebSocket.instances[2]!.simulateOpen();
    });

    expect(useDashboardStore.getState().error).toBeNull();
    expect(useDashboardStore.getState().connectionStatus).toBe('connected');
  });

  it('disconnect cancels a pending reconnect timer', () => {
    const { result } = renderHook(() => useWebSocket(defaultParams));

    act(() => {
      result.current.connect({});
    });
    act(() => {
      MockWebSocket.instances[0]!.simulateOpen();
    });
    act(() => {
      MockWebSocket.instances[0]!.simulateClose();
    });

    // A reconnect is now scheduled. Disconnect should cancel it.
    act(() => {
      result.current.disconnect();
    });

    act(() => {
      vi.advanceTimersByTime(30000);
    });

    // No new WebSocket should have been created after disconnect
    expect(MockWebSocket.instances).toHaveLength(1);
  });
});
