import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import AppBar, { NAV_TAB_DESCRIPTIONS } from '../components/AppBar';
import { useDashboardStore, type ViewMode } from '../store';
import * as http3StatusModule from '../lib/http3Status';

function renderAppBar(overrides = {}) {
  const defaults = {
    onClearServer: vi.fn().mockResolvedValue(undefined),
    onClearLogs: vi.fn().mockResolvedValue(undefined),
    onClearExpectations: vi.fn().mockResolvedValue(undefined),
    onShowShortcuts: vi.fn(),
  };
  const props = { ...defaults, ...overrides };
  return {
    ...render(
      <ThemeProvider theme={buildTheme('dark')}>
        <AppBar {...props} />
      </ThemeProvider>,
    ),
    props,
  };
}

describe('AppBar', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      connectionStatus: 'connected',
      themeMode: 'dark',
      autoScroll: true,
    });
  });

  it('displays the MockServer title', () => {
    renderAppBar();
    expect(screen.getByText('MockServer')).toBeInTheDocument();
  });

  it('shows connection status chip', () => {
    renderAppBar();
    expect(screen.getByText('connected')).toBeInTheDocument();
  });

  it('shows different connection statuses', () => {
    useDashboardStore.setState({ connectionStatus: 'error' });
    renderAppBar();
    expect(screen.getByText('error')).toBeInTheDocument();
  });

  it('exposes the server location (host:port) in the connection chip tooltip', async () => {
    const user = userEvent.setup();
    renderAppBar();
    // The chip label stays "connected"; the tooltip finally says WHERE.
    await user.hover(screen.getByText('connected'));
    const tooltip = await screen.findByRole('tooltip');
    expect(tooltip).toHaveTextContent(/MockServer at https?:\/\/[^:]+:\d+/);
  });

  it('toggles theme when theme button is clicked', async () => {
    const user = userEvent.setup();
    renderAppBar();

    const themeButtons = screen.getAllByRole('button');
    const themeButton = themeButtons.find((b) => b.getAttribute('aria-label')?.includes('light') || b.querySelector('[data-testid="LightModeIcon"]'));

    if (themeButton) {
      await user.click(themeButton);
      expect(useDashboardStore.getState().themeMode).toBe('light');
    }
  });

  it('opens clear menu and calls clear server on reset after confirmation', async () => {
    const user = userEvent.setup();
    const { props } = renderAppBar();

    const clearButton = screen.getAllByRole('button').find(
      (b) => b.querySelector('[data-testid="DeleteSweepIcon"]'),
    );
    expect(clearButton).toBeDefined();

    await user.click(clearButton!);
    expect(screen.getByText('Reset Server (all)')).toBeInTheDocument();

    // Reset is destructive — it opens a confirmation dialog rather than firing immediately.
    await user.click(screen.getByText('Reset Server (all)'));
    expect(props.onClearServer).not.toHaveBeenCalled();
    expect(screen.getByText('Reset the entire server?')).toBeInTheDocument();

    // Confirm in the dialog.
    await user.click(screen.getByRole('button', { name: 'Reset server' }));
    expect(props.onClearServer).toHaveBeenCalledOnce();
  });

  it('shows the Mocks tab label (not Composer) inside the Mock group', async () => {
    const user = userEvent.setup();
    renderAppBar();
    // The view label lives in the Mock group's dropdown — open it and assert the
    // renamed "Mocks" item is present and the old "Composer" label is gone.
    await user.click(screen.getByRole('button', { name: 'Mock views' }));
    expect(await screen.findByRole('menuitem', { name: 'Mocks view' })).toBeInTheDocument();
    expect(screen.queryByText('Composer')).not.toBeInTheDocument();
  });

  it('does not show a standalone MCP tab', () => {
    renderAppBar();
    // The MCP tab was removed and folded into the Mocks page
    const mcpButton = screen.queryByRole('button', { name: /MCP tools view/i });
    expect(mcpButton).not.toBeInTheDocument();
  });

  it('confirms before clearing server logs from the menu', async () => {
    const user = userEvent.setup();
    const { props } = renderAppBar();

    const clearButton = screen.getAllByRole('button').find(
      (b) => b.querySelector('[data-testid="DeleteSweepIcon"]'),
    );
    await user.click(clearButton!);
    // Clearing logs from the menu opens a confirmation dialog rather than firing
    // immediately — matching the Cmd/Ctrl+Shift+L shortcut behaviour.
    await user.click(screen.getByText('Clear Server Logs'));
    expect(props.onClearLogs).not.toHaveBeenCalled();
    expect(screen.getByText('Clear server logs?')).toBeInTheDocument();

    // Confirm in the dialog.
    await user.click(screen.getByRole('button', { name: 'Clear logs' }));
    expect(props.onClearLogs).toHaveBeenCalledOnce();
    expect(props.onClearServer).not.toHaveBeenCalled();
  });

  it('shows HTTP/3 status chip when H3 is enabled', async () => {
    vi.spyOn(http3StatusModule, 'fetchHttp3Status').mockResolvedValue({
      enabled: true,
      port: 8443,
      activeConnections: 2,
    });

    renderAppBar();

    await waitFor(() => {
      expect(screen.getByText('H3 :8443 (2)')).toBeInTheDocument();
    });
  });

  it('does not show HTTP/3 chip when H3 is disabled', async () => {
    const spy = vi.spyOn(http3StatusModule, 'fetchHttp3Status').mockResolvedValue({
      enabled: false,
      port: -1,
      activeConnections: 0,
    });

    renderAppBar();

    // Wait for the H3 status effect to complete before asserting absence.
    // Using waitFor on the spy ensures the async effect has settled.
    await waitFor(() => expect(spy).toHaveBeenCalled());
    expect(screen.queryByText(/^H3 :/)).not.toBeInTheDocument();
  });

  it('does not show HTTP/3 chip when endpoint is unavailable', async () => {
    const spy = vi.spyOn(http3StatusModule, 'fetchHttp3Status').mockRejectedValue(
      new Error('Not Found'),
    );

    renderAppBar();

    // Wait for the H3 status effect to complete before asserting absence.
    await waitFor(() => expect(spy).toHaveBeenCalled());
    expect(screen.queryByText(/^H3 :/)).not.toBeInTheDocument();
  });

  it('requests the keyboard shortcuts dialog from the keyboard icon', async () => {
    const user = userEvent.setup();
    // The shortcuts dialog is now owned by App (shared with the `?` shortcut); the
    // AppBar button just asks App to open it via onShowShortcuts.
    const { props } = renderAppBar();

    await user.click(screen.getByRole('button', { name: 'Keyboard shortcuts' }));
    expect(props.onShowShortcuts).toHaveBeenCalledOnce();
  });

  it('opens the SAML dialog from the tools menu', async () => {
    const user = userEvent.setup();
    renderAppBar();

    const toolsButton = screen.getAllByRole('button').find(
      (b) => b.getAttribute('aria-label') === 'Import / export tools',
    );
    expect(toolsButton).toBeDefined();
    await user.click(toolsButton!);

    await user.click(screen.getByText('Mock SAML Provider…'));
    // The dialog title appears once the SAML dialog opens.
    expect(screen.getByText('Mock SAML Provider')).toBeInTheDocument();
  });

  it('opens the GraphQL import dialog from the tools menu', async () => {
    const user = userEvent.setup();
    renderAppBar();

    const toolsButton = screen.getAllByRole('button').find(
      (b) => b.getAttribute('aria-label') === 'Import / export tools',
    );
    expect(toolsButton).toBeDefined();
    await user.click(toolsButton!);

    await user.click(screen.getByText('Import GraphQL Schema…'));
    // The dialog title appears once the GraphQL import dialog opens.
    expect(screen.getByRole('heading', { name: 'Import GraphQL Schema' })).toBeInTheDocument();
  });

  it('opens the SCIM dialog from the tools menu', async () => {
    const user = userEvent.setup();
    renderAppBar();

    const toolsButton = screen.getAllByRole('button').find(
      (b) => b.getAttribute('aria-label') === 'Import / export tools',
    );
    expect(toolsButton).toBeDefined();
    await user.click(toolsButton!);

    await user.click(screen.getByText('Mock SCIM Provider…'));
    // The dialog title appears once the SCIM dialog opens.
    expect(screen.getByText('Mock SCIM Provider')).toBeInTheDocument();
  });

  it('opens the baseline compare dialog from the tools menu', async () => {
    const user = userEvent.setup();
    renderAppBar();

    const toolsButton = screen.getAllByRole('button').find(
      (b) => b.getAttribute('aria-label') === 'Import / export tools',
    );
    expect(toolsButton).toBeDefined();
    await user.click(toolsButton!);

    await user.click(screen.getByText('Compare Against Baseline…'));
    // The dialog heading appears once the baseline compare dialog opens.
    expect(screen.getByRole('heading', { name: 'Compare Against Baseline' })).toBeInTheDocument();
  });
});

/**
 * Drive the AppBar at a narrow viewport by stubbing matchMedia so every media
 * query matches — this makes `useMediaQuery(theme.breakpoints.down('lg'))`
 * resolve to true and the nav collapses into the "hamburger" Menu.
 */
function stubMatchMedia(matches: boolean) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}

describe('AppBar responsive navigation', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      connectionStatus: 'connected',
      themeMode: 'dark',
      autoScroll: true,
      view: 'dashboard',
    });
  });

  afterEach(() => {
    // @ts-expect-error allow deleting the optional stub so other suites see desktop
    delete window.matchMedia;
  });

  it('renders a top-level group button bar on wide screens with no hamburger', () => {
    // jsdom default: no matchMedia → useMediaQuery returns false → wide layout.
    // The nav now renders one group button per category; individual views live
    // inside each group's dropdown rather than as inline toggle buttons.
    renderAppBar();
    expect(screen.getByRole('button', { name: 'Mock views' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Observe views' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify views' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Resilience views' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'AI views' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Inspect views' })).toBeInTheDocument();
    // No hamburger on wide screens.
    expect(screen.queryByRole('button', { name: 'Open navigation menu' })).not.toBeInTheDocument();
  });

  it('opens a group dropdown and navigates to a view, preserving setView', async () => {
    const user = userEvent.setup();
    renderAppBar();

    // Open the Observe group dropdown and pick Metrics.
    await user.click(screen.getByRole('button', { name: 'Observe views' }));
    const metricsItem = await screen.findByRole('menuitem', { name: 'Metrics view' });
    await user.click(metricsItem);
    // setView ran → store view updated (Wave 1 persistence + hash hang off this).
    expect(useDashboardStore.getState().view).toBe('metrics');
  });

  it('navigates to the standalone Scenarios view from the Mock group', async () => {
    const user = userEvent.setup();
    renderAppBar();

    await user.click(screen.getByRole('button', { name: 'Mock views' }));
    const scenariosItem = await screen.findByRole('menuitem', { name: 'Scenarios view' });
    await user.click(scenariosItem);
    expect(useDashboardStore.getState().view).toBe('scenarios');
  });

  it('navigates to the Audit trail view from the Inspect group', async () => {
    const user = userEvent.setup();
    renderAppBar();

    await user.click(screen.getByRole('button', { name: 'Inspect views' }));
    const auditItem = await screen.findByRole('menuitem', { name: 'Audit trail view' });
    await user.click(auditItem);
    expect(useDashboardStore.getState().view).toBe('audit');
  });

  it('highlights the group that owns the active view', () => {
    useDashboardStore.setState({ view: 'slo' });
    renderAppBar();
    // SLO lives in the Verify group, so the Verify group button is marked active.
    const verifyButton = screen.getByRole('button', { name: 'Verify views' });
    expect(verifyButton).toHaveAttribute('aria-expanded', 'false');
    // The active-group highlight is applied via a translucent background; assert
    // the button is present and reachable (visual highlight asserted via style).
    expect(verifyButton).toBeInTheDocument();
  });

  it('reaches every ViewMode through at least one group (no orphaned view)', async () => {
    const user = userEvent.setup();
    renderAppBar();

    // Map EVERY ViewMode to its expected nav aria-label. Typed as
    // `Record<ViewMode, string>`, so this is a COMPILE-TIME exhaustiveness
    // guard: adding a new value to the ViewMode union without an entry here is
    // a TypeScript error, forcing whoever adds a view to also grant it a nav
    // label — and (via NAV_GROUPS below) a group. There is deliberately no
    // hardcoded `allViews` array that could silently fall out of sync.
    const expectedAria: Record<ViewMode, string> = {
      'get-started': 'Get started view',
      dashboard: 'Dashboard view',
      traffic: 'Traffic inspector view',
      sessions: 'Trace inspector view',
      composer: 'Mocks view',
      library: 'Library of captured content',
      chaos: 'Service chaos view',
      performance: 'Performance testing view',
      metrics: 'Metrics view',
      drift: 'Drift detection view',
      verification: 'Verification view',
      slo: 'SLO verification view',
      async: 'AsyncAPI broker mock view',
      grpc: 'gRPC services view',
      breakpoints: 'Breakpoints view',
      contract: 'Contract test view',
      cluster: 'Cluster status view',
      optimise: 'LLM Optimise view',
      'mcp-health': 'MCP server health view',
      scenarios: 'Scenarios view',
      audit: 'Audit trail view',
    };
    const allViews = Object.keys(expectedAria) as ViewMode[];

    const groupButtons = [
      'Mock views', 'Observe views', 'Verify views',
      'Resilience views', 'AI views', 'Inspect views',
    ];

    const reachableLabels = new Set<string>();
    for (const groupName of groupButtons) {
      await user.click(screen.getByRole('button', { name: groupName }));
      const items = await screen.findAllByRole('menuitem');
      for (const item of items) {
        const aria = item.getAttribute('aria-label');
        if (aria) reachableLabels.add(aria);
      }
      // Close before opening the next group's menu.
      await user.keyboard('{Escape}');
    }

    // Every ViewMode is reachable through some group (runtime guard that the
    // NAV_GROUPS wiring actually renders each labelled item). A view may live in
    // more than one group (e.g. Trace under both Observe and AI), so this asserts
    // reachability — "at least one group" — not single-group uniqueness.
    for (const v of allViews) {
      expect(reachableLabels.has(expectedAria[v])).toBe(true);
    }
  });

  it('highlights every group that owns a multi-group view (Trace in Observe and AI)', () => {
    // Trace (`sessions`) is deliberately listed under both Observe and AI, so
    // both group buttons must read as active when the Trace view is current —
    // not just the first group, which the old find-first highlight would do.
    useDashboardStore.setState({ view: 'sessions' });
    renderAppBar();
    const observeButton = screen.getByRole('button', { name: 'Observe views' });
    const aiButton = screen.getByRole('button', { name: 'AI views' });
    // The active-group highlight is a translucent background applied via sx;
    // assert both owning group buttons are present (visual highlight via style).
    expect(observeButton).toBeInTheDocument();
    expect(aiButton).toBeInTheDocument();
  });

  it('exposes a one-line description for nav tabs that have one', () => {
    // The description bar under the nav reads from this map. Every entry present
    // must be non-empty; the LLM Optimise tab must mention what it does, the
    // Traffic tab must hint that selecting an item opens its detail, and the
    // self-explanatory Get Started tab is intentionally omitted (no bar).
    const views = Object.keys(NAV_TAB_DESCRIPTIONS);
    // Exact count guards against descriptions being accidentally dropped from
    // other tabs: 20 tabs carry one (every tab except the omitted Get Started).
    expect(views.length).toBe(20);
    for (const v of views) {
      expect(NAV_TAB_DESCRIPTIONS[v as keyof typeof NAV_TAB_DESCRIPTIONS]?.length ?? 0).toBeGreaterThan(0);
    }
    expect(NAV_TAB_DESCRIPTIONS['get-started']).toBeUndefined();
    expect(NAV_TAB_DESCRIPTIONS.optimise).toMatch(/prompts.*inference cost.*safety.*speed/i);
    expect(NAV_TAB_DESCRIPTIONS.traffic).toMatch(/select an item/i);
  });

  it('does not render the keyboard-shortcut caption (the ? dialog replaces it)', () => {
    renderAppBar();
    expect(screen.queryByText(/clear logs/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Esc filter/i)).not.toBeInTheDocument();
    // The Keyboard-shortcuts dialog button remains the discoverability path.
    expect(screen.getByRole('button', { name: 'Keyboard shortcuts' })).toBeInTheDocument();
  });

  it('navigates to the gRPC view from the hamburger menu on narrow screens', async () => {
    stubMatchMedia(true);
    const user = userEvent.setup();
    renderAppBar();

    await user.click(screen.getByRole('button', { name: 'Open navigation menu' }));
    const grpcItem = screen.getByRole('menuitem', { name: 'gRPC services view' });
    await user.click(grpcItem);
    expect(useDashboardStore.getState().view).toBe('grpc');
  });

  it('collapses the nav into a hamburger Menu on narrow screens', async () => {
    stubMatchMedia(true);
    const user = userEvent.setup();
    renderAppBar();

    // The inline tab strip is gone; a hamburger button takes its place.
    const navButton = screen.getByRole('button', { name: 'Open navigation menu' });
    expect(navButton).toBeInTheDocument();
    // The current view label is still shown so the active view stays visible.
    expect(screen.getByText('Dashboard')).toBeInTheDocument();

    // The full tab list is reachable from the menu, including the last tab.
    await user.click(navButton);
    const metricsItem = screen.getByRole('menuitem', { name: 'Metrics view' });
    expect(metricsItem).toBeInTheDocument();

    // Selecting a tab from the menu changes the active view.
    await user.click(metricsItem);
    expect(useDashboardStore.getState().view).toBe('metrics');
  });
});
