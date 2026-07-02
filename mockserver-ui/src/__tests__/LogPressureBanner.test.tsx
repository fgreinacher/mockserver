import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor, act, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import LogPressureBanner from '../components/LogPressureBanner';
import type { ConnectionParams } from '../hooks/useConnectionParams';

const params: ConnectionParams = { host: 'localhost', port: '1080', secure: false };

function mockMetrics(response: { status?: number; body?: string }): void {
  const status = response.status ?? 200;
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      status,
      ok: status >= 200 && status < 300,
      text: async () => response.body ?? '',
    })),
  );
}

function renderBanner() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <LogPressureBanner connectionParams={params} />
    </ThemeProvider>,
  );
}

describe('LogPressureBanner', () => {
  beforeEach(() => {
    vi.useRealTimers();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('shows a warning with the dropped count when events have been evicted', async () => {
    mockMetrics({
      body: [
        '# TYPE mock_server_dropped_log_events_total counter',
        'mock_server_dropped_log_events_total 128.0',
      ].join('\n'),
    });
    renderBanner();

    const banner = await screen.findByTestId('log-pressure-banner');
    expect(banner).toHaveTextContent('128');
    expect(banner).toHaveTextContent(/maxLogEntries/);
    expect(screen.getByTestId('log-pressure-banner-learn-more')).toHaveAttribute(
      'href',
      '/mock_server/performance.html',
    );
  });

  it('renders nothing when no events have been dropped', async () => {
    mockMetrics({ body: 'mock_server_dropped_log_events_total 0.0\nrequests_received_count 3.0' });
    const { container } = renderBanner();

    // Give the poll a chance to resolve, then assert the banner never appears.
    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(screen.queryByTestId('log-pressure-banner')).not.toBeInTheDocument();
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when metrics are disabled (404)', async () => {
    mockMetrics({ status: 404 });
    renderBanner();

    await waitFor(() => expect(fetch).toHaveBeenCalled());
    expect(screen.queryByTestId('log-pressure-banner')).not.toBeInTheDocument();
  });

  it('can be dismissed', async () => {
    mockMetrics({ body: 'mock_server_dropped_log_events_total 5.0' });
    const user = userEvent.setup();
    renderBanner();

    await screen.findByTestId('log-pressure-banner');
    await user.click(screen.getByRole('button', { name: /close/i }));
    expect(screen.queryByTestId('log-pressure-banner')).not.toBeInTheDocument();
  });

  it('re-shows after a server restart resets the counter below the dismissed value', async () => {
    // First poll reports 128 drops; after a server restart the counter resets
    // and the next poll reports only 3 — fewer than the dismissed total.
    vi.useFakeTimers();
    let call = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        call += 1;
        const value = call === 1 ? 128 : 3;
        return {
          status: 200,
          ok: true,
          text: async () => `mock_server_dropped_log_events_total ${value}.0`,
        };
      }),
    );
    renderBanner();

    // First poll → 128 drops; dismiss the banner. (fireEvent is synchronous —
    // userEvent's internal delays deadlock under fake timers.)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByTestId('log-pressure-banner')).toHaveTextContent('128');
    fireEvent.click(screen.getByRole('button', { name: /close/i }));
    expect(screen.queryByTestId('log-pressure-banner')).not.toBeInTheDocument();

    // Next poll (after the 15s interval) returns 3 — a regression below the
    // dismissed total of 128, i.e. a restarted server with fresh drops.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15000);
    });

    // The banner must reappear for the fresh drops rather than staying hidden
    // until the counter climbs back past 128.
    const reappeared = screen.getByTestId('log-pressure-banner');
    expect(reappeared).toHaveTextContent('3');

    vi.useRealTimers();
  });
});
