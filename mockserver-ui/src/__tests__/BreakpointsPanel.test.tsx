import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import BreakpointsPanel from '../components/BreakpointsPanel';
import { _resetBreakpointCallbackClient } from '../lib/breakpointCallbackClient';
import type { BreakpointMatcherListResponse } from '../lib/breakpoints';
import { useDashboardStore } from '../store';

const params = { host: '127.0.0.1', port: '1080', secure: false };

// ---------------------------------------------------------------------------
// MockWebSocket — needed because BreakpointsPanel creates a callback WS
// ---------------------------------------------------------------------------

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
    this.readyState = 3;
    // Don't call onclose here to avoid triggering reconnect in tests
  }

  simulateOpen() {
    this.readyState = 1;
    this.onopen?.();
  }

  simulateMessage(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) });
  }
}

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <BreakpointsPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

/** Get the callback WS instance (the one pointed at /_mockserver_callback_websocket). */
function getCallbackWs(): MockWebSocket {
  const ws = MockWebSocket.instances.find((w) => w.url.includes('_mockserver_callback_websocket'));
  if (!ws) throw new Error('No callback WS instance found');
  return ws;
}

/** Simulate the callback WS connecting and receiving a clientId. */
function connectCallbackWs(clientId = 'test-client-id'): MockWebSocket {
  const ws = getCallbackWs();
  ws.simulateOpen();
  ws.simulateMessage({
    type: 'org.mockserver.serialization.model.WebSocketClientIdDTO',
    value: JSON.stringify({ clientId }),
  });
  return ws;
}

const emptyMatchers: BreakpointMatcherListResponse = { matchers: [] };

beforeEach(() => {
  MockWebSocket.instances = [];
  vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket);

  // Reset the singleton callback client
  _resetBreakpointCallbackClient();

  // Clear any cross-view breakpoint prefill handoff left over from another test.
  useDashboardStore.setState({ pendingBreakpointPrefill: null });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// Matchers tab
// ---------------------------------------------------------------------------

describe('BreakpointsPanel — Matchers tab', () => {
  it('renders the panel title and WS indicator', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();
    expect(screen.getByText('Breakpoints')).toBeInTheDocument();
    expect(screen.getByTestId('ws-indicator')).toBeInTheDocument();
  });

  it('shows matcher registration form', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();
    expect(screen.getByText('Register a New Breakpoint Matcher')).toBeInTheDocument();
    expect(screen.getByLabelText(/Path/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Register Matcher/ })).toBeInTheDocument();
  });

  it('shows empty matchers state', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText(/No matchers registered/)).toBeInTheDocument();
    });
  });

  it('lists registered matchers', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200,
      json: async () => ({
        matchers: [{
          id: 'matcher-123',
          httpRequest: { method: 'GET', path: '/api/.*' },
          phases: ['REQUEST', 'RESPONSE'],
          clientId: 'client-abc-defgh',
        }],
      }),
    })));

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('matcher-123')).toBeInTheDocument();
    });
    expect(screen.getByText('GET /api/.*')).toBeInTheDocument();
    expect(screen.getByText('REQUEST')).toBeInTheDocument();
    expect(screen.getByText('RESPONSE')).toBeInTheDocument();
  });

  it('calls remove endpoint when Remove button is clicked', async () => {
    const user = userEvent.setup();
    let removeCalled = false;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (String(url).includes('/matcher/remove') && init?.method === 'PUT') {
        removeCalled = true;
        return { ok: true, status: 200, json: async () => ({ status: 'removed', id: 'matcher-1' }) };
      }
      return {
        ok: true, status: 200,
        json: async () => ({
          matchers: [{ id: 'matcher-1', httpRequest: { path: '/test' }, phases: ['REQUEST'] }],
        }),
      };
    }));

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('matcher-1')).toBeInTheDocument();
    });

    const removeBtn = screen.getByRole('button', { name: /Remove matcher-1/ });
    await user.click(removeBtn);

    await waitFor(() => {
      expect(removeCalled).toBe(true);
    });
  });

  it('registers a matcher via the form', async () => {
    const user = userEvent.setup();
    let registerCalled = false;
    let registerBody: Record<string, unknown> | null = null;

    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const urlStr = String(url);
      if (urlStr.endsWith('/mockserver/breakpoint/matcher') && init?.method === 'PUT') {
        registerCalled = true;
        registerBody = JSON.parse(init.body as string) as Record<string, unknown>;
        return { ok: true, status: 200, json: async () => ({ id: 'new-1', phases: ['REQUEST', 'RESPONSE'] }) };
      }
      return { ok: true, status: 200, json: async () => emptyMatchers };
    }));

    renderPanel();

    // Connect the callback WS so the clientId is available
    await waitFor(() => {
      expect(MockWebSocket.instances.length).toBeGreaterThan(0);
    });
    connectCallbackWs('client-for-register');

    // Fill in the path
    const pathInput = screen.getByLabelText(/Path/);
    await user.clear(pathInput);
    await user.type(pathInput, '/api/test');

    // Click register
    const registerBtn = screen.getByRole('button', { name: /Register Matcher/ });
    await user.click(registerBtn);

    await waitFor(() => {
      expect(registerCalled).toBe(true);
    });

    expect(registerBody).not.toBeNull();
    expect(registerBody!.clientId).toBe('client-for-register');
    expect((registerBody!.httpRequest as Record<string, unknown>).path).toBe('/api/test');
    expect(registerBody!.phases).toEqual(expect.arrayContaining(['REQUEST', 'RESPONSE']));
  });

  it('shows error when registering without WS connection', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();

    // Don't connect the WS - button should be disabled
    const registerBtn = screen.getByRole('button', { name: /Register Matcher/ });
    expect(registerBtn).toBeDisabled();
  });

  it('shows matchers error for server-side errors', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new Error('Connection refused');
    }));

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText(/Could not load matchers/)).toBeInTheDocument();
    });
    expect(screen.getByText('Connection refused')).toBeInTheDocument();
  });

  it('auto-refreshes the matcher list on an interval without a manual click', async () => {
    vi.useFakeTimers();
    try {
      const fetchMock = vi.fn(async () => ({
        ok: true, status: 200, json: async () => emptyMatchers,
      }));
      vi.stubGlobal('fetch', fetchMock);

      renderPanel();

      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
      await vi.advanceTimersByTimeAsync(5000);
      await vi.waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(1));
    } finally {
      vi.useRealTimers();
    }
  });

  it('shows unavailable message for 404', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      json: async () => ({}),
    })));

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText(/Breakpoint matchers not available/)).toBeInTheDocument();
    });
  });
});

// ---------------------------------------------------------------------------
// Live Exchanges tab
// ---------------------------------------------------------------------------

describe('BreakpointsPanel — Live Exchanges tab', () => {
  async function switchToExchangesTab() {
    const user = userEvent.setup();
    const tab = screen.getByRole('tab', { name: /Live Exchanges/ });
    await user.click(tab);
  }

  it('shows empty state', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();
    await switchToExchangesTab();

    expect(screen.getByText(/No paused exchanges/)).toBeInTheDocument();
  });

  it('shows paused request from WS push', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();

    // Connect WS and push a paused request
    await waitFor(() => {
      expect(MockWebSocket.instances.length).toBeGreaterThan(0);
    });
    const ws = connectCallbackWs();

    // Push a paused request
    ws.simulateMessage({
      type: 'org.mockserver.model.HttpRequest',
      value: JSON.stringify({
        method: 'GET',
        path: '/api/users',
        headers: {
          'WebSocketCorrelationId': ['corr-1'],
          'X-MockServer-BreakpointId': ['bp-1'],
        },
      }),
    });

    await switchToExchangesTab();

    await waitFor(() => {
      expect(screen.getByText('GET')).toBeInTheDocument();
    });
    expect(screen.getByText('/api/users')).toBeInTheDocument();
  });

  it('resolves a request with Continue by sending HttpRequest over WS', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();

    await waitFor(() => {
      expect(MockWebSocket.instances.length).toBeGreaterThan(0);
    });
    const ws = connectCallbackWs();

    // Push a paused request
    ws.simulateMessage({
      type: 'org.mockserver.model.HttpRequest',
      value: JSON.stringify({
        method: 'POST',
        path: '/test',
        headers: {
          'WebSocketCorrelationId': ['corr-2'],
          'X-MockServer-BreakpointId': ['bp-2'],
        },
      }),
    });

    await switchToExchangesTab();

    await waitFor(() => {
      expect(screen.getByText('POST')).toBeInTheDocument();
    });

    // Click Continue
    const continueBtn = screen.getAllByRole('button').find((b) => b.getAttribute('aria-label')?.startsWith('Continue'));
    expect(continueBtn).toBeDefined();
    await user.click(continueBtn!);

    // Verify WS message was sent
    expect(ws.sentMessages.length).toBeGreaterThan(0);
    const envelope = JSON.parse(ws.sentMessages[ws.sentMessages.length - 1]!);
    expect(envelope.type).toBe('org.mockserver.model.HttpRequest');
    const inner = JSON.parse(envelope.value);
    expect(inner.headers['WebSocketCorrelationId']).toEqual(['corr-2']);

    // Item should be removed
    await waitFor(() => {
      expect(screen.getByText(/No paused exchanges/)).toBeInTheDocument();
    });
  });

  it('resolves a request with Abort by sending HttpResponse over WS', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();

    await waitFor(() => {
      expect(MockWebSocket.instances.length).toBeGreaterThan(0);
    });
    const ws = connectCallbackWs();

    ws.simulateMessage({
      type: 'org.mockserver.model.HttpRequest',
      value: JSON.stringify({
        method: 'DELETE',
        path: '/remove',
        headers: {
          'WebSocketCorrelationId': ['corr-3'],
          'X-MockServer-BreakpointId': ['bp-3'],
        },
      }),
    });

    await switchToExchangesTab();

    await waitFor(() => {
      expect(screen.getByText('DELETE')).toBeInTheDocument();
    });

    // Click Abort
    const abortBtn = screen.getAllByRole('button').find((b) => b.getAttribute('aria-label')?.startsWith('Abort'));
    expect(abortBtn).toBeDefined();
    await user.click(abortBtn!);

    // Verify HttpResponse was sent (abort = statusCode present)
    const envelope = JSON.parse(ws.sentMessages[ws.sentMessages.length - 1]!);
    expect(envelope.type).toBe('org.mockserver.model.HttpResponse');
    const inner = JSON.parse(envelope.value);
    expect(inner.statusCode).toBe(503);
  });

  it('renders a disabled, clearly-labelled Abort for RESPONSE-phase items (cannot abort a response)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();

    await waitFor(() => {
      expect(MockWebSocket.instances.length).toBeGreaterThan(0);
    });
    const ws = connectCallbackWs();

    // Push a RESPONSE-phase paused item (request + response pair).
    ws.simulateMessage({
      type: 'org.mockserver.model.HttpRequestAndHttpResponse',
      value: JSON.stringify({
        httpRequest: {
          method: 'GET',
          path: '/resp',
          headers: {
            'WebSocketCorrelationId': ['corr-resp-1'],
            'X-MockServer-BreakpointId': ['bp-resp-1'],
          },
        },
        httpResponse: { statusCode: 200, reasonPhrase: 'OK' },
      }),
    });

    await switchToExchangesTab();

    await waitFor(() => {
      expect(screen.getByText('RESPONSE')).toBeInTheDocument();
    });

    // The Abort control for a response is rendered but disabled and labelled as
    // not applicable, so it cannot silently behave like Continue.
    const abortBtn = screen
      .getAllByRole('button')
      .find((b) => b.getAttribute('aria-label')?.startsWith('Abort'));
    expect(abortBtn).toBeDefined();
    expect(abortBtn).toBeDisabled();
    expect(abortBtn!.getAttribute('aria-label')).toMatch(/not applicable for responses/);

    // Continue must still be enabled — the legitimate way to resolve a response.
    const continueBtn = screen
      .getAllByRole('button')
      .find((b) => b.getAttribute('aria-label')?.startsWith('Continue'));
    expect(continueBtn).toBeDefined();
    expect(continueBtn).not.toBeDisabled();
  });

  it('opens modify dialog and sends modified request over WS', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();

    await waitFor(() => {
      expect(MockWebSocket.instances.length).toBeGreaterThan(0);
    });
    const ws = connectCallbackWs();

    ws.simulateMessage({
      type: 'org.mockserver.model.HttpRequest',
      value: JSON.stringify({
        method: 'GET',
        path: '/original',
        headers: {
          'WebSocketCorrelationId': ['corr-4'],
          'X-MockServer-BreakpointId': ['bp-4'],
        },
      }),
    });

    await switchToExchangesTab();

    await waitFor(() => {
      expect(screen.getByText('GET')).toBeInTheDocument();
    });

    // Click Modify
    const modifyBtn = screen.getAllByRole('button').find((b) => b.getAttribute('aria-label')?.startsWith('Modify'));
    expect(modifyBtn).toBeDefined();
    await user.click(modifyBtn!);

    await waitFor(() => {
      expect(screen.getByText('Modify Request')).toBeInTheDocument();
    });

    // Edit the JSON
    const textarea = screen.getByRole('textbox');
    fireEvent.change(textarea, { target: { value: '{"method":"POST","path":"/modified"}' } });

    const sendBtn = screen.getByRole('button', { name: /Send Modified/ });
    await user.click(sendBtn);

    // Verify WS message
    const envelope = JSON.parse(ws.sentMessages[ws.sentMessages.length - 1]!);
    expect(envelope.type).toBe('org.mockserver.model.HttpRequest');
    const inner = JSON.parse(envelope.value);
    expect(inner.method).toBe('POST');
    expect(inner.path).toBe('/modified');
  });

  it('shows Invalid JSON error in modify dialog', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();

    await waitFor(() => {
      expect(MockWebSocket.instances.length).toBeGreaterThan(0);
    });
    const ws = connectCallbackWs();

    ws.simulateMessage({
      type: 'org.mockserver.model.HttpRequest',
      value: JSON.stringify({
        method: 'GET',
        path: '/test',
        headers: {
          'WebSocketCorrelationId': ['corr-5'],
          'X-MockServer-BreakpointId': ['bp-5'],
        },
      }),
    });

    await switchToExchangesTab();
    await waitFor(() => { expect(screen.getByText('GET')).toBeInTheDocument(); });

    const modifyBtn = screen.getAllByRole('button').find((b) => b.getAttribute('aria-label')?.startsWith('Modify'));
    await user.click(modifyBtn!);

    await waitFor(() => { expect(screen.getByText('Modify Request')).toBeInTheDocument(); });

    const textarea = screen.getByRole('textbox');
    fireEvent.change(textarea, { target: { value: 'not valid json' } });

    const sendBtn = screen.getByRole('button', { name: /Send Modified/ });
    await user.click(sendBtn);

    await waitFor(() => {
      expect(screen.getByText('Invalid JSON')).toBeInTheDocument();
    });
  });
});

// ---------------------------------------------------------------------------
// Live Streams tab
// ---------------------------------------------------------------------------

describe('BreakpointsPanel — Live Streams tab', () => {
  async function switchToStreamsTab() {
    const user = userEvent.setup();
    const tab = screen.getByRole('tab', { name: /Live Streams/ });
    await user.click(tab);
  }

  it('shows empty state', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();
    await switchToStreamsTab();

    expect(screen.getByText(/No paused stream frames/)).toBeInTheDocument();
  });

  it('shows paused frame from WS push', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();

    await waitFor(() => {
      expect(MockWebSocket.instances.length).toBeGreaterThan(0);
    });
    const ws = connectCallbackWs();

    ws.simulateMessage({
      type: 'org.mockserver.serialization.model.PausedStreamFrameDTO',
      value: JSON.stringify({
        correlationId: 'frame-1',
        streamId: 'stream-abc',
        sequenceNumber: 0,
        direction: 'OUTBOUND',
        phase: 'RESPONSE_STREAM',
        body: btoa('data: hello'),
        requestMethod: 'GET',
        requestPath: '/events',
        breakpointId: 'bp-stream',
      }),
    });

    await switchToStreamsTab();

    await waitFor(() => {
      expect(screen.getByText('stream-abc')).toBeInTheDocument();
    });
    expect(screen.getByText('#0')).toBeInTheDocument();
    expect(screen.getByText('GET')).toBeInTheDocument();
    expect(screen.getByText('/events')).toBeInTheDocument();
    expect(screen.getByText('Outbound')).toBeInTheDocument();
  });

  it('resolves a frame with Continue', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();

    await waitFor(() => {
      expect(MockWebSocket.instances.length).toBeGreaterThan(0);
    });
    const ws = connectCallbackWs();

    ws.simulateMessage({
      type: 'org.mockserver.serialization.model.PausedStreamFrameDTO',
      value: JSON.stringify({
        correlationId: 'frame-cont-1',
        streamId: 's1',
        sequenceNumber: 0,
        direction: 'OUTBOUND',
        phase: 'RESPONSE_STREAM',
        body: btoa('test'),
        breakpointId: 'bp-1',
      }),
    });

    await switchToStreamsTab();
    await waitFor(() => { expect(screen.getByText('s1')).toBeInTheDocument(); });

    const continueBtn = screen.getAllByRole('button').find((b) => b.getAttribute('aria-label')?.startsWith('Continue'));
    await user.click(continueBtn!);

    // Verify decision sent
    const envelope = JSON.parse(ws.sentMessages[ws.sentMessages.length - 1]!);
    expect(envelope.type).toBe('org.mockserver.serialization.model.StreamFrameDecisionDTO');
    const inner = JSON.parse(envelope.value);
    expect(inner.correlationId).toBe('frame-cont-1');
    expect(inner.action).toBe('CONTINUE');

    // Frame should be removed from list
    await waitFor(() => {
      expect(screen.getByText(/No paused stream frames/)).toBeInTheDocument();
    });
  });

  it('resolves a frame with Drop', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();
    await waitFor(() => { expect(MockWebSocket.instances.length).toBeGreaterThan(0); });
    const ws = connectCallbackWs();

    ws.simulateMessage({
      type: 'org.mockserver.serialization.model.PausedStreamFrameDTO',
      value: JSON.stringify({
        correlationId: 'frame-drop-1',
        streamId: 's2',
        sequenceNumber: 0,
        direction: 'INBOUND',
        phase: 'INBOUND_STREAM',
        body: btoa('test'),
        breakpointId: 'bp-2',
      }),
    });

    await switchToStreamsTab();
    await waitFor(() => { expect(screen.getByText('s2')).toBeInTheDocument(); });

    const dropBtn = screen.getAllByRole('button').find((b) => b.getAttribute('aria-label')?.startsWith('Drop'));
    await user.click(dropBtn!);

    const envelope = JSON.parse(ws.sentMessages[ws.sentMessages.length - 1]!);
    const inner = JSON.parse(envelope.value);
    expect(inner.action).toBe('DROP');
    expect(inner.correlationId).toBe('frame-drop-1');
  });

  it('resolves a frame with Close', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();
    await waitFor(() => { expect(MockWebSocket.instances.length).toBeGreaterThan(0); });
    const ws = connectCallbackWs();

    ws.simulateMessage({
      type: 'org.mockserver.serialization.model.PausedStreamFrameDTO',
      value: JSON.stringify({
        correlationId: 'frame-close-1',
        streamId: 's3',
        sequenceNumber: 0,
        direction: 'OUTBOUND',
        phase: 'RESPONSE_STREAM',
        body: btoa('test'),
        breakpointId: 'bp-3',
      }),
    });

    await switchToStreamsTab();
    await waitFor(() => { expect(screen.getByText('s3')).toBeInTheDocument(); });

    const closeBtn = screen.getAllByRole('button').find((b) => b.getAttribute('aria-label')?.startsWith('Close'));
    await user.click(closeBtn!);

    const envelope = JSON.parse(ws.sentMessages[ws.sentMessages.length - 1]!);
    const inner = JSON.parse(envelope.value);
    expect(inner.action).toBe('CLOSE');
  });

  it('shows Inbound direction chip for INBOUND frames', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();
    await waitFor(() => { expect(MockWebSocket.instances.length).toBeGreaterThan(0); });
    const ws = connectCallbackWs();

    ws.simulateMessage({
      type: 'org.mockserver.serialization.model.PausedStreamFrameDTO',
      value: JSON.stringify({
        correlationId: 'frame-dir-1',
        streamId: 'ws-in',
        sequenceNumber: 0,
        direction: 'INBOUND',
        phase: 'INBOUND_STREAM',
        body: btoa('msg'),
        breakpointId: 'bp-dir',
      }),
    });

    await switchToStreamsTab();
    await waitFor(() => { expect(screen.getByText('ws-in')).toBeInTheDocument(); });
    expect(screen.getByText('Inbound')).toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Sort-by-request-timestamp (unit test of the sort logic)
// ---------------------------------------------------------------------------

describe('BreakpointsPanel — sort by requestTimestamp', () => {
  async function switchToExchangesTab() {
    const user = userEvent.setup();
    const tab = screen.getByRole('tab', { name: /Live Exchanges/ });
    await user.click(tab);
  }

  it('sorts exchanges by server requestTimestamp, not client receivedAt', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    renderPanel();

    // Connect WS
    await waitFor(() => { expect(MockWebSocket.instances.length).toBeGreaterThan(0); });
    const ws = connectCallbackWs();

    // Push two REQUEST-phase items with reversed arrival order vs timestamp.
    // Item 1 arrives first but has requestTimestamp=2000 (later request).
    ws.simulateMessage({
      type: 'org.mockserver.model.HttpRequest',
      value: JSON.stringify({
        method: 'GET',
        path: '/api/later-request',
        headers: {
          'WebSocketCorrelationId': ['corr-sort-2'],
          'X-MockServer-BreakpointId': ['bp-s2'],
          'X-MockServer-RequestTimestamp': ['2000'],
        },
      }),
    });

    // Item 2 arrives second but has requestTimestamp=1000 (earlier request).
    ws.simulateMessage({
      type: 'org.mockserver.model.HttpRequest',
      value: JSON.stringify({
        method: 'POST',
        path: '/api/earlier-request',
        headers: {
          'WebSocketCorrelationId': ['corr-sort-1'],
          'X-MockServer-BreakpointId': ['bp-s1'],
          'X-MockServer-RequestTimestamp': ['1000'],
        },
      }),
    });

    // Switch to Live Exchanges tab
    await switchToExchangesTab();

    // Wait for items to appear (path column shows the request path for REQUEST items)
    await waitFor(() => {
      expect(screen.getByText('/api/earlier-request')).toBeInTheDocument();
      expect(screen.getByText('/api/later-request')).toBeInTheDocument();
    });

    // Verify order: find all table rows and collect paths
    const allRows = document.querySelectorAll('tbody tr');
    const rowPaths: string[] = [];
    allRows.forEach(row => {
      const cells = row.querySelectorAll('td');
      cells.forEach(cell => {
        const text = cell.textContent || '';
        if (text.includes('/api/earlier-request') || text.includes('/api/later-request')) {
          rowPaths.push(text);
        }
      });
    });

    // The item with earlier requestTimestamp should come first in the table
    const earlierIdx = rowPaths.findIndex(p => p.includes('/api/earlier-request'));
    const laterIdx = rowPaths.findIndex(p => p.includes('/api/later-request'));
    expect(earlierIdx).toBeGreaterThanOrEqual(0);
    expect(laterIdx).toBeGreaterThanOrEqual(0);
    expect(earlierIdx).toBeLessThan(laterIdx);
  });
});

// ---------------------------------------------------------------------------
// "Set breakpoint" handoff — the panel pre-fills the form from a log row
// ---------------------------------------------------------------------------

describe('BreakpointsPanel — "Set breakpoint" prefill from a log row', () => {
  it('pre-fills the matcher form method + path from the store handoff and lands on the Matchers tab', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    // Simulate a click on a log row's "Set breakpoint": the store action seeds
    // the prefill and switches the view (here we only mount the panel directly).
    useDashboardStore.getState().setBreakpointPrefill({ method: 'POST', path: '/api/orders' });

    renderPanel();

    // The path field is populated from the handoff...
    await waitFor(() => {
      expect(screen.getByLabelText(/Path/)).toHaveValue('/api/orders');
    });
    // ...and the recognized method is selected on the (hidden) select input.
    const methodInput = document.querySelector('input[name="Method"], input[aria-hidden]') as HTMLInputElement | null;
    // The MUI select stores its value on a hidden input; assert the visible text instead.
    expect(screen.getByText('POST')).toBeInTheDocument();
    void methodInput;

    // The Matchers tab (index 0) is selected so the seeded form is visible.
    const matchersTab = screen.getByRole('tab', { name: /Matchers/ });
    expect(matchersTab).toHaveAttribute('aria-selected', 'true');

    // The handoff is consumed exactly once.
    expect(useDashboardStore.getState().pendingBreakpointPrefill).toBeNull();
  });

  it('ignores an unrecognized HTTP method but still applies the path', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true, status: 200, json: async () => emptyMatchers,
    })));

    useDashboardStore.getState().setBreakpointPrefill({ method: 'PROPFIND', path: '/dav' });

    renderPanel();

    await waitFor(() => {
      expect(screen.getByLabelText(/Path/)).toHaveValue('/dav');
    });
    // Method falls back to "(any)" since PROPFIND is not in the dropdown.
    expect(screen.getByText('(any)')).toBeInTheDocument();
    expect(useDashboardStore.getState().pendingBreakpointPrefill).toBeNull();
  });
});
