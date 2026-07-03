import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import AuditPanel from '../components/AuditPanel';
import type { AuditEntry } from '../lib/audit';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <AuditPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

function fixture(): AuditEntry[] {
  return [
    {
      epochTimeMs: 1719950400123,
      method: 'PUT',
      path: '/mockserver/expectation',
      operation: 'expectation',
      sourceAddress: '127.0.0.1',
      principal: 'admin',
      principalSource: 'BASIC',
      outcome: 'AUTHORIZED',
      summary: 'control-plane expectation',
    },
    {
      epochTimeMs: 1719950300000,
      method: 'PUT',
      path: '/mockserver/clear',
      operation: 'clear',
      sourceAddress: '10.0.0.5',
      principal: null,
      principalSource: null,
      outcome: 'FORBIDDEN',
      summary: 'control-plane clear',
    },
  ];
}

/** Stub fetch to return the supplied array as a JSON body; captures the last URL. */
const lastUrl = { value: '' };
function stubFetch(entries: AuditEntry[]) {
  const mock = vi.fn(async (url: string) => {
    lastUrl.value = url;
    return { ok: true, json: async () => entries };
  });
  vi.stubGlobal('fetch', mock);
  return mock;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('AuditPanel', () => {
  it('fetches on mount and renders the audit entries', async () => {
    const mock = stubFetch(fixture());
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('control-plane expectation')).toBeInTheDocument();
    });
    expect(screen.getByText('control-plane clear')).toBeInTheDocument();
    expect(screen.getByText('AUTHORIZED')).toBeInTheDocument();
    expect(screen.getByText('FORBIDDEN')).toBeInTheDocument();
    expect(screen.getByText('admin')).toBeInTheDocument();
    // Fetched from the audit endpoint exactly once on mount.
    expect(mock).toHaveBeenCalledTimes(1);
    expect(lastUrl.value).toContain('/mockserver/audit');
  });

  it('refetches when Refresh is clicked', async () => {
    const mock = stubFetch(fixture());
    const user = userEvent.setup();
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('control-plane expectation')).toBeInTheDocument();
    });
    expect(mock).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: /refresh/i }));
    await waitFor(() => {
      expect(mock).toHaveBeenCalledTimes(2);
    });
  });

  it('filters entries by the search field', async () => {
    stubFetch(fixture());
    const user = userEvent.setup();
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('control-plane expectation')).toBeInTheDocument();
    });

    await user.type(screen.getByRole('textbox', { name: 'Search audit entries' }), 'clear');
    expect(screen.queryByText('control-plane expectation')).not.toBeInTheDocument();
    expect(screen.getByText('control-plane clear')).toBeInTheDocument();
  });

  it('shows the empty state when no entries are recorded', async () => {
    stubFetch([]);
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('No control-plane changes recorded yet.')).toBeInTheDocument();
    });
  });

  it('surfaces the server error envelope on failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        json: async () => ({ error: 'audit store unavailable' }),
      })),
    );
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('Could not load audit trail')).toBeInTheDocument();
    });
    expect(screen.getByText('audit store unavailable')).toBeInTheDocument();
  });

  it('shows the not-available branch on a 404 (older server)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        json: async () => { throw new Error('no body'); },
      })),
    );
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('Audit trail not available')).toBeInTheDocument();
    });
  });
});
