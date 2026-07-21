import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import TrafficInspector from '../components/TrafficInspector';
import { useDashboardStore } from '../store';

function renderTrafficInspector() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <TrafficInspector />
    </ThemeProvider>,
  );
}

/** A captured request against `host`, with an optional extra header shape. */
function request(key: string, host: string | null, path: string, method = 'GET') {
  return {
    key,
    value: {
      httpRequest: {
        method,
        path,
        ...(host === null ? {} : { headers: [{ name: 'host', values: [host] }] }),
      },
      httpResponse: { statusCode: 200 },
    },
  };
}

function seed(requests: { key: string; value: Record<string, unknown> }[]) {
  useDashboardStore.setState({
    proxiedRequests: requests,
    recordedRequests: [],
    activeExpectations: [],
    trafficSearch: '',
    selectedTrafficKey: null,
  });
}

const MULTI_HOST = [
  request('a1', 'api.example.com', '/api/one'),
  request('a2', 'api.example.com', '/api/two'),
  request('a3', 'api.example.com', '/api/three'),
  request('c1', 'cdn.example.com', '/assets/logo.png'),
  request('o1', 'other.test', '/health'),
];

/** Accessible names of the host buttons, in the order the facet renders them. */
function hostButtonNames(): string[] {
  return screen
    .queryAllByRole('button', { name: /Filter traffic by host/ })
    .map((b) => b.getAttribute('aria-label') ?? '');
}

// ---------------------------------------------------------------------------
// host: operator in the Traffic search
// ---------------------------------------------------------------------------

describe('TrafficInspector — host: search operator', () => {
  beforeEach(() => seed(MULTI_HOST));

  it('filters the traffic list by exact host', async () => {
    const user = userEvent.setup();
    renderTrafficInspector();

    expect(screen.getByText('cdn.example.com/assets/logo.png')).toBeInTheDocument();

    await user.type(screen.getByRole('textbox', { name: 'Search' }), 'host:api.example.com');

    expect(screen.getByText('api.example.com/api/one')).toBeInTheDocument();
    expect(screen.queryByText('cdn.example.com/assets/logo.png')).not.toBeInTheDocument();
    expect(screen.queryByText('other.test/health')).not.toBeInTheDocument();
  });

  it('supports a host glob', async () => {
    const user = userEvent.setup();
    renderTrafficInspector();

    await user.type(screen.getByRole('textbox', { name: 'Search' }), 'host:*.example.com');

    expect(screen.getByText('api.example.com/api/one')).toBeInTheDocument();
    expect(screen.getByText('cdn.example.com/assets/logo.png')).toBeInTheDocument();
    expect(screen.queryByText('other.test/health')).not.toBeInTheDocument();
  });

  it('ANDs the host operator with the other operators', async () => {
    const user = userEvent.setup();
    seed([
      request('g', 'api.example.com', '/api/get', 'GET'),
      request('p', 'api.example.com', '/api/post', 'POST'),
      request('x', 'other.test', '/api/post', 'POST'),
    ]);
    renderTrafficInspector();

    await user.type(
      screen.getByRole('textbox', { name: 'Search' }),
      'host:api.example.com method:POST',
    );

    expect(screen.getByText('api.example.com/api/post')).toBeInTheDocument();
    expect(screen.queryByText('api.example.com/api/get')).not.toBeInTheDocument();
    expect(screen.queryByText('other.test/api/post')).not.toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Host facet
// ---------------------------------------------------------------------------

describe('TrafficInspector — host facet', () => {
  it('is hidden when every request targets the same host', () => {
    seed([
      request('a', 'localhost:1080', '/api/one'),
      request('b', 'localhost:1080', '/api/two'),
    ]);
    renderTrafficInspector();

    expect(screen.queryByText(/^Hosts \(/)).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /Filter traffic by host/ }),
    ).not.toBeInTheDocument();
  });

  it('is hidden when there is no traffic at all', () => {
    seed([]);
    renderTrafficInspector();

    expect(screen.queryByText(/^Hosts \(/)).not.toBeInTheDocument();
  });

  it('appears once traffic spans more than one host, with per-host counts busiest first', () => {
    seed(MULTI_HOST);
    renderTrafficInspector();

    expect(screen.getByText('Hosts (3)')).toBeInTheDocument();
    expect(hostButtonNames()).toEqual([
      'Filter traffic by host api.example.com (3 requests)',
      'Filter traffic by host cdn.example.com (1 request)',
      'Filter traffic by host other.test (1 request)',
    ]);
  });

  it('ignores requests that carry no Host header', () => {
    seed([
      request('a', 'api.example.com', '/api/one'),
      request('b', 'cdn.example.com', '/assets/x'),
      request('n', null, '/no-host'),
    ]);
    renderTrafficInspector();

    expect(screen.getByText('Hosts (2)')).toBeInTheDocument();
    expect(hostButtonNames()).toEqual([
      'Filter traffic by host api.example.com (1 request)',
      'Filter traffic by host cdn.example.com (1 request)',
    ]);
  });

  it('omits hosts the search DSL cannot round-trip, rather than offering a broken pin', () => {
    // A client can put anything in a Host header. A space would tokenise into
    // `host:foo` plus stray free text — selecting nothing, including the row
    // the facet counted — and `*` would pin a glob matching every row while the
    // facet claimed one. Neither is offered.
    seed([
      request('a', 'api.example.com', '/api/one'),
      request('b', 'cdn.example.com', '/assets/x'),
      request('s', 'foo bar', '/spaced'),
      request('g', '*', '/star'),
    ]);
    renderTrafficInspector();

    expect(hostButtonNames()).toEqual([
      'Filter traffic by host api.example.com (1 request)',
      'Filter traffic by host cdn.example.com (1 request)',
    ]);
  });

  it('still offers a host carrying a port, which the DSL does round-trip', () => {
    seed([
      request('a', 'localhost:1080', '/api/one'),
      request('b', 'localhost:1090', '/api/two'),
    ]);
    renderTrafficInspector();

    expect(hostButtonNames()).toEqual([
      'Filter traffic by host localhost:1080 (1 request)',
      'Filter traffic by host localhost:1090 (1 request)',
    ]);
  });

  it('groups by the same host the row shows, whatever header shape carried it', async () => {
    const user = userEvent.setup();
    // Object-shaped headers with non-canonical casing. The facet must bucket by
    // exactly the value the row and the `host:` operator resolve, not a second
    // extraction — otherwise a pin would select a different set than it counted.
    seed([
      {
        key: 'obj',
        value: {
          httpRequest: { method: 'GET', path: '/one', headers: { Host: ['API.Example.com'] } },
          httpResponse: { statusCode: 200 },
        },
      },
      {
        key: 'arr',
        value: {
          httpRequest: { method: 'GET', path: '/two', headers: [{ name: 'HOST', values: ['API.Example.com'] }] },
          httpResponse: { statusCode: 200 },
        },
      },
      request('other', 'other.test', '/three'),
    ]);
    renderTrafficInspector();

    const both = screen.getByRole('button', {
      name: 'Filter traffic by host API.Example.com (2 requests)',
    });
    await user.click(both);

    expect(screen.getByText('API.Example.com/one')).toBeInTheDocument();
    expect(screen.getByText('API.Example.com/two')).toBeInTheDocument();
    expect(screen.queryByText('other.test/three')).not.toBeInTheDocument();
  });

  it('treats a bare host: with no value as nothing pinned', () => {
    useDashboardStore.setState({
      proxiedRequests: MULTI_HOST,
      recordedRequests: [],
      activeExpectations: [],
      trafficSearch: 'host:',
      selectedTrafficKey: null,
    });
    renderTrafficInspector();

    for (const button of screen.getAllByRole('button', { name: /Filter traffic by host/ })) {
      expect(button).toHaveAttribute('aria-pressed', 'false');
    }
  });

  it('pins the clicked host into the search and narrows the list to its count', async () => {
    const user = userEvent.setup();
    seed(MULTI_HOST);
    renderTrafficInspector();

    await user.click(
      screen.getByRole('button', { name: 'Filter traffic by host api.example.com (3 requests)' }),
    );

    expect(useDashboardStore.getState().trafficSearch).toBe('host:api.example.com');
    expect(screen.getByRole('textbox', { name: 'Search' })).toHaveValue('host:api.example.com');

    // The facet's count and the filter agree: 3 counted, 3 rows shown.
    expect(screen.getByText('api.example.com/api/one')).toBeInTheDocument();
    expect(screen.getByText('api.example.com/api/two')).toBeInTheDocument();
    expect(screen.getByText('api.example.com/api/three')).toBeInTheDocument();
    expect(screen.queryByText('cdn.example.com/assets/logo.png')).not.toBeInTheDocument();
    expect(screen.queryByText('other.test/health')).not.toBeInTheDocument();
  });

  it('keeps every host listed while one is pinned, so another can be picked', async () => {
    const user = userEvent.setup();
    seed(MULTI_HOST);
    renderTrafficInspector();

    await user.click(
      screen.getByRole('button', { name: 'Filter traffic by host api.example.com (3 requests)' }),
    );

    expect(screen.getByText('Hosts (3)')).toBeInTheDocument();
    await user.click(
      screen.getByRole('button', { name: 'Filter traffic by host other.test (1 request)' }),
    );

    expect(useDashboardStore.getState().trafficSearch).toBe('host:other.test');
    expect(screen.getByText('other.test/health')).toBeInTheDocument();
    expect(screen.queryByText('api.example.com/api/one')).not.toBeInTheDocument();
  });

  it('unpins when the pinned host is clicked again', async () => {
    const user = userEvent.setup();
    seed(MULTI_HOST);
    renderTrafficInspector();

    const pin = () =>
      screen.getByRole('button', { name: 'Filter traffic by host api.example.com (3 requests)' });

    await user.click(pin());
    expect(pin()).toHaveAttribute('aria-pressed', 'true');

    await user.click(pin());
    expect(useDashboardStore.getState().trafficSearch).toBe('');
    expect(pin()).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('cdn.example.com/assets/logo.png')).toBeInTheDocument();
  });

  it('unpinning keeps the rest of the search intact', async () => {
    const user = userEvent.setup();
    seed(MULTI_HOST);
    renderTrafficInspector();

    await user.type(screen.getByRole('textbox', { name: 'Search' }), 'method:GET');
    const pin = () =>
      screen.getByRole('button', { name: 'Filter traffic by host api.example.com (3 requests)' });

    await user.click(pin());
    expect(useDashboardStore.getState().trafficSearch).toBe('method:GET host:api.example.com');

    await user.click(pin());
    expect(useDashboardStore.getState().trafficSearch).toBe('method:GET');
  });

  it('narrows an existing search rather than replacing it', async () => {
    const user = userEvent.setup();
    seed([
      request('g', 'api.example.com', '/api/get', 'GET'),
      request('p', 'api.example.com', '/api/post', 'POST'),
      request('x', 'other.test', '/api/post', 'POST'),
    ]);
    renderTrafficInspector();

    await user.type(screen.getByRole('textbox', { name: 'Search' }), 'method:POST');
    await user.click(
      screen.getByRole('button', { name: 'Filter traffic by host api.example.com (2 requests)' }),
    );

    expect(useDashboardStore.getState().trafficSearch).toBe('method:POST host:api.example.com');
    expect(screen.getByText('api.example.com/api/post')).toBeInTheDocument();
    expect(screen.queryByText('api.example.com/api/get')).not.toBeInTheDocument();
    expect(screen.queryByText('other.test/api/post')).not.toBeInTheDocument();
  });

  it('persists the pin so it survives leaving and re-entering the view', async () => {
    const user = userEvent.setup();
    seed(MULTI_HOST);
    const { unmount } = renderTrafficInspector();

    await user.click(
      screen.getByRole('button', { name: 'Filter traffic by host api.example.com (3 requests)' }),
    );
    unmount();

    renderTrafficInspector();
    expect(screen.getByRole('textbox', { name: 'Search' })).toHaveValue('host:api.example.com');
    expect(screen.queryByText('other.test/health')).not.toBeInTheDocument();
  });

  it('collapses and re-expands the host list', async () => {
    const user = userEvent.setup();
    seed(MULTI_HOST);
    renderTrafficInspector();

    expect(screen.getAllByRole('button', { name: /Filter traffic by host/ })).toHaveLength(3);

    // The list is unmounted on collapse (not merely hidden), so it stops costing
    // DOM nodes; MUI runs that through a transition, hence the waitFor.
    await user.click(screen.getByRole('button', { name: 'Collapse hosts' }));
    await waitFor(() =>
      expect(screen.queryAllByRole('button', { name: /Filter traffic by host/ })).toHaveLength(0),
    );

    await user.click(screen.getByRole('button', { name: 'Expand hosts' }));
    expect(
      screen.getByRole('button', { name: 'Filter traffic by host other.test (1 request)' }),
    ).toBeInTheDocument();
  });
});
