import { create } from 'zustand';
import type {
  ConnectionStatus,
  DebugMismatchResult,
  JsonListItem,
  LogMessage,
  RequestFilter,
  ThemeMode,
  WebSocketMessage,
} from '../types';
import { ACTION_TYPES, LLM_PROVIDERS } from '../lib/clientFilters';

export type ViewMode = 'dashboard' | 'traffic' | 'sessions' | 'composer' | 'library' | 'chaos' | 'performance' | 'metrics' | 'drift' | 'verification' | 'slo' | 'async' | 'grpc' | 'breakpoints' | 'contract' | 'cluster' | 'optimise' | 'mcp-health' | 'get-started';

/** Map legacy/removed ViewMode values to their replacement. */
const VIEW_MIGRATION: Record<string, ViewMode> = {
  'mcp-tools': 'composer',
};

/** Every valid ViewMode, used to validate persisted/hash-derived values. */
export const ALL_VIEWS: readonly ViewMode[] = [
  'dashboard', 'traffic', 'sessions', 'composer', 'library', 'chaos', 'performance',
  'metrics', 'drift', 'verification', 'slo', 'async', 'grpc', 'breakpoints', 'contract',
  'cluster', 'optimise', 'mcp-health', 'get-started',
];

const VIEW_STORAGE_KEY = 'mockserver-view';
const SEARCH_STORAGE_KEY = 'mockserver-search';

/**
 * Coerce an arbitrary string into a valid ViewMode, applying legacy migrations.
 * Returns null when the value is unknown — callers fall back to their default,
 * so a stale or hand-edited persisted/hash value can never poison the view.
 */
export function coerceView(value: string | null | undefined): ViewMode | null {
  if (!value) return null;
  const migrated = VIEW_MIGRATION[value] ?? value;
  return (ALL_VIEWS as readonly string[]).includes(migrated) ? (migrated as ViewMode) : null;
}

/** Read the view encoded in the URL hash (e.g. `#/contract`), if any and valid. */
function viewFromHash(): ViewMode | null {
  try {
    const hash = globalThis.location?.hash ?? '';
    const match = /^#\/?(.+)$/.exec(hash);
    return match ? coerceView(match[1]) : null;
  } catch {
    return null;
  }
}

/**
 * Resolve the view to land on at startup. Precedence:
 *   1. a valid view in the URL hash (linkable/deep-link),
 *   2. else a previously-persisted view from localStorage,
 *   3. else 'get-started' for a genuine first visit (nothing persisted).
 */
function getInitialView(): ViewMode {
  const fromHash = viewFromHash();
  if (fromHash) return fromHash;
  try {
    const stored = coerceView(globalThis.localStorage?.getItem(VIEW_STORAGE_KEY));
    if (stored) return stored;
  } catch {
    // localStorage may not be available in test/SSR environments
  }
  return 'get-started';
}

/** Persist the active view to localStorage and reflect it in the URL hash. */
function persistView(view: ViewMode): void {
  try { globalThis.localStorage?.setItem(VIEW_STORAGE_KEY, view); } catch { /* noop */ }
  try {
    // Only assign when the hash actually differs so this can never trigger a
    // hashchange event (and thus a feedback loop with App's hashchange listener),
    // regardless of how the browser coalesces identical assignments.
    const next = `#/${view}`;
    if (globalThis.location && globalThis.location.hash !== next) {
      globalThis.location.hash = next;
    }
  } catch { /* noop */ }
}

interface PersistedSearch {
  logSearch: string;
  expectationSearch: string;
  receivedSearch: string;
  proxiedSearch: string;
  trafficSearch: string;
}

const EMPTY_SEARCH: PersistedSearch = {
  logSearch: '', expectationSearch: '', receivedSearch: '', proxiedSearch: '', trafficSearch: '',
};

/** Restore the persisted per-panel search/filter terms; defaults to empty. */
function getInitialSearch(): PersistedSearch {
  try {
    const raw = globalThis.localStorage?.getItem(SEARCH_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<PersistedSearch>;
      return {
        logSearch: typeof parsed.logSearch === 'string' ? parsed.logSearch : '',
        expectationSearch: typeof parsed.expectationSearch === 'string' ? parsed.expectationSearch : '',
        receivedSearch: typeof parsed.receivedSearch === 'string' ? parsed.receivedSearch : '',
        proxiedSearch: typeof parsed.proxiedSearch === 'string' ? parsed.proxiedSearch : '',
        trafficSearch: typeof parsed.trafficSearch === 'string' ? parsed.trafficSearch : '',
      };
    }
  } catch {
    // unavailable or malformed — fall through to empty defaults
  }
  return { ...EMPTY_SEARCH };
}

/** Persist the current set of per-panel search/filter terms to localStorage. */
function persistSearch(search: PersistedSearch): void {
  try { globalThis.localStorage?.setItem(SEARCH_STORAGE_KEY, JSON.stringify(search)); } catch { /* noop */ }
}

/**
 * Per-panel cache mapping each `key` to the reference currently held for it and
 * that reference's serialized form. Lets {@link reconcileByKey} compare a
 * freshly-parsed entry against the previous one with a single `JSON.stringify`
 * of the *new* entry, instead of re-serializing both sides on every push.
 *
 * One cache instance is passed per entity array (the four panels never collide).
 * The stored `str` is trusted only when the stored `ref` is identical to the
 * previous entry being compared, so a stale cache (e.g. after a direct
 * `setState` that bypasses this function) can never cause a wrong comparison.
 */
type ReconcileCache = Map<string, { ref: unknown; str: string }>;

const logMessagesCache: ReconcileCache = new Map();
const activeExpectationsCache: ReconcileCache = new Map();
const recordedRequestsCache: ReconcileCache = new Map();
const proxiedRequestsCache: ReconcileCache = new Map();

/**
 * Reconcile a freshly-parsed array against the previous one, preserving object
 * identity for entries whose content is unchanged.
 *
 * Every WebSocket push delivers brand-new parsed objects, so without this each
 * row would be a new reference on every tick — and `React.memo` on the row
 * components could never skip a re-render. Entries are matched by their stable
 * `key`; equality is a structural (JSON) compare. Unchanged entries keep their
 * previous reference, which keeps memoized rows and their `useMemo([item.value])`
 * hooks valid across pushes. The reused reference is the whole entry, so nested
 * children (e.g. a log group's entries) are preserved for free.
 *
 * Equality semantics are identical to a deep JSON compare, but each entry is
 * serialized at most once per lifetime: the previous reference's string is read
 * from {@link cache}, and only the new entry is stringified per push (the
 * previous code stringified both sides for every matched row, ~100 rows × 4
 * panels × ~1/sec). The cache stores both the kept reference and its string and
 * the cached string is only trusted when its `ref` is *identical* to the
 * previous entry — so a stale cache (e.g. after a direct `setState`) is detected
 * and the previous entry is re-serialized rather than mis-compared. When an
 * entry is unchanged we return — and keep cached — the previous reference and
 * its string; when it changes we cache the new string. Keys absent from `next`
 * are pruned so the cache cannot grow unbounded.
 *
 * ARRAY identity (L1): when the reconciled result is element-for-element
 * identical to `prev` (same references, same order, same length) we return the
 * ORIGINAL `prev` array rather than the freshly-mapped copy. The server re-sends
 * the full list roughly once a second even when nothing has changed; without
 * this, every push replaced all four array identities and re-rendered every
 * subscribed panel (and invalidated every `useMemo([array])` downstream) for no
 * visible change. Returning `prev` lets a Zustand selector on the array short-
 * circuit the whole subtree. The cache is left untouched in this case because it
 * is already valid for the preserved references.
 *
 * Serialization cost (L2): the dominant cost is `JSON.stringify(n)` on each new
 * entry, which is irreducible for a structural change-detection compare without
 * a protocol change (the server would have to send a per-entry hash/version) —
 * explicitly out of scope. The only safe, clear pre-`stringify` discriminator
 * across these heterogeneous entry shapes is reference identity, so we check
 * `p === n` FIRST and reuse the cached string for that reference instead of
 * re-serializing it. No speculative field-level discriminator is added: the
 * measured throughput win comes from L1 (skipping re-renders on idle re-pushes),
 * not from trying to eliminate a stringify that is genuinely needed whenever an
 * entry's content actually differs.
 */
function reconcileByKey<T extends { key: string }>(prev: T[], next: T[], cache: ReconcileCache): T[] {
  if (next.length === 0) {
    cache.clear();
    return next;
  }
  if (prev.length === 0) {
    cache.clear();
    for (const n of next) cache.set(n.key, { ref: n, str: JSON.stringify(n) });
    return next;
  }
  const prevByKey = new Map(prev.map((p) => [p.key, p] as const));
  const nextCache: ReconcileCache = new Map();
  // Tracks whether the reconciled array diverges from `prev` in any position
  // (different length, a new/changed entry, or a reorder). Stays false only when
  // every element is the SAME reference at the SAME index as in `prev`.
  let changed = next.length !== prev.length;
  const result = next.map((n, i) => {
    const p = prevByKey.get(n.key);
    if (!p) {
      changed = true;
      nextCache.set(n.key, { ref: n, str: JSON.stringify(n) });
      return n;
    }
    if (p === n) {
      // Same reference already held — reuse its cached string (only when it was
      // recorded for THIS reference) instead of re-serializing an unchanged entry.
      const cached = cache.get(n.key);
      const str = cached && cached.ref === n ? cached.str : JSON.stringify(n);
      nextCache.set(n.key, { ref: n, str });
      if (prev[i] !== n) changed = true; // same ref but reordered
      return n;
    }
    const nStr = JSON.stringify(n);
    // Trust the cached string only when it was recorded for *this* reference;
    // otherwise (cold/stale cache after a direct setState) re-serialize `p`.
    const cached = cache.get(n.key);
    const pStr = cached && cached.ref === p ? cached.str : JSON.stringify(p);
    if (pStr === nStr) {
      // Semantically unchanged — preserve the previous reference and its string.
      nextCache.set(n.key, { ref: p, str: pStr });
      if (prev[i] !== p) changed = true; // preserved ref but reordered
      return p;
    }
    changed = true;
    nextCache.set(n.key, { ref: n, str: nStr });
    return n;
  });
  if (!changed) {
    // Element-for-element identical to `prev`: keep the previous array identity
    // so subscribed panels skip re-rendering. The existing cache already holds
    // valid { ref, str } entries for these preserved references, so leave it.
    return prev;
  }
  cache.clear();
  for (const [k, v] of nextCache) cache.set(k, v);
  return result;
}

interface DashboardState {
  logMessages: LogMessage[];
  activeExpectations: JsonListItem[];
  recordedRequests: JsonListItem[];
  proxiedRequests: JsonListItem[];

  view: ViewMode;
  requestFilter: RequestFilter;
  filterEnabled: boolean;
  filterExpanded: boolean;

  connectionStatus: ConnectionStatus;
  themeMode: ThemeMode;
  autoScroll: boolean;

  logSearch: string;
  expectationSearch: string;
  receivedSearch: string;
  proxiedSearch: string;
  trafficSearch: string;

  /** When false, FORWARDED_REQUEST log entries are hidden from the Log panel. */
  logShowForwarded: boolean;

  error: string | null;

  /**
   * Where the current {@link error} came from, so a data push only supersedes a
   * banner it is entitled to. `'push'` = carried in a WebSocket message
   * (`message.error`); `'client'` = set locally via {@link setError} (a
   * user-action failure such as a failed clear, a parse failure, or a
   * connection-lost notice). A clean data push clears a `'push'` error but must
   * leave a `'client'` error standing until it is dismissed or superseded.
   */
  errorSource: 'push' | 'client' | null;

  /** Transient toast/snackbar for action feedback (success/info/warning/error). */
  notification: { message: string; severity: 'success' | 'info' | 'warning' | 'error' } | null;

  debugMismatchOpen: boolean;
  debugMismatchLoading: boolean;
  debugMismatchResult: DebugMismatchResult | null;
  debugMismatchError: string | null;

  generateStubOpen: boolean;
  generateStubLoading: boolean;
  generateStubSuggestions: Record<string, unknown>[];
  generateStubConfidence: number;
  generateStubError: string | null;

  selectedTrafficKey: string | null;

  /**
   * An expectation handed off from the Active Expectations panel's "Edit"
   * action for the Composer to load into its form. Null when there is no
   * pending edit. The raw expectation value (the matcher + action JSON) is
   * stored as-is; the Composer consumes and then clears it.
   */
  pendingEditExpectation: Record<string, unknown> | null;

  actionTypeFilter: string[];
  llmProviderFilter: string[];

  setActionTypeFilter: (types: string[]) => void;
  setLlmProviderFilter: (providers: string[]) => void;

  /** Load an expectation into the Composer for editing and switch to that view. */
  editExpectation: (expectation: Record<string, unknown>) => void;
  /** Clear the pending edit handoff (called by the Composer once consumed). */
  clearPendingEditExpectation: () => void;

  /**
   * Pre-filled method/path to seed a new breakpoint matcher form, set when the
   * user clicks "Set breakpoint" on a log row. Consumed (and cleared) by the
   * Breakpoints panel once it has applied it to the registration form.
   */
  pendingBreakpointPrefill: { method?: string; path?: string } | null;
  /** Seed the breakpoint matcher form from a request and switch to that view. */
  setBreakpointPrefill: (prefill: { method?: string; path?: string }) => void;
  /** Clear the pending breakpoint prefill (called by the panel once consumed). */
  clearPendingBreakpointPrefill: () => void;

  applyMessage: (message: WebSocketMessage) => void;
  clearUI: () => void;
  setView: (view: ViewMode) => void;
  setRequestFilter: (filter: RequestFilter) => void;
  setFilterEnabled: (enabled: boolean) => void;
  setFilterExpanded: (expanded: boolean) => void;
  toggleFilterExpanded: () => void;
  setConnectionStatus: (status: ConnectionStatus) => void;
  setThemeMode: (mode: ThemeMode) => void;
  toggleThemeMode: () => void;
  setAutoScroll: (enabled: boolean) => void;
  toggleAutoScroll: () => void;
  setLogSearch: (term: string) => void;
  setLogShowForwarded: (show: boolean) => void;
  setExpectationSearch: (term: string) => void;
  setReceivedSearch: (term: string) => void;
  setProxiedSearch: (term: string) => void;
  setTrafficSearch: (term: string) => void;
  setSelectedTrafficKey: (key: string | null) => void;
  setError: (error: string | null) => void;
  setNotification: (notification: { message: string; severity: 'success' | 'info' | 'warning' | 'error' } | null) => void;
  openDebugMismatch: (result: DebugMismatchResult) => void;
  closeDebugMismatch: () => void;
  setDebugMismatchLoading: (loading: boolean) => void;
  setDebugMismatchError: (error: string | null) => void;

  openGenerateStub: (suggestions: Record<string, unknown>[], confidence: number) => void;
  closeGenerateStub: () => void;
  setGenerateStubLoading: (loading: boolean) => void;
  setGenerateStubError: (error: string | null) => void;
}

function getInitialTheme(): ThemeMode {
  try {
    const stored = globalThis.localStorage?.getItem('mockserver-theme');
    if (stored === 'dark' || stored === 'light') return stored;
  } catch {
    // localStorage may not be available in test/SSR environments
  }
  return 'dark';
}

const initialSearch = getInitialSearch();

export const useDashboardStore = create<DashboardState>()((set) => ({
  logMessages: [],
  activeExpectations: [],
  recordedRequests: [],
  proxiedRequests: [],

  // Restore the last-used view (URL hash wins over localStorage); a genuine
  // first visit with nothing persisted falls back to 'get-started'.
  view: getInitialView(),
  requestFilter: {},
  filterEnabled: false,
  filterExpanded: false,

  connectionStatus: 'disconnected',
  themeMode: getInitialTheme(),
  autoScroll: true,

  logSearch: initialSearch.logSearch,
  expectationSearch: initialSearch.expectationSearch,
  receivedSearch: initialSearch.receivedSearch,
  proxiedSearch: initialSearch.proxiedSearch,
  trafficSearch: initialSearch.trafficSearch,

  logShowForwarded: true,

  error: null,
  errorSource: null,
  notification: null,

  debugMismatchOpen: false,
  debugMismatchLoading: false,
  debugMismatchResult: null,
  debugMismatchError: null,

  generateStubOpen: false,
  generateStubLoading: false,
  generateStubSuggestions: [],
  generateStubConfidence: 0,
  generateStubError: null,

  selectedTrafficKey: null,

  pendingEditExpectation: null,
  pendingBreakpointPrefill: null,

  actionTypeFilter: [],
  llmProviderFilter: [],

  // Whitelist incoming filter values so a stale URL or serialised state cannot
  // poison the store with a value that silently matches nothing.
  setActionTypeFilter: (types) => set({
    actionTypeFilter: types.filter((t) => (ACTION_TYPES as readonly string[]).includes(t)),
  }),
  setLlmProviderFilter: (providers) => set({
    llmProviderFilter: providers.filter((p) => (LLM_PROVIDERS as readonly string[]).includes(p)),
  }),

  applyMessage: (message) =>
    // The view is never changed here: the user stays on whatever view they are
    // on (the initial view is 'get-started') until they navigate themselves.
    // We deliberately do NOT auto-advance to the dashboard when the first data
    // arrives — landing on Get Started should be sticky.
    set((s) => {
      // Reconcile only the arrays actually present in this push. An error-only
      // message (e.g. a filter that failed to deserialize server-side arrives as
      // `{"error": "..."}` with no data arrays) must NOT blank the four panels —
      // an absent field keeps the previous array untouched until the next
      // event-driven push replaces it.
      const next = {
        logMessages: message.logMessages !== undefined
          ? reconcileByKey(s.logMessages, message.logMessages, logMessagesCache)
          : s.logMessages,
        activeExpectations: message.activeExpectations !== undefined
          ? reconcileByKey(s.activeExpectations, message.activeExpectations, activeExpectationsCache)
          : s.activeExpectations,
        recordedRequests: message.recordedRequests !== undefined
          ? reconcileByKey(s.recordedRequests, message.recordedRequests, recordedRequestsCache)
          : s.recordedRequests,
        proxiedRequests: message.proxiedRequests !== undefined
          ? reconcileByKey(s.proxiedRequests, message.proxiedRequests, proxiedRequestsCache)
          : s.proxiedRequests,
      };
      if (message.error != null) {
        // A push carried an error — record that it came from a push so a later
        // clean push may clear it.
        return { ...next, error: message.error, errorSource: 'push' as const };
      }
      if (s.errorSource === 'push') {
        // The standing banner was itself set by a push; a clean push supersedes it.
        return { ...next, error: null, errorSource: null };
      }
      // A user-action / connection error (set via setError) survives the push —
      // it stays until dismissed or explicitly superseded.
      return next;
    }),

  clearUI: () => {
    logMessagesCache.clear();
    activeExpectationsCache.clear();
    recordedRequestsCache.clear();
    proxiedRequestsCache.clear();
    // A server reset returns the user to Get Started and discards all traffic;
    // persist that view AND clear persisted/in-memory search so a subsequent
    // reload doesn't restore the now-stale prior view or filters.
    persistView('get-started');
    persistSearch(EMPTY_SEARCH);
    set({
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
      selectedTrafficKey: null,
      pendingEditExpectation: null,
      pendingBreakpointPrefill: null,
      error: null,
      errorSource: null,
      notification: null,
      view: 'get-started' as ViewMode,

      logSearch: '',
      expectationSearch: '',
      receivedSearch: '',
      proxiedSearch: '',
      trafficSearch: '',

      debugMismatchOpen: false,
      debugMismatchLoading: false,
      debugMismatchResult: null,
      debugMismatchError: null,

      generateStubOpen: false,
      generateStubLoading: false,
      generateStubSuggestions: [],
      generateStubConfidence: 0,
      generateStubError: null,
    });
  },

  setView: (view) => {
    const resolved = VIEW_MIGRATION[view as string] ?? view;
    persistView(resolved);
    set({ view: resolved, selectedTrafficKey: null });
  },
  setRequestFilter: (filter) => set({ requestFilter: filter }),
  setFilterEnabled: (enabled) => set({ filterEnabled: enabled }),
  setFilterExpanded: (expanded) => set({ filterExpanded: expanded }),
  toggleFilterExpanded: () => set((s) => ({ filterExpanded: !s.filterExpanded })),
  setConnectionStatus: (status) => set({ connectionStatus: status }),
  setThemeMode: (mode) => {
    try { globalThis.localStorage?.setItem('mockserver-theme', mode); } catch { /* noop */ }
    set({ themeMode: mode });
  },
  toggleThemeMode: () =>
    set((s) => {
      const next = s.themeMode === 'dark' ? 'light' : 'dark';
      try { globalThis.localStorage?.setItem('mockserver-theme', next); } catch { /* noop */ }
      return { themeMode: next };
    }),
  setAutoScroll: (enabled) => set({ autoScroll: enabled }),
  toggleAutoScroll: () => set((s) => ({ autoScroll: !s.autoScroll })),
  // Each setter persists ONLY the five search fields (an explicit PersistedSearch
  // slice) — never `{ ...s }`, which would serialize the entire store (logs,
  // expectations, recorded/proxied requests) to localStorage on every keystroke.
  setLogSearch: (term) => set((s) => {
    persistSearch({ logSearch: term, expectationSearch: s.expectationSearch, receivedSearch: s.receivedSearch, proxiedSearch: s.proxiedSearch, trafficSearch: s.trafficSearch });
    return { logSearch: term };
  }),
  setLogShowForwarded: (show) => set({ logShowForwarded: show }),
  setExpectationSearch: (term) => set((s) => {
    persistSearch({ logSearch: s.logSearch, expectationSearch: term, receivedSearch: s.receivedSearch, proxiedSearch: s.proxiedSearch, trafficSearch: s.trafficSearch });
    return { expectationSearch: term };
  }),
  setReceivedSearch: (term) => set((s) => {
    persistSearch({ logSearch: s.logSearch, expectationSearch: s.expectationSearch, receivedSearch: term, proxiedSearch: s.proxiedSearch, trafficSearch: s.trafficSearch });
    return { receivedSearch: term };
  }),
  setProxiedSearch: (term) => set((s) => {
    persistSearch({ logSearch: s.logSearch, expectationSearch: s.expectationSearch, receivedSearch: s.receivedSearch, proxiedSearch: term, trafficSearch: s.trafficSearch });
    return { proxiedSearch: term };
  }),
  setTrafficSearch: (term) => set((s) => {
    persistSearch({ logSearch: s.logSearch, expectationSearch: s.expectationSearch, receivedSearch: s.receivedSearch, proxiedSearch: s.proxiedSearch, trafficSearch: term });
    return { trafficSearch: term };
  }),
  setSelectedTrafficKey: (key) => set({ selectedTrafficKey: key }),
  editExpectation: (expectation) => {
    persistView('composer');
    set({ pendingEditExpectation: expectation, view: 'composer' as ViewMode, selectedTrafficKey: null });
  },
  clearPendingEditExpectation: () => set({ pendingEditExpectation: null }),
  setBreakpointPrefill: (prefill) => {
    persistView('breakpoints');
    set({ pendingBreakpointPrefill: prefill, view: 'breakpoints' as ViewMode, selectedTrafficKey: null });
  },
  clearPendingBreakpointPrefill: () => set({ pendingBreakpointPrefill: null }),
  // A locally-set error (failed clear, parse failure, connection-lost notice) is
  // tagged 'client' so a subsequent data push cannot silently wipe it — it stays
  // until the user dismisses it or a later setError/push supersedes it.
  setError: (error) => set({ error, errorSource: error != null ? 'client' : null }),
  setNotification: (notification) => set({ notification }),
  openDebugMismatch: (result) =>
    set({ debugMismatchOpen: true, debugMismatchResult: result, debugMismatchLoading: false, debugMismatchError: null }),
  closeDebugMismatch: () =>
    set({ debugMismatchOpen: false, debugMismatchResult: null, debugMismatchLoading: false, debugMismatchError: null }),
  setDebugMismatchLoading: (loading) => set({ debugMismatchLoading: loading }),
  setDebugMismatchError: (error) =>
    set({ debugMismatchError: error, debugMismatchLoading: false }),

  openGenerateStub: (suggestions, confidence) =>
    set({ generateStubOpen: true, generateStubSuggestions: suggestions, generateStubConfidence: confidence, generateStubLoading: false, generateStubError: null }),
  closeGenerateStub: () =>
    set({ generateStubOpen: false, generateStubSuggestions: [], generateStubConfidence: 0, generateStubLoading: false, generateStubError: null }),
  setGenerateStubLoading: (loading) => set({ generateStubLoading: loading }),
  setGenerateStubError: (error) =>
    set({ generateStubError: error, generateStubLoading: false }),
}));
