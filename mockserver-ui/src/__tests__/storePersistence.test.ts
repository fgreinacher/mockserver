import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import type { ViewMode } from '../store';

/**
 * View/search persistence is resolved at store-module init time (localStorage +
 * URL hash are read once when the store is created). To exercise that init path
 * with different starting conditions, each test sets up localStorage/hash, then
 * resets the module registry and dynamically imports a fresh store instance.
 */
async function freshStore() {
  vi.resetModules();
  const mod = await import('../store');
  return mod.useDashboardStore;
}

describe('view + search persistence', () => {
  beforeEach(() => {
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
  });

  afterEach(() => {
    globalThis.localStorage.clear();
    globalThis.location.hash = '';
    vi.resetModules();
  });

  describe('initial view restoration', () => {
    it('defaults to get-started on a genuine first visit (nothing persisted)', async () => {
      const store = await freshStore();
      expect(store.getState().view).toBe('get-started');
    });

    it('restores a previously-persisted view from localStorage', async () => {
      globalThis.localStorage.setItem('mockserver-view', 'contract');
      const store = await freshStore();
      expect(store.getState().view).toBe('contract');
    });

    it('ignores an invalid persisted view and falls back to get-started', async () => {
      globalThis.localStorage.setItem('mockserver-view', 'totally-bogus');
      const store = await freshStore();
      expect(store.getState().view).toBe('get-started');
    });

    it('migrates a legacy persisted view value', async () => {
      globalThis.localStorage.setItem('mockserver-view', 'mcp-tools');
      const store = await freshStore();
      expect(store.getState().view).toBe('composer');
    });
  });

  describe('URL hash precedence', () => {
    it('a valid hash view takes precedence over localStorage', async () => {
      globalThis.localStorage.setItem('mockserver-view', 'contract');
      globalThis.location.hash = '#/metrics';
      const store = await freshStore();
      expect(store.getState().view).toBe('metrics');
    });

    it('an invalid hash is ignored and localStorage is used instead', async () => {
      globalThis.localStorage.setItem('mockserver-view', 'drift');
      globalThis.location.hash = '#/not-a-view';
      const store = await freshStore();
      expect(store.getState().view).toBe('drift');
    });

    it('an invalid hash with nothing persisted falls back to get-started', async () => {
      globalThis.location.hash = '#/nonsense';
      const store = await freshStore();
      expect(store.getState().view).toBe('get-started');
    });

    it('accepts a hash without a leading slash', async () => {
      globalThis.location.hash = '#chaos';
      const store = await freshStore();
      expect(store.getState().view).toBe('chaos');
    });
  });

  describe('persisting on view change', () => {
    it('setView writes the view to localStorage and the URL hash', async () => {
      const store = await freshStore();
      store.getState().setView('verification' as ViewMode);
      expect(globalThis.localStorage.getItem('mockserver-view')).toBe('verification');
      expect(globalThis.location.hash).toBe('#/verification');
    });

    it('a view persisted via setView is restored on the next load', async () => {
      const store = await freshStore();
      store.getState().setView('library' as ViewMode);
      // Hash now also points at library; clear it so localStorage is exercised.
      globalThis.location.hash = '';
      const reloaded = await freshStore();
      expect(reloaded.getState().view).toBe('library');
    });

    it('clearUI resets the persisted view to get-started', async () => {
      const store = await freshStore();
      store.getState().setView('drift' as ViewMode);
      store.getState().clearUI();
      expect(globalThis.localStorage.getItem('mockserver-view')).toBe('get-started');
    });

    it('setView migrates a legacy value before persisting view + hash', async () => {
      const store = await freshStore();
      store.getState().setView('mcp-tools' as ViewMode);
      expect(store.getState().view).toBe('composer');
      expect(globalThis.localStorage.getItem('mockserver-view')).toBe('composer');
      expect(globalThis.location.hash).toBe('#/composer');
    });
  });

  describe('search persistence', () => {
    it('persists a search term and restores it on the next load', async () => {
      const store = await freshStore();
      store.getState().setLogSearch('timeout');
      store.getState().setTrafficSearch('POST /api');
      globalThis.location.hash = '';
      const reloaded = await freshStore();
      expect(reloaded.getState().logSearch).toBe('timeout');
      expect(reloaded.getState().trafficSearch).toBe('POST /api');
    });

    it('persists every per-panel search field independently', async () => {
      const store = await freshStore();
      store.getState().setLogSearch('a');
      store.getState().setExpectationSearch('b');
      store.getState().setReceivedSearch('c');
      store.getState().setProxiedSearch('d');
      const reloaded = await freshStore();
      const s = reloaded.getState();
      expect(s.logSearch).toBe('a');
      expect(s.expectationSearch).toBe('b');
      expect(s.receivedSearch).toBe('c');
      expect(s.proxiedSearch).toBe('d');
    });

    it('defaults all search terms to empty on a first visit', async () => {
      const store = await freshStore();
      const s = store.getState();
      expect(s.logSearch).toBe('');
      expect(s.expectationSearch).toBe('');
      expect(s.receivedSearch).toBe('');
      expect(s.proxiedSearch).toBe('');
      expect(s.trafficSearch).toBe('');
    });

    it('persists ONLY the five search fields, never the whole store', async () => {
      const store = await freshStore();
      // Load the store with bulky entity data that must NOT leak into localStorage.
      store.getState().applyMessage({
        logMessages: [],
        activeExpectations: Array.from({ length: 50 }, (_, i) => ({ key: `e${i}`, value: { httpRequest: { path: '/secret' }, big: 'x'.repeat(1000) } })),
        recordedRequests: [{ key: 'r', value: { body: 'y'.repeat(1000) } }],
        proxiedRequests: [],
      });
      store.getState().setLogSearch('term');

      const raw = globalThis.localStorage.getItem('mockserver-search')!;
      const parsed = JSON.parse(raw);
      expect(Object.keys(parsed).sort()).toEqual([
        'expectationSearch', 'logSearch', 'proxiedSearch', 'receivedSearch', 'trafficSearch',
      ]);
      // No entity data bled through (the per-keystroke payload stays tiny).
      expect(raw).not.toContain('secret');
      expect(raw).not.toContain('httpRequest');
      expect(raw.length).toBeLessThan(200);
    });

    it('tolerates malformed persisted search JSON', async () => {
      globalThis.localStorage.setItem('mockserver-search', '{not valid json');
      const store = await freshStore();
      expect(store.getState().logSearch).toBe('');
    });
  });

  describe('theme persistence is preserved', () => {
    it('still restores the persisted theme independently of view/search', async () => {
      globalThis.localStorage.setItem('mockserver-theme', 'light');
      const store = await freshStore();
      expect(store.getState().themeMode).toBe('light');
    });
  });
});
