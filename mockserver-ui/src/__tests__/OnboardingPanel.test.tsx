import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import OnboardingPanel from '../components/OnboardingPanel';
import { useDashboardStore } from '../store';
import type { WebSocketMessage } from '../types';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <OnboardingPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

beforeEach(() => {
  useDashboardStore.setState({
    logMessages: [],
    activeExpectations: [],
    recordedRequests: [],
    proxiedRequests: [],
    view: 'get-started',
    requestFilter: {},
    filterEnabled: false,
    filterExpanded: false,
    connectionStatus: 'disconnected',
    autoScroll: true,
    logSearch: '',
    expectationSearch: '',
    receivedSearch: '',
    proxiedSearch: '',
    trafficSearch: '',
    error: null,
    notification: null,
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('OnboardingPanel', () => {
  it('renders the welcome heading', () => {
    renderPanel();
    expect(screen.getByText('Welcome to MockServer')).toBeInTheDocument();
  });

  it('renders the six key features (tile layout + narrow bulleted list)', () => {
    renderPanel();
    // Each feature appears in both the wide-screen tiles and the narrow-screen
    // bulleted list (one is CSS-hidden per viewport), so each is present >= once.
    for (const title of ['Breakpoints', 'Debugging Proxy', 'Performance Testing', 'LLM Optimise', 'Mocking', 'Chaos Testing']) {
      expect(screen.getAllByText(title).length).toBeGreaterThan(0);
    }
  });

  it('no longer claims that no expectations or traffic are recorded', () => {
    renderPanel();
    expect(screen.queryByText(/No expectations or traffic recorded/i)).not.toBeInTheDocument();
  });

  it('has no per-tile Docs links', () => {
    renderPanel();
    expect(screen.queryByRole('link', { name: 'Docs' })).not.toBeInTheDocument();
  });

  it('renders the Import OpenAPI button', () => {
    renderPanel();
    expect(screen.getByRole('button', { name: /Import OpenAPI/i })).toBeInTheDocument();
  });

  it('navigates to a feature page when a tile action is clicked', async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.click(screen.getByRole('button', { name: /Open Breakpoints/i }));
    expect(useDashboardStore.getState().view).toBe('breakpoints');

    await user.click(screen.getByRole('button', { name: /Open Chaos/i }));
    expect(useDashboardStore.getState().view).toBe('chaos');

    await user.click(screen.getByRole('button', { name: /Open Performance/i }));
    expect(useDashboardStore.getState().view).toBe('performance');

    await user.click(screen.getByRole('button', { name: /Open LLM Optimise/i }));
    expect(useDashboardStore.getState().view).toBe('optimise');
  });

  it('lists the other tabs as in-app navigation links', async () => {
    const user = userEvent.setup();
    renderPanel();

    expect(screen.getByText(/More in the tabs above/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Drift' }));
    expect(useDashboardStore.getState().view).toBe('drift');

    await user.click(screen.getByRole('button', { name: 'Metrics' }));
    expect(useDashboardStore.getState().view).toBe('metrics');
  });

  it('links to the UI docs inline in the more-tabs line', () => {
    renderPanel();
    const uiDocs = screen.getByRole('link', { name: 'UI docs' });
    expect(uiDocs).toHaveAttribute('href', 'https://www.mock-server.com/mock_server/mockserver_ui.html');
    expect(uiDocs).toHaveAttribute('target', '_blank');
  });

  it('opens the OpenAPI import dialog when the button is clicked', async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.click(screen.getByRole('button', { name: /Import OpenAPI/i }));

    // The dialog should appear (OpenApiImportDialog renders with a dialog title)
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('navigates to the composer from the Mocking tile Create Mock button', async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.click(screen.getByRole('button', { name: /^Create Mock$/i }));
    expect(useDashboardStore.getState().view).toBe('composer');
  });

  it('no longer renders the Try It Now quick-start section', () => {
    renderPanel();
    // The Mocking tile's Create Mock CTA now carries the primary first-run action,
    // so the duplicated "Try It Now" steps (and their curl / proxy snippets) are gone.
    expect(screen.queryByText('Try It Now')).not.toBeInTheDocument();
    expect(screen.queryByText(/curl http:\/\//i)).not.toBeInTheDocument();
    expect(screen.queryByText(/export HTTPS_PROXY=/i)).not.toBeInTheDocument();
  });

  it('does not show the returning-user banner when the server has no state', () => {
    renderPanel();
    expect(screen.queryByRole('button', { name: /Open Dashboard/i })).not.toBeInTheDocument();
  });

  it('shows the returning-user banner only when there are active mocks or recorded requests', async () => {
    useDashboardStore.setState({
      activeExpectations: [{ key: 'exp1', value: { httpRequest: { path: '/a' } } }],
      recordedRequests: [
        { key: 'rec1', value: { path: '/b' } },
        { key: 'rec2', value: { path: '/c' } },
      ],
    });
    const user = userEvent.setup();
    renderPanel();

    expect(screen.getByText(/This server has 1 active mock and 2 recorded requests/i)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Open Dashboard/i }));
    expect(useDashboardStore.getState().view).toBe('dashboard');
  });
});

describe('OnboardingPanel default-selection logic', () => {
  it('is the default view when the store initialises', () => {
    expect(useDashboardStore.getState().view).toBe('get-started');
  });

  it('stays on get-started when applyMessage has no data', () => {
    const message: WebSocketMessage = {
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('get-started');
  });

  it('stays on get-started when expectations arrive (no auto-switch)', () => {
    const message: WebSocketMessage = {
      logMessages: [],
      activeExpectations: [{ key: 'exp1', value: { httpRequest: { path: '/test' } } }],
      recordedRequests: [],
      proxiedRequests: [],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('get-started');
  });

  it('stays on get-started when recorded requests arrive (no auto-switch)', () => {
    const message: WebSocketMessage = {
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [{ key: 'rec1', value: { path: '/received' } }],
      proxiedRequests: [],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('get-started');
  });

  it('stays on get-started when proxied requests arrive (no auto-switch)', () => {
    const message: WebSocketMessage = {
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [{ key: 'prx1', value: { path: '/proxied' } }],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('get-started');
  });

  it('leaves a manually-chosen view untouched when data arrives', () => {
    useDashboardStore.getState().setView('traffic');
    const message: WebSocketMessage = {
      logMessages: [],
      activeExpectations: [{ key: 'exp1', value: {} }],
      recordedRequests: [],
      proxiedRequests: [],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('traffic');
  });

  it('stays on get-started when log messages arrive without other data', () => {
    const message: WebSocketMessage = {
      logMessages: [{ key: 'log1', value: { messageParts: [] } }],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('get-started');
  });
});
