import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, within, cleanup, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import OptimiseView, { safeHttpUrl } from '../components/OptimiseView';
import { useDashboardStore } from '../store';
import sample from '../__fixtures__/optimisationReport.sample.json';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderView() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <OptimiseView connectionParams={params} />
    </ThemeProvider>,
  );
}

/**
 * Stub fetch. `markdown` requests resolve to a text body; everything else
 * resolves to the JSON fixture. Returns the mock so tests can inspect the URLs
 * the component actually requested.
 */
function stubFetch(jsonReport: unknown = sample, markdown = '# Optimisation brief\n') {
  const fn = vi.fn(async (url: string) => {
    const isMarkdown = url.includes('format=markdown');
    return {
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => jsonReport,
      text: async () => (isMarkdown ? markdown : JSON.stringify(jsonReport)),
    } as unknown as Response;
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

/** Install a clipboard stub after userEvent.setup (which installs its own). */
function stubClipboard(): { writeText: ReturnType<typeof vi.fn>; restore: () => void } {
  const writeText = vi.fn(async () => undefined);
  const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true, writable: true });
  return {
    writeText,
    restore: () => {
      if (original) Object.defineProperty(navigator, 'clipboard', original);
      else delete (navigator as { clipboard?: unknown }).clipboard;
    },
  };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('OptimiseView', () => {
  it('fetches the JSON report on mount with format=json', async () => {
    const fetchMock = stubFetch();
    renderView();
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled();
    });
    const firstUrl = String(fetchMock.mock.calls[0]?.[0]);
    expect(firstUrl).toContain('/mockserver/llm/optimisationReport');
    expect(firstUrl).toContain('format=json');
  });

  it('renders hero numbers from the report totals', async () => {
    stubFetch();
    renderView();
    const hero = await screen.findByTestId('optimise-hero');
    // total cost ($0.1573), input/output tokens, call count, avg latency
    expect(within(hero).getByText('$0.1573')).toBeInTheDocument();
    expect(within(hero).getByText('48,210')).toBeInTheDocument();
    expect(within(hero).getByText('3,120')).toBeInTheDocument();
    expect(within(hero).getByText('6')).toBeInTheDocument();
    // avg latency = 14230 / 6 ≈ 2371.7 ms → "2.4 s"
    expect(within(hero).getByText('2.4 s')).toBeInTheDocument();
  });

  it('orders signals HIGH severity first', async () => {
    stubFetch();
    renderView();
    const panel = await screen.findByTestId('optimise-signals');
    const chips = within(panel).getAllByText(/^(HIGH|MEDIUM|LOW)$/);
    expect(chips.map((c) => c.textContent)).toEqual(['HIGH', 'MEDIUM', 'LOW']);
  });

  it('shows signal saving and the structured fix when present', async () => {
    stubFetch();
    renderView();
    const panel = await screen.findByTestId('optimise-signals');
    expect(within(panel).getByText(/Identical 5,400-token system prompt/)).toBeInTheDocument();
    expect(within(panel).getByText(/save ~\$0\.0675/)).toBeInTheDocument();
    // signal 0 carries a `fix` — its summary + action render in place of the
    // back-compat recommendation, plus a "Learn more" docs link.
    expect(within(panel).getByText('Enable prompt caching')).toBeInTheDocument();
    expect(within(panel).getByText(/Add cache_control to the static system block/)).toBeInTheDocument();
    expect(within(panel).getByRole('link', { name: 'Learn more' })).toBeInTheDocument();
  });

  it('falls back to the recommendation when a signal has no fix', async () => {
    stubFetch();
    renderView();
    const panel = await screen.findByTestId('optimise-signals');
    // the OVERSIZED_TOOL_RESULT signal has no `fix` → recommendation shown.
    expect(within(panel).getByText(/Trim or summarise the search_docs tool output/)).toBeInTheDocument();
  });

  it('renders the verdict banner with a grade and headline', async () => {
    stubFetch();
    renderView();
    const banner = await screen.findByTestId('optimise-verdict');
    expect(within(banner).getByText('C')).toBeInTheDocument();
    expect(within(banner).getByText(/recoverable \(18% of spend\)/)).toBeInTheDocument();
    expect(within(banner).getByText(/an estimated 18% of spend/)).toBeInTheDocument();
  });

  it('renders the Cache-hit and One-shot hero cards', async () => {
    stubFetch();
    renderView();
    const hero = await screen.findByTestId('optimise-hero');
    expect(within(hero).getByText('Cache hit')).toBeInTheDocument();
    expect(within(hero).getByText('62%')).toBeInTheDocument();
    expect(within(hero).getByText('One-shot')).toBeInTheDocument();
    expect(within(hero).getByText('83%')).toBeInTheDocument();
  });

  it('Copy verdict writes a client-side verdict to the clipboard without a markdown fetch', async () => {
    const fetchMock = stubFetch();
    const user = userEvent.setup();
    const clipboard = stubClipboard();
    try {
      renderView();
      const button = await screen.findByRole('button', { name: /Copy verdict/i });
      const callsBefore = fetchMock.mock.calls.length;
      await user.click(button);
      await waitFor(() => {
        expect(clipboard.writeText).toHaveBeenCalled();
      });
      const text = String(clipboard.writeText.mock.calls[0]?.[0] ?? '');
      expect(text).toContain('Grade C');
      expect(text).toContain('[HIGH]');
      expect(text).toContain('Enable prompt caching');
      // no extra fetch fired for the verdict copy — it is built client-side.
      expect(fetchMock.mock.calls.length).toBe(callsBefore);
    } finally {
      clipboard.restore();
    }
  });

  it('shows a calm grade-A state when there are no opportunities', async () => {
    const calmWire = {
      ...sample,
      verdict: {
        grade: 'A',
        rationale: 'No optimisation opportunities detected.',
        totalEstimatedSavingUsd: 0,
        totalWastedInputTokens: 0,
        savingFractionOfSpend: 0,
        costIsEstimated: false,
        highCount: 0,
        mediumCount: 0,
        lowCount: 0,
      },
    };
    stubFetch(calmWire);
    renderView();
    const banner = await screen.findByTestId('optimise-verdict');
    expect(within(banner).getByText(/no optimisation opportunities detected/i)).toBeInTheDocument();
  });

  it('renders one table row per call', async () => {
    stubFetch();
    renderView();
    const table = await screen.findByTestId('optimise-call-table');
    // header row + 6 data rows
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(1 + 6);
    // finish reasons from the fixture appear in the table
    expect(within(table).getAllByText('tool_calls').length).toBeGreaterThan(0);
    expect(within(table).getByText('length')).toBeInTheDocument();
  });

  it('Copy optimisation brief fetches format=markdown and writes to clipboard', async () => {
    const fetchMock = stubFetch();
    const user = userEvent.setup();
    const clipboard = stubClipboard();
    try {
      renderView();
      const copyButton = await screen.findByRole('button', { name: /Copy optimisation brief/i });
      await user.click(copyButton);
      await waitFor(() => {
        expect(clipboard.writeText).toHaveBeenCalledWith('# Optimisation brief\n');
      });
      const markdownCall = fetchMock.mock.calls.find((c) => String(c[0]).includes('format=markdown'));
      expect(markdownCall).toBeDefined();
    } finally {
      clipboard.restore();
    }
  });

  it('Download bundle fetches format=json and triggers a download', async () => {
    const fetchMock = stubFetch();
    const user = userEvent.setup();
    const originalCreateObjectURL = URL.createObjectURL;
    const originalRevokeObjectURL = URL.revokeObjectURL;
    URL.createObjectURL = vi.fn(() => 'blob:mock');
    URL.revokeObjectURL = vi.fn();
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    try {
      renderView();
      const downloadButton = await screen.findByRole('button', { name: /Download bundle/i });
      await user.click(downloadButton);
      await waitFor(() => {
        expect(clickSpy).toHaveBeenCalled();
      });
      // download uses the json endpoint; markdown must NOT be requested for a download
      const jsonCalls = fetchMock.mock.calls.filter((c) => String(c[0]).includes('format=json'));
      expect(jsonCalls.length).toBeGreaterThanOrEqual(2); // mount + download
      expect(fetchMock.mock.calls.some((c) => String(c[0]).includes('format=markdown'))).toBe(false);
      expect(URL.createObjectURL).toHaveBeenCalled();
    } finally {
      URL.createObjectURL = originalCreateObjectURL;
      URL.revokeObjectURL = originalRevokeObjectURL;
    }
  });

  it('shows the empty state for the real empty-report wire shape (omitted arrays)', async () => {
    // The server's NON_EMPTY JSON mapper OMITS empty collections entirely, so an
    // empty report arrives with no `calls`, `signals`, `session.providers`,
    // `session.models` or `redaction.*` keys at all. The view must not crash —
    // this is the first thing a user sees before any traffic is captured.
    const emptyWire = {
      schemaVersion: 1,
      generatedBy: 'mockserver',
      session: { key: 'all', groupingBasis: 'PROXY_HOST' },
      totals: {
        callCount: 0, inputTokens: 0, outputTokens: 0, cachedInputTokens: 0,
        reasoningTokens: 0, estimatedCostUsd: 0, costIsEstimated: false,
        totalLatencyMs: 0, toolCallCount: 0,
      },
      redaction: { applied: true },
    };
    stubFetch(emptyWire);
    renderView();
    expect(await screen.findByText('No LLM traffic captured')).toBeInTheDocument();
  });

  it('degrades gracefully on a server error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        text: async () => '',
        json: async () => ({}),
      }) as unknown as Response),
    );
    renderView();
    expect(await screen.findByTestId('optimise-error')).toBeInTheDocument();
    // 404 is humanised to the "feature not available" message
    expect(screen.getByText(/isn.t available on the connected MockServer/i)).toBeInTheDocument();
  });
});

describe('safeHttpUrl (docsUrl scheme guard)', () => {
  it('accepts absolute http / https URLs', () => {
    expect(safeHttpUrl('https://www.mock-server.com/docs')).toBe('https://www.mock-server.com/docs');
    expect(safeHttpUrl('http://example.com/x')).toBe('http://example.com/x');
  });

  it('rejects javascript:, data: and other non-http schemes', () => {
    expect(safeHttpUrl('javascript:alert(1)')).toBeNull();
    expect(safeHttpUrl('data:text/html,<script>alert(1)</script>')).toBeNull();
    expect(safeHttpUrl('file:///etc/passwd')).toBeNull();
  });

  it('rejects relative / malformed / non-string values', () => {
    expect(safeHttpUrl('/relative/path')).toBeNull();
    expect(safeHttpUrl('not a url')).toBeNull();
    expect(safeHttpUrl('')).toBeNull();
    expect(safeHttpUrl(undefined)).toBeNull();
    expect(safeHttpUrl(42)).toBeNull();
  });
});

describe('OptimiseView — stale-traffic banner', () => {
  it('shows a refresh banner when new traffic is captured after the report loads', async () => {
    stubFetch();
    act(() => {
      useDashboardStore.setState({ proxiedRequests: [], recordedRequests: [] });
    });
    renderView();

    // Wait for the report to finish loading.
    await screen.findByTestId('optimise-hero');
    expect(screen.queryByTestId('optimise-stale-banner')).not.toBeInTheDocument();

    // New traffic arrives via a WebSocket push (store update).
    act(() => {
      useDashboardStore.setState({
        proxiedRequests: [
          { key: 'new-1', value: { httpRequest: { method: 'POST', path: '/v1/messages' }, httpResponse: { statusCode: 200 } } },
        ],
      });
    });

    const banner = await screen.findByTestId('optimise-stale-banner');
    expect(within(banner).getByRole('button', { name: 'Refresh' })).toBeInTheDocument();
  });
});
