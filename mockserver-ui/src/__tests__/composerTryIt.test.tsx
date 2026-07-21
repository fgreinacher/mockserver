/**
 * Composer "Try It" wiring: the inline live-response widget sits beside "Test
 * Matcher" and is seeded from the same draft expectation JSON that would be
 * registered, so what the user fires is what the matcher describes.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: (p: { host: string; port: string; secure: boolean }) =>
    `${p.secure ? 'https' : 'http'}://${p.host}:${p.port}`,
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderComposer() {
  // Advanced mode shows the full matcher form — and with it the Try It button.
  try { globalThis.sessionStorage?.setItem('mockserver-composer-mode', 'advanced'); } catch { /* noop */ }
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

beforeEach(() => {
  useDashboardStore.setState({ activeExpectations: [] });
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({ ok: true, status: 200, statusText: 'OK', json: async () => ({ ports: [1080] }) })),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('Composer — Try It', () => {
  it('opens an inline widget seeded from the draft matcher, and closes again', async () => {
    const user = userEvent.setup();
    renderComposer();

    const pathField = screen.getByLabelText('Path');
    await user.clear(pathField);
    await user.type(pathField, '/seeded/live');

    expect(screen.queryByTestId('live-response-widget')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Try It' }));
    await act(async () => {});

    // Inline — not a modal dialog, and rendered alongside the matcher form.
    expect(screen.getByTestId('live-response-widget')).toBeInTheDocument();
    expect(screen.queryByText('Matcher Test Playground')).toBeNull();
    expect((screen.getByLabelText('Live request path') as HTMLInputElement).value).toBe('/seeded/live');
    expect((screen.getByLabelText('Live request method') as HTMLInputElement).value).toBe('GET');

    await user.click(screen.getByRole('button', { name: 'Hide Try It' }));
    expect(screen.queryByTestId('live-response-widget')).toBeNull();
  });

  it('leaves the path blank when the draft matcher path is a regex', async () => {
    const user = userEvent.setup();
    renderComposer();

    const pathField = screen.getByLabelText('Path');
    await user.clear(pathField);
    await user.type(pathField, '/api/.*');

    await user.click(screen.getByRole('button', { name: 'Try It' }));
    await act(async () => {});

    expect((screen.getByLabelText('Live request path') as HTMLInputElement).value).toBe('');
    expect(screen.getByTestId('try-it-derivation-notes').textContent).toContain('/api/.*');
    expect(screen.getByRole('button', { name: /Send request/i })).toBeDisabled();
  });
});
