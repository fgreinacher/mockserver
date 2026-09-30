/**
 * Anonymous, cookieless dashboard usage analytics.
 *
 * Privacy is the top priority. This module is *structurally* incapable of
 * sending user, request, or mock data: every public function only ever emits
 * one of a closed set of event names with a closed/whitelisted set of param
 * values. There is no code path that accepts a free-text string (URL, hostname,
 * header, body, file path, expectation JSON, or error message) and forwards it
 * to the analytics backend.
 *
 * Activation is gated behind a long list of opt-out signals (master switch,
 * blank endpoint/key, Do Not Track, Global Privacy Control, local opt-out,
 * offline, automation/headless, suppressed IDE telemetry). If ANY gate fails
 * the module becomes a permanent no-op for the lifetime of the page.
 *
 * posthog-js is loaded via a DYNAMIC import only AFTER all gates pass, so it is
 * a lazily-fetched first-party chunk that never loads unless analytics is
 * active. We never inject a remote `<script>`.
 *
 * Every exported function wraps its body in try/catch and swallows errors:
 * analytics must NEVER break the dashboard or throw into render.
 */
import type { Configuration } from './configuration';
import { useDashboardStore } from '../store';

// Injected by Vite `define` from the UI package.json version. Falls back to
// 'unknown' if the define is absent (e.g. in a bare test environment).
declare const __APP_VERSION__: string | undefined;

const OPT_OUT_KEY = 'mockserver.analytics.optOut';
const BANNER_DISMISSED_KEY = 'mockserver.analytics.bannerDismissed';

/** Closed set of feature events. Anything outside this set is dropped. */
export type Feature =
  | 'expectation_created'
  | 'openapi_imported'
  | 'chaos_started'
  | 'load_run_started'
  | 'breakpoint_set'
  | 'optimise_run'
  | 'export_performed';

const FEATURES: ReadonlySet<string> = new Set<Feature>([
  'expectation_created',
  'openapi_imported',
  'chaos_started',
  'load_run_started',
  'breakpoint_set',
  'optimise_run',
  'export_performed',
]);

/** Closed set of coarse error categories. NEVER a free-text message. */
export type ErrorCategory =
  | 'load_failed'
  | 'save_failed'
  | 'connection_failed'
  | 'validation_failed'
  | 'unknown';

const ERROR_CATEGORIES: ReadonlySet<string> = new Set<ErrorCategory>([
  'load_failed',
  'save_failed',
  'connection_failed',
  'validation_failed',
  'unknown',
]);

type FeatureMode = 'quick' | 'advanced';
const FEATURE_MODES: ReadonlySet<string> = new Set<FeatureMode>(['quick', 'advanced']);

/**
 * Closed set of MockServer artefacts ("distributions") the dashboard can be
 * served from. Reported once per session on `app_open` so we can tell which
 * artefact the data came from. Any value outside this set — including a blank
 * or absent config value — is normalised to `unknown`, so the privacy contract
 * (no free-text ever reaches the backend) holds even if the server sends an
 * unexpected string.
 */
export type Distribution =
  | 'docker-standard'
  | 'docker-graaljs'
  | 'docker-clustered'
  | 'docker-http3'
  | 'helm'
  | 'binary'
  | 'jar'
  | 'unknown';

const DISTRIBUTIONS: ReadonlySet<string> = new Set<Distribution>([
  'docker-standard',
  'docker-graaljs',
  'docker-clustered',
  'docker-http3',
  'helm',
  'binary',
  'jar',
  'unknown',
]);

/**
 * Normalise the server-supplied `dashboardAnalyticsDistribution` to a value in
 * the closed allow-list. Any other/empty/non-string value becomes `unknown`,
 * guaranteeing no free-text is ever forwarded (same shape as the Feature /
 * ErrorCategory guards).
 */
function normaliseDistribution(value: unknown): Distribution {
  if (typeof value === 'string' && DISTRIBUTIONS.has(value)) return value as Distribution;
  return 'unknown';
}

type Surface = 'browser' | 'ide-embedded';

// Minimal structural type for the subset of the posthog-js API we use, so this
// module needs no compile-time dependency on the (optional, lazily-loaded) package.
interface PostHogLike {
  init(key: string, options: Record<string, unknown>): void;
  capture(event: string, properties?: Record<string, unknown>): void;
  opt_out_capturing?: () => void;
}

// Module-level state. Once `decided` is true the activation decision is final
// for the lifetime of the page (the no-op outcome can never be reversed except
// by a fresh load), guaranteeing "fails silent / never fires when it shouldn't".
let decided = false;
let active = false;
let posthog: PostHogLike | null = null;

// Whitelist of valid view names, supplied by the caller (the store's ALL_VIEWS).
// trackView drops anything outside this set so the runtime guard matches the
// privacy contract (same shape as the Feature / ErrorCategory guards). Empty
// until initAnalytics runs.
let knownViews: ReadonlySet<string> = new Set<string>();

// The view that was current at activation time, captured so MINOR-1 can emit
// the initial `view_change` exactly once when activation completes (activation
// is async — the App's `[view]` effect has already fired-and-dropped by then).
let pendingInitialView: string | null = null;

// One-shot listeners fired when activation completes successfully. The consent
// banner subscribes so it can render reactively (activation is async, so a
// mount-time snapshot would always be `false`).
const activationListeners = new Set<() => void>();

/**
 * Subscribe to analytics activation. If analytics is already active the callback
 * fires immediately (next microtask). Returns an unsubscribe function. Used by
 * the consent banner to appear once activation completes.
 */
export function onAnalyticsActivated(listener: () => void): () => void {
  try {
    if (active) {
      // Already active — notify asynchronously so callers always observe
      // consistent (post-subscribe) ordering.
      queueMicrotask(() => {
        try {
          listener();
        } catch {
          /* swallow */
        }
      });
      return () => {};
    }
    activationListeners.add(listener);
    return () => {
      activationListeners.delete(listener);
    };
  } catch {
    return () => {};
  }
}

function notifyActivated(): void {
  for (const listener of [...activationListeners]) {
    try {
      listener();
    } catch {
      /* swallow */
    }
  }
  activationListeners.clear();
}

function appVersion(): string {
  try {
    if (typeof __APP_VERSION__ === 'string' && __APP_VERSION__.length > 0) return __APP_VERSION__;
  } catch {
    /* define absent */
  }
  return 'unknown';
}

function currentTheme(): string | undefined {
  try {
    const mode = useDashboardStore.getState().themeMode;
    return typeof mode === 'string' ? mode : undefined;
  } catch {
    return undefined;
  }
}

function detectSurface(): Surface {
  try {
    return typeof (window as unknown as { acquireVsCodeApi?: unknown }).acquireVsCodeApi !== 'undefined'
      ? 'ide-embedded'
      : 'browser';
  } catch {
    return 'browser';
  }
}

/** True if any Do Not Track / Global Privacy Control signal is set. */
function privacySignalSet(): boolean {
  try {
    const nav = navigator as unknown as {
      doNotTrack?: string | null;
      msDoNotTrack?: string | null;
      globalPrivacyControl?: boolean;
    };
    const win = window as unknown as { doNotTrack?: string | null };
    if (nav.doNotTrack === '1') return true;
    if (win.doNotTrack === '1') return true;
    if (nav.msDoNotTrack === '1') return true;
    if (nav.globalPrivacyControl === true) return true;
  } catch {
    /* environment without navigator/window — treat as no signal */
  }
  return false;
}

/** True only if every activation gate holds. */
function gatesPass(config: Configuration): boolean {
  // Gate 1: master kill switch.
  if (config['dashboardAnalyticsEnabled'] !== true) return false;

  // Gate 2: endpoint and key both present and non-empty.
  const endpoint = config['dashboardAnalyticsEndpoint'];
  const key = config['dashboardAnalyticsKey'];
  if (typeof endpoint !== 'string' || endpoint.trim() === '') return false;
  if (typeof key !== 'string' || key.trim() === '') return false;

  // Gates 3 & 4: Do Not Track / Global Privacy Control.
  if (privacySignalSet()) return false;

  // Gate 5: local opt-out.
  try {
    if (globalThis.localStorage?.getItem(OPT_OUT_KEY) === 'true') return false;
  } catch {
    /* storage unavailable — does not by itself disable analytics */
  }

  // Gate 6: online.
  try {
    if (navigator.onLine === false) return false;
  } catch {
    /* navigator.onLine unavailable — treat as online */
  }

  // Gate 7: not automation/headless.
  try {
    if ((navigator as unknown as { webdriver?: boolean }).webdriver === true) return false;
  } catch {
    /* navigator.webdriver unavailable */
  }

  // Gate 8: IDE telemetry suppressed by the embedding host.
  try {
    const inIde = typeof (window as unknown as { acquireVsCodeApi?: unknown }).acquireVsCodeApi !== 'undefined';
    const ideSuppressed =
      (window as unknown as { __MOCKSERVER_IDE_TELEMETRY_DISABLED__?: boolean }).__MOCKSERVER_IDE_TELEMETRY_DISABLED__ ===
      true;
    if (inIde && ideSuppressed) return false;
  } catch {
    /* not in an IDE webview */
  }

  return true;
}

/**
 * Options for {@link initAnalytics}.
 * @property validViews   The closed set of valid view names (the store's
 *                        ALL_VIEWS) used to whitelist `trackView`. trackView
 *                        drops any value outside this set.
 * @property initialView  The view that is current at startup. If supplied and
 *                        valid, it is emitted once as the first `view_change`
 *                        the moment activation completes, so the initial tab is
 *                        counted exactly once (without double-counting on the
 *                        App's subsequent `[view]` effect).
 */
export interface InitAnalyticsOptions {
  validViews?: readonly string[];
  initialView?: string;
}

/**
 * Run all activation gates once. If any fail, the module becomes a permanent
 * no-op. On success, dynamic-import posthog-js, init it cookielessly, emit the
 * `app_open` event, emit the initial `view_change`, and notify activation
 * listeners (e.g. the consent banner). Safe to call more than once (guarded
 * against double-init).
 */
export function initAnalytics(config: Configuration, options?: InitAnalyticsOptions): void {
  try {
    if (decided) return;
    decided = true;

    // Record the view whitelist + initial view regardless of gate outcome; they
    // are only ever consulted while active, so this is harmless when inactive.
    if (options?.validViews) knownViews = new Set<string>(options.validViews);
    pendingInitialView = options?.initialView ?? null;

    if (!gatesPass(config)) {
      active = false;
      return;
    }

    const endpoint = (config['dashboardAnalyticsEndpoint'] as string).trim();
    const key = (config['dashboardAnalyticsKey'] as string).trim();
    const surface = detectSurface();
    // Session-constant artefact identity, normalised to the closed allow-list
    // (never free-text). Emitted on `app_open` only.
    const distribution = normaliseDistribution(config['dashboardAnalyticsDistribution']);

    // Dynamic import: posthog-js becomes a separate lazy chunk that is never
    // fetched unless we reach this point (i.e. analytics is active).
    void import('posthog-js')
      .then((mod) => {
        try {
          const ph = (mod.default ?? mod) as unknown as PostHogLike;
          ph.init(key, {
            api_host: endpoint,
            persistence: 'memory', // no cookie, no localStorage identifier
            autocapture: false, // only our explicit closed event set
            capture_pageview: false, // SPA tab switches sent manually as view_change
            capture_pageleave: false,
            disable_session_recording: true,
            disable_surveys: true,
          });
          posthog = ph;
          active = true;
          ph.capture('app_open', {
            app_version: appVersion(),
            surface,
            distribution,
            ...(currentTheme() ? { theme: currentTheme() } : {}),
          });
          // MINOR-1: emit the initial view exactly once now that we are active.
          // The App's `[view]` effect already fired (and was dropped) while
          // inactive; a later navigation is what re-arms it, so without this the
          // first tab would never be counted. trackView de-dupes / whitelists.
          if (pendingInitialView !== null) {
            trackView(pendingInitialView);
            pendingInitialView = null;
          }
          notifyActivated();
        } catch {
          // Init failed — leave inactive.
          active = false;
          posthog = null;
        }
      })
      .catch(() => {
        // Import failed (offline / chunk missing) — leave inactive.
        active = false;
        posthog = null;
      });
  } catch {
    active = false;
  }
}

/**
 * Capture a SPA navigation. No-op unless active. Drops any value outside the
 * configured view whitelist (the store's ALL_VIEWS), so the runtime guard
 * matches the privacy contract — the same shape as trackFeature/trackError.
 */
export function trackView(view: string): void {
  try {
    if (!active || !posthog) return;
    if (typeof view !== 'string' || view === '') return;
    // If a whitelist was configured, enforce it. (It is always configured before
    // activation completes; the size>0 fallback only guards a misconfigured caller.)
    if (knownViews.size > 0 && !knownViews.has(view)) return;
    posthog.capture('view_change', { view });
  } catch {
    /* swallow */
  }
}

/** Capture a feature usage. Drops any value outside the closed Feature set. */
export function trackFeature(feature: Feature, params?: { mode?: FeatureMode }): void {
  try {
    if (!active || !posthog) return;
    if (!FEATURES.has(feature)) return; // runtime guard against non-enum values
    const properties: Record<string, unknown> = {};
    const mode = params?.mode;
    if (mode !== undefined && FEATURE_MODES.has(mode)) {
      properties.mode = mode;
    }
    posthog.capture('feature_used', { feature, ...properties });
  } catch {
    /* swallow */
  }
}

/** Capture a coarse error category. NEVER accepts/sends a free-text message. */
export function trackError(category: ErrorCategory): void {
  try {
    if (!active || !posthog) return;
    if (!ERROR_CATEGORIES.has(category)) return; // runtime guard
    posthog.capture('error_shown', { category });
  } catch {
    /* swallow */
  }
}

/**
 * Persist the local opt-out flag. When opting out, also stop any already-running
 * posthog capture so no further events are sent this session.
 */
export function setAnalyticsOptOut(optOut: boolean): void {
  try {
    if (optOut) {
      try {
        globalThis.localStorage?.setItem(OPT_OUT_KEY, 'true');
      } catch {
        /* storage unavailable */
      }
      active = false;
      try {
        posthog?.opt_out_capturing?.();
      } catch {
        /* swallow */
      }
    } else {
      try {
        globalThis.localStorage?.removeItem(OPT_OUT_KEY);
      } catch {
        /* storage unavailable */
      }
    }
  } catch {
    /* swallow */
  }
}

/** Whether analytics is currently active (used to decide whether to show the banner). */
export function isAnalyticsActive(): boolean {
  try {
    return active;
  } catch {
    return false;
  }
}

/** Whether the disclosure banner has already been dismissed/opted-out. */
export function isBannerDismissed(): boolean {
  try {
    if (globalThis.localStorage?.getItem(BANNER_DISMISSED_KEY) === 'true') return true;
    if (globalThis.localStorage?.getItem(OPT_OUT_KEY) === 'true') return true;
  } catch {
    /* storage unavailable */
  }
  return false;
}

/** Persist the "banner seen" flag so the disclosure does not reappear. */
export function dismissBanner(): void {
  try {
    globalThis.localStorage?.setItem(BANNER_DISMISSED_KEY, 'true');
  } catch {
    /* storage unavailable */
  }
}

/**
 * TEST-ONLY: reset the module's one-shot activation decision so unit tests can
 * exercise `initAnalytics` repeatedly. Not used by production code.
 */
export function __resetAnalyticsForTests(): void {
  decided = false;
  active = false;
  posthog = null;
  knownViews = new Set<string>();
  pendingInitialView = null;
  activationListeners.clear();
}
