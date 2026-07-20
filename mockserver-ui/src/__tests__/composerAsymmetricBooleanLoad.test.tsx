/**
 * Edit round-trip for the asymmetric-default booleans.
 *
 * The generator now always writes `closeConnection` / `fallbackOnTimeout`, so the
 * value the Composer LOADS is the value that gets written back. That makes the
 * load path safety-critical: if an ABSENT field loads as OFF, then merely opening
 * an existing expectation and saving it silently flips live behaviour, because on
 * the server absent means:
 *
 *   - SSE / WebSocket `closeConnection`     → CLOSE      (`== null || value`)
 *   - `fallbackOnTimeout`                   → FALL BACK  (`== null || value`)
 *   - gRPC streaming `closeConnection`      → DON'T CLOSE (`!= null && value`)
 *
 * These tests load expectations that OMIT the field — the only shape that can
 * expose the inversion. An expectation that already carries an explicit value
 * would round-trip correctly either way and would prove nothing.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderComposer() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

function loadForEdit(expectation: Record<string, unknown>) {
  useDashboardStore.setState({
    activeExpectations: [],
    pendingEditExpectation: expectation as never,
    view: 'composer',
  });
  renderComposer();
}

/**
 * The switch for `label`. MUI's Switch renders `role="switch"` (NOT "checkbox" —
 * querying for checkbox silently finds nothing and every assertion times out).
 */
async function switchFor(label: RegExp): Promise<HTMLInputElement> {
  const el = await screen.findByRole('switch', { name: label });
  return el as HTMLInputElement;
}

describe('an absent asymmetric-default boolean loads as the server default', () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [], pendingEditExpectation: null, view: 'composer' });
    try { globalThis.sessionStorage?.clear(); } catch { /* noop */ }
  });

  it('SSE with NO closeConnection loads with the switch ON (server closes)', async () => {
    loadForEdit({
      id: 'sse-absent',
      httpRequest: { method: 'GET', path: '/sse' },
      httpSseResponse: { statusCode: 200, events: [{ event: 'm', data: 'd' }] },
    });
    await waitFor(() => expect(screen.getByText('Expectation kind')).toBeInTheDocument());
    expect((await switchFor(/close connection/i)).checked).toBe(true);
  });

  it('WebSocket with NO closeConnection loads with the switch ON (server closes)', async () => {
    loadForEdit({
      id: 'ws-absent',
      httpRequest: { method: 'GET', path: '/ws' },
      httpWebSocketResponse: { messages: [{ text: 'hi' }] },
    });
    await waitFor(() => expect(screen.getByText('Expectation kind')).toBeInTheDocument());
    expect((await switchFor(/close connection/i)).checked).toBe(true);
  });

  it('gRPC streaming with NO closeConnection loads with the switch OFF (server keeps it open)', async () => {
    loadForEdit({
      id: 'grpc-absent',
      httpRequest: { method: 'POST', path: '/grpc' },
      grpcStreamResponse: { statusName: 'OK', messages: [{ json: '{"a":1}' }] },
    });
    await waitFor(() => expect(screen.getByText('Expectation kind')).toBeInTheDocument());
    expect((await switchFor(/close connection/i)).checked).toBe(false);
  });

  it('forward-with-fallback with NO fallbackOnTimeout loads with the switch ON (server falls back)', async () => {
    loadForEdit({
      id: 'fb-absent',
      httpRequest: { method: 'GET', path: '/fb' },
      httpForwardWithFallback: {
        httpForward: { host: 'up.example.com', port: 80, scheme: 'HTTP' },
        fallbackResponse: { statusCode: 503 },
      },
    });
    await waitFor(() => expect(screen.getByText('Expectation kind')).toBeInTheDocument());
    expect((await switchFor(/fallback on timeout/i)).checked).toBe(true);
  });

  it('an explicit false is preserved as OFF (not overridden by the default)', async () => {
    loadForEdit({
      id: 'sse-false',
      httpRequest: { method: 'GET', path: '/sse' },
      httpSseResponse: { statusCode: 200, events: [{ event: 'm', data: 'd' }], closeConnection: false },
    });
    await waitFor(() => expect(screen.getByText('Expectation kind')).toBeInTheDocument());
    expect((await switchFor(/close connection/i)).checked).toBe(false);
  });
});
