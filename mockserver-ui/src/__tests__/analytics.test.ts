import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

// posthog-js is dynamically imported by the analytics module. Mock it so we can
// assert exactly which events (if any) are captured, and never load the real SDK.
const initMock = vi.fn();
const captureMock = vi.fn();
const optOutMock = vi.fn();
vi.mock('posthog-js', () => ({
  default: {
    init: (...args: unknown[]) => initMock(...args),
    capture: (...args: unknown[]) => captureMock(...args),
    opt_out_capturing: (...args: unknown[]) => optOutMock(...args),
  },
}));

// The analytics module reads themeMode from the store. Mock it so the module
// has no real store dependency in these unit tests.
vi.mock('../store', () => ({
  useDashboardStore: { getState: () => ({ themeMode: 'dark' }) },
}));

import {
  initAnalytics,
  trackView,
  trackFeature,
  trackError,
  setAnalyticsOptOut,
  isAnalyticsActive,
  onAnalyticsActivated,
  __resetAnalyticsForTests,
} from '../lib/analytics';
import type { Configuration } from '../lib/configuration';

const ACTIVE_CONFIG: Configuration = {
  dashboardAnalyticsEnabled: true,
  dashboardAnalyticsEndpoint: 'https://posthog.example.com',
  dashboardAnalyticsKey: 'phc_test_key',
};

// A representative view whitelist (mirrors the store's ALL_VIEWS) plus the
// initial view, supplied to initAnalytics so trackView is whitelisted and the
// initial view is emitted at activation.
const KNOWN_VIEWS = ['dashboard', 'traffic', 'composer', 'chaos'] as const;
const INIT_OPTIONS = { validViews: KNOWN_VIEWS, initialView: 'dashboard' as string };

/** Wait for the dynamic import(.then) chain inside initAnalytics to settle. */
async function flushMicrotasks(): Promise<void> {
  // The module does `import('posthog-js').then(...)`. A dynamic import resolves
  // on a later task (not just a microtask) in the vitest/jsdom runtime, so yield
  // a real macrotask and then drain the microtask queue a few times.
  for (let i = 0; i < 5; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
    await Promise.resolve();
  }
}

/** Override navigator/window props for a single test. */
function setNav(props: Record<string, unknown>): void {
  for (const [k, v] of Object.entries(props)) {
    Object.defineProperty(navigator, k, { value: v, configurable: true });
  }
}

beforeEach(() => {
  __resetAnalyticsForTests();
  initMock.mockClear();
  captureMock.mockClear();
  optOutMock.mockClear();
  localStorage.clear();
  // Reset the privacy-relevant navigator/window signals to the "allowed" state.
  setNav({ doNotTrack: null, msDoNotTrack: null, globalPrivacyControl: undefined, onLine: true, webdriver: false });
  Object.defineProperty(window, 'doNotTrack', { value: null, configurable: true });
  delete (window as unknown as { acquireVsCodeApi?: unknown }).acquireVsCodeApi;
  delete (window as unknown as { __MOCKSERVER_IDE_TELEMETRY_DISABLED__?: boolean }).__MOCKSERVER_IDE_TELEMETRY_DISABLED__;
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('analytics activation gates', () => {
  it('activates and emits app_open only when every gate passes', async () => {
    initAnalytics(ACTIVE_CONFIG, INIT_OPTIONS);
    await flushMicrotasks();

    expect(initMock).toHaveBeenCalledTimes(1);
    const [key, options] = initMock.mock.calls[0]!;
    expect(key).toBe('phc_test_key');
    expect(options).toMatchObject({
      api_host: 'https://posthog.example.com',
      persistence: 'memory',
      autocapture: false,
      capture_pageview: false,
      capture_pageleave: false,
      disable_session_recording: true,
      disable_surveys: true,
    });

    expect(isAnalyticsActive()).toBe(true);
    expect(captureMock).toHaveBeenCalledWith(
      'app_open',
      expect.objectContaining({ app_version: expect.any(String), surface: 'browser', theme: 'dark' }),
    );
    // app_open must NOT carry any unexpected keys. `distribution` is always
    // present (normalised to `unknown` when no config value is supplied).
    const appOpenProps = captureMock.mock.calls.find((c) => c[0] === 'app_open')![1] as Record<string, unknown>;
    expect(Object.keys(appOpenProps).sort()).toEqual(['app_version', 'distribution', 'surface', 'theme']);
    // Absent config value normalises to `unknown` — never raw/free-text.
    expect(appOpenProps.distribution).toBe('unknown');
  });

  it('sends a valid distribution value on app_open', async () => {
    initAnalytics({ ...ACTIVE_CONFIG, dashboardAnalyticsDistribution: 'docker-graaljs' }, INIT_OPTIONS);
    await flushMicrotasks();
    const appOpenProps = captureMock.mock.calls.find((c) => c[0] === 'app_open')![1] as Record<string, unknown>;
    expect(appOpenProps.distribution).toBe('docker-graaljs');
  });

  it('sends the -http3 image distribution value on app_open', async () => {
    initAnalytics({ ...ACTIVE_CONFIG, dashboardAnalyticsDistribution: 'docker-http3' }, INIT_OPTIONS);
    await flushMicrotasks();
    const appOpenProps = captureMock.mock.calls.find((c) => c[0] === 'app_open')![1] as Record<string, unknown>;
    expect(appOpenProps.distribution).toBe('docker-http3');
  });

  it('normalises an out-of-allow-list distribution to unknown (privacy invariant)', async () => {
    // A free-text / unexpected value must NEVER be forwarded raw.
    initAnalytics(
      { ...ACTIVE_CONFIG, dashboardAnalyticsDistribution: 'GET https://api.internal/secret' },
      INIT_OPTIONS,
    );
    await flushMicrotasks();
    const appOpenProps = captureMock.mock.calls.find((c) => c[0] === 'app_open')![1] as Record<string, unknown>;
    expect(appOpenProps.distribution).toBe('unknown');
    // The raw value must not appear anywhere in the captured params.
    expect(JSON.stringify(appOpenProps)).not.toContain('api.internal');
  });

  it('normalises a blank distribution to unknown', async () => {
    initAnalytics({ ...ACTIVE_CONFIG, dashboardAnalyticsDistribution: '' }, INIT_OPTIONS);
    await flushMicrotasks();
    const appOpenProps = captureMock.mock.calls.find((c) => c[0] === 'app_open')![1] as Record<string, unknown>;
    expect(appOpenProps.distribution).toBe('unknown');
  });

  it('activates normally when the distribution config value is absent', async () => {
    // Absence of dashboardAnalyticsDistribution must not change activation behaviour.
    initAnalytics(ACTIVE_CONFIG, INIT_OPTIONS);
    await flushMicrotasks();
    expect(isAnalyticsActive()).toBe(true);
    const appOpenProps = captureMock.mock.calls.find((c) => c[0] === 'app_open')![1] as Record<string, unknown>;
    expect(appOpenProps.distribution).toBe('unknown');
  });

  it('does NOT activate when the master switch is off', async () => {
    initAnalytics({ ...ACTIVE_CONFIG, dashboardAnalyticsEnabled: false });
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
    expect(isAnalyticsActive()).toBe(false);
  });

  it('does NOT activate when the endpoint is blank', async () => {
    initAnalytics({ ...ACTIVE_CONFIG, dashboardAnalyticsEndpoint: '' });
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('does NOT activate when the endpoint is whitespace only', async () => {
    initAnalytics({ ...ACTIVE_CONFIG, dashboardAnalyticsEndpoint: '   ' });
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('does NOT activate when the key is blank', async () => {
    initAnalytics({ ...ACTIVE_CONFIG, dashboardAnalyticsKey: '' });
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('does NOT activate when navigator.doNotTrack is "1"', async () => {
    setNav({ doNotTrack: '1' });
    initAnalytics(ACTIVE_CONFIG);
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('does NOT activate when window.doNotTrack is "1"', async () => {
    Object.defineProperty(window, 'doNotTrack', { value: '1', configurable: true });
    initAnalytics(ACTIVE_CONFIG);
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('does NOT activate when navigator.msDoNotTrack is "1"', async () => {
    setNav({ msDoNotTrack: '1' });
    initAnalytics(ACTIVE_CONFIG);
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('does NOT activate when Global Privacy Control is set', async () => {
    setNav({ globalPrivacyControl: true });
    initAnalytics(ACTIVE_CONFIG);
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('does NOT activate when opted out locally', async () => {
    localStorage.setItem('mockserver.analytics.optOut', 'true');
    initAnalytics(ACTIVE_CONFIG);
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('does NOT activate when offline', async () => {
    setNav({ onLine: false });
    initAnalytics(ACTIVE_CONFIG);
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('does NOT activate under automation (webdriver)', async () => {
    setNav({ webdriver: true });
    initAnalytics(ACTIVE_CONFIG);
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('does NOT activate inside a VS Code webview with telemetry disabled', async () => {
    (window as unknown as { acquireVsCodeApi: () => void }).acquireVsCodeApi = () => {};
    (window as unknown as { __MOCKSERVER_IDE_TELEMETRY_DISABLED__: boolean }).__MOCKSERVER_IDE_TELEMETRY_DISABLED__ = true;
    initAnalytics(ACTIVE_CONFIG);
    await flushMicrotasks();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('reports surface=ide-embedded when in a VS Code webview but telemetry allowed', async () => {
    (window as unknown as { acquireVsCodeApi: () => void }).acquireVsCodeApi = () => {};
    initAnalytics(ACTIVE_CONFIG, INIT_OPTIONS);
    await flushMicrotasks();
    const appOpenProps = captureMock.mock.calls.find((c) => c[0] === 'app_open')![1] as Record<string, unknown>;
    expect(appOpenProps.surface).toBe('ide-embedded');
  });

  it('is a one-shot decision — a second call does not re-init', async () => {
    initAnalytics(ACTIVE_CONFIG, INIT_OPTIONS);
    await flushMicrotasks();
    initAnalytics(ACTIVE_CONFIG, INIT_OPTIONS);
    await flushMicrotasks();
    expect(initMock).toHaveBeenCalledTimes(1);
  });

  it('emits the initial view exactly once at activation (MINOR-1)', async () => {
    initAnalytics(ACTIVE_CONFIG, { validViews: KNOWN_VIEWS, initialView: 'composer' });
    await flushMicrotasks();
    const viewChanges = captureMock.mock.calls.filter((c) => c[0] === 'view_change');
    expect(viewChanges).toHaveLength(1);
    expect(viewChanges[0]![1]).toEqual({ view: 'composer' });
  });

  it('does NOT emit an initial view when none is supplied', async () => {
    initAnalytics(ACTIVE_CONFIG, { validViews: KNOWN_VIEWS });
    await flushMicrotasks();
    expect(captureMock.mock.calls.some((c) => c[0] === 'view_change')).toBe(false);
  });

  it('does NOT emit an initial view that is outside the whitelist', async () => {
    initAnalytics(ACTIVE_CONFIG, { validViews: KNOWN_VIEWS, initialView: 'http://evil.example.com' });
    await flushMicrotasks();
    expect(captureMock.mock.calls.some((c) => c[0] === 'view_change')).toBe(false);
  });

  it('notifies activation subscribers when activation completes', async () => {
    const listener = vi.fn();
    onAnalyticsActivated(listener);
    initAnalytics(ACTIVE_CONFIG, INIT_OPTIONS);
    await flushMicrotasks();
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('does NOT notify activation subscribers when a gate fails', async () => {
    const listener = vi.fn();
    onAnalyticsActivated(listener);
    initAnalytics({ ...ACTIVE_CONFIG, dashboardAnalyticsEnabled: false }, INIT_OPTIONS);
    await flushMicrotasks();
    expect(listener).not.toHaveBeenCalled();
  });
});

describe('event capture when active', () => {
  beforeEach(async () => {
    // No initialView here so the only view_change events come from the test bodies.
    initAnalytics(ACTIVE_CONFIG, { validViews: KNOWN_VIEWS });
    await flushMicrotasks();
    captureMock.mockClear();
  });

  it('trackView emits view_change with only { view }', () => {
    trackView('dashboard');
    expect(captureMock).toHaveBeenCalledWith('view_change', { view: 'dashboard' });
  });

  it('trackView ignores an empty view', () => {
    trackView('');
    expect(captureMock).not.toHaveBeenCalled();
  });

  it('trackView DROPS a value outside the ViewMode whitelist (MINOR-2)', () => {
    trackView('GET https://api.internal/secret');
    expect(captureMock).not.toHaveBeenCalled();
  });

  it('trackFeature emits feature_used for an enum value', () => {
    trackFeature('chaos_started');
    expect(captureMock).toHaveBeenCalledWith('feature_used', { feature: 'chaos_started' });
  });

  it('trackFeature includes a whitelisted mode but nothing else', () => {
    trackFeature('export_performed', { mode: 'advanced' });
    expect(captureMock).toHaveBeenCalledWith('feature_used', { feature: 'export_performed', mode: 'advanced' });
  });

  it('trackFeature DROPS a non-enum feature value (privacy invariant)', () => {
    // @ts-expect-error — deliberately passing an invalid value
    trackFeature('http://evil.example.com/secret');
    expect(captureMock).not.toHaveBeenCalled();
  });

  it('trackFeature DROPS a non-enum mode value', () => {
    // @ts-expect-error — deliberately passing an invalid mode
    trackFeature('chaos_started', { mode: 'GET https://api.internal/secret' });
    expect(captureMock).toHaveBeenCalledWith('feature_used', { feature: 'chaos_started' });
    const props = captureMock.mock.calls[0]![1] as Record<string, unknown>;
    expect(props.mode).toBeUndefined();
  });

  it('trackError emits error_shown for an enum category', () => {
    trackError('connection_failed');
    expect(captureMock).toHaveBeenCalledWith('error_shown', { category: 'connection_failed' });
  });

  it('trackError DROPS a non-enum category (privacy invariant)', () => {
    // @ts-expect-error — deliberately passing a free-text message
    trackError('TypeError: cannot read property foo of undefined at line 42');
    expect(captureMock).not.toHaveBeenCalled();
  });
});

describe('tracking is inert when not active', () => {
  beforeEach(async () => {
    initAnalytics({ ...ACTIVE_CONFIG, dashboardAnalyticsEnabled: false });
    await flushMicrotasks();
  });

  it('no track* call captures anything', () => {
    trackView('dashboard');
    trackFeature('chaos_started');
    trackError('unknown');
    expect(captureMock).not.toHaveBeenCalled();
  });
});

describe('opt-out', () => {
  it('persists the flag and stops an active capture', async () => {
    initAnalytics(ACTIVE_CONFIG);
    await flushMicrotasks();
    expect(isAnalyticsActive()).toBe(true);

    setAnalyticsOptOut(true);
    expect(localStorage.getItem('mockserver.analytics.optOut')).toBe('true');
    expect(optOutMock).toHaveBeenCalledTimes(1);
    expect(isAnalyticsActive()).toBe(false);

    captureMock.mockClear();
    trackFeature('chaos_started');
    expect(captureMock).not.toHaveBeenCalled();
  });

  it('clears the flag when opting back in', () => {
    setAnalyticsOptOut(true);
    expect(localStorage.getItem('mockserver.analytics.optOut')).toBe('true');
    setAnalyticsOptOut(false);
    expect(localStorage.getItem('mockserver.analytics.optOut')).toBeNull();
  });
});
