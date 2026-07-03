import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import CompareRunsDialog from '../components/CompareRunsDialog';
import { useDashboardStore } from '../store';

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

beforeEach(() => {
  vi.restoreAllMocks();
  useDashboardStore.setState({
    proxiedRequests: [],
    recordedRequests: [],
    activeExpectations: [],
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/** A single Anthropic-kind proxied request → one unscoped session on api.anthropic.com. */
function anthropicRequest(key: string) {
  return {
    key,
    value: {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        headers: { Host: ['api.anthropic.com'] },
        body: { model: 'claude', messages: [{ role: 'user', content: 'hi' }] },
      },
      httpResponse: {
        statusCode: 200,
        body: { model: 'claude', usage: { input_tokens: 10, output_tokens: 5 }, stop_reason: 'end_turn', content: [] },
      },
    },
  };
}

function renderDialog(overrides: Partial<Parameters<typeof CompareRunsDialog>[0]> = {}) {
  const defaults = {
    open: true,
    onClose: vi.fn(),
    ...overrides,
  };
  return {
    ...render(
      <ThemeProvider theme={buildTheme('dark')}>
        <CompareRunsDialog {...defaults} />
      </ThemeProvider>,
    ),
    ...defaults,
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('CompareRunsDialog', () => {
  it('renders dialog title', () => {
    renderDialog();
    expect(screen.getByText('Compare Runs')).toBeInTheDocument();
  });

  it('does not render when open is false', () => {
    renderDialog({ open: false });
    expect(screen.queryByText('Compare Runs')).not.toBeInTheDocument();
  });

  it('shows empty state when no traces are selected', () => {
    renderDialog();
    expect(screen.getByText('Choose two captured traces to compare.')).toBeInTheDocument();
  });

  it('shows Trace A and Trace B selectors', () => {
    renderDialog();
    expect(screen.getByLabelText('Trace A')).toBeInTheDocument();
    expect(screen.getByLabelText('Trace B')).toBeInTheDocument();
  });

  it('shows Close button', () => {
    renderDialog();
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
  });

  it('calls onClose when Close button is clicked', async () => {
    const user = userEvent.setup();
    const { onClose } = renderDialog();

    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('shows the diff-mode toggle with client and server options', () => {
    renderDialog();
    expect(screen.getByRole('button', { name: 'Structural (Client)' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Server Diff (Decoded Prompts)' })).toBeInTheDocument();
  });

  it('computes and renders the server-side decoded-prompt diff', async () => {
    useDashboardStore.setState({
      proxiedRequests: [anthropicRequest('a')],
      recordedRequests: [],
      activeExpectations: [],
    });

    const serverResult = {
      promptChanged: true,
      messageCountBefore: 1,
      messageCountAfter: 2,
      messageDiffs: [{ changeType: 'ADDED', role: 'user', beforeText: null, afterText: 'follow-up question' }],
      toolCallsAdded: ['search'],
      toolCallsRemoved: [],
      tokenDelta: null,
    };
    const calls: { url: string; init?: RequestInit }[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: true, status: 200, statusText: 'OK',
        json: async () => serverResult, text: async () => JSON.stringify(serverResult),
      };
    }));

    const user = userEvent.setup();
    renderDialog();

    // Select the same captured trace for A and B.
    await user.selectOptions(screen.getByLabelText('Trace A'), 'Unscoped (1 requests)');
    await user.selectOptions(screen.getByLabelText('Trace B'), 'Unscoped (1 requests)');

    // Switch to server-diff mode and compute.
    await user.click(screen.getByRole('button', { name: 'Server Diff (Decoded Prompts)' }));
    await user.click(screen.getByRole('button', { name: 'Compute Server Diff' }));

    await waitFor(() => {
      expect(screen.getByText(/PROMPTS CHANGED/)).toBeInTheDocument();
    });
    expect(screen.getByText('follow-up question')).toBeInTheDocument();
    expect(screen.getByText('+ search')).toBeInTheDocument();

    // The diff is requested with a host-scoped filter (server groups by host).
    expect(calls[0]?.url).toContain('/mockserver/llm/diffRuns');
    const body = JSON.parse(String(calls[0]?.init?.body));
    expect(body.before.host).toBe('api.anthropic.com');
    expect(body.after.host).toBe('api.anthropic.com');
  });

  it('surfaces a server error from the diff endpoint', async () => {
    useDashboardStore.setState({
      proxiedRequests: [anthropicRequest('a')],
      recordedRequests: [],
      activeExpectations: [],
    });
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false, status: 500, statusText: 'Internal Server Error',
      text: async () => 'Internal error diffing agent runs',
    })));

    const user = userEvent.setup();
    renderDialog();
    await user.selectOptions(screen.getByLabelText('Trace A'), 'Unscoped (1 requests)');
    await user.selectOptions(screen.getByLabelText('Trace B'), 'Unscoped (1 requests)');
    await user.click(screen.getByRole('button', { name: 'Server Diff (Decoded Prompts)' }));
    await user.click(screen.getByRole('button', { name: 'Compute Server Diff' }));

    await waitFor(() => {
      expect(screen.getByText(/Internal error diffing agent runs/)).toBeInTheDocument();
    });
  });
});
