import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDebugMismatch } from '../hooks/useDebugMismatch';
import { useDashboardStore } from '../store';
import type { ConnectionParams } from '../hooks/useConnectionParams';

const params: ConnectionParams = { host: 'localhost', port: '1080', secure: false };

function stubFetch(response: {
  status?: number;
  body?: string;
  text?: () => Promise<string>;
}) {
  const status = response.status ?? 200;
  return vi.fn(async () => ({
    status,
    ok: status >= 200 && status < 300,
    text: response.text ?? (async () => response.body ?? ''),
  }));
}

beforeEach(() => {
  useDashboardStore.setState({
    debugMismatchOpen: false,
    debugMismatchResult: null,
    debugMismatchLoading: false,
    debugMismatchError: null,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('useDebugMismatch', () => {
  it('opens the dialog with the parsed result on a 2xx JSON response', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch({
        status: 200,
        body: JSON.stringify({ correlationId: 'abc', results: [] }),
      }),
    );

    const { result } = renderHook(() => useDebugMismatch(params));
    await act(async () => {
      await result.current.debugMismatch({ method: 'GET', path: '/x' });
    });

    const state = useDashboardStore.getState();
    expect(state.debugMismatchError).toBeNull();
    expect(state.debugMismatchOpen).toBe(true);
    expect(state.debugMismatchResult?.correlationId).toBe('abc');
    // The original request is attached for "Create Expectation".
    expect(state.debugMismatchResult?.unmatchedRequest).toEqual({
      method: 'GET',
      path: '/x',
    });
  });

  it('reports the real status for a non-2xx response with an empty body (not a connection failure)', async () => {
    vi.stubGlobal('fetch', stubFetch({ status: 404, body: '' }));

    const { result } = renderHook(() => useDebugMismatch(params));
    await act(async () => {
      await result.current.debugMismatch({ method: 'GET', path: '/x' });
    });

    const state = useDashboardStore.getState();
    expect(state.debugMismatchError).toBe('Request failed: 404');
    // Must NOT be misreported as a connection failure — the server responded.
    expect(state.debugMismatchError).not.toBe('Failed to connect to MockServer');
    expect(state.debugMismatchOpen).toBe(true);
    expect(state.debugMismatchResult).toBeNull();
  });

  it('reports status and body for a non-2xx response with a non-JSON (HTML) body', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch({ status: 500, body: '<html><body>Internal Server Error</body></html>' }),
    );

    const { result } = renderHook(() => useDebugMismatch(params));
    await act(async () => {
      await result.current.debugMismatch({ method: 'GET', path: '/x' });
    });

    const state = useDashboardStore.getState();
    expect(state.debugMismatchError).toContain('500');
    expect(state.debugMismatchError).toContain('Internal Server Error');
    expect(state.debugMismatchError).not.toBe('Failed to connect to MockServer');
  });

  it('surfaces a JSON {error} envelope on a non-2xx response', async () => {
    vi.stubGlobal(
      'fetch',
      stubFetch({ status: 400, body: JSON.stringify({ error: 'path must start with /' }) }),
    );

    const { result } = renderHook(() => useDebugMismatch(params));
    await act(async () => {
      await result.current.debugMismatch({ method: 'GET', path: 'x' });
    });

    expect(useDashboardStore.getState().debugMismatchError).toBe(
      'Request failed (400): path must start with /',
    );
  });

  it('reports a connection failure only when fetch itself rejects', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    const { result } = renderHook(() => useDebugMismatch(params));
    await act(async () => {
      await result.current.debugMismatch({ method: 'GET', path: '/x' });
    });

    expect(useDashboardStore.getState().debugMismatchError).toBe('Failed to connect to MockServer');
  });

  it('reports an unexpected-response error when a 2xx body is not valid JSON', async () => {
    vi.stubGlobal('fetch', stubFetch({ status: 200, body: 'not json' }));

    const { result } = renderHook(() => useDebugMismatch(params));
    await act(async () => {
      await result.current.debugMismatch({ method: 'GET', path: '/x' });
    });

    const state = useDashboardStore.getState();
    expect(state.debugMismatchError).toBe('Received an unexpected response from MockServer');
    expect(state.debugMismatchOpen).toBe(true);
    expect(state.debugMismatchResult).toBeNull();
  });
});
