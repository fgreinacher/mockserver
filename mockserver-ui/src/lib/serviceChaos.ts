/**
 * Client for MockServer's service-scoped chaos control-plane endpoint
 * (`/mockserver/serviceChaos`). Register one HTTP chaos profile per upstream
 * host and have it applied to every matched forward to that host, with an
 * optional time-to-live (auto-revert). See the Chaos Testing docs.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface DelayDTO {
  timeUnit?: string;
  value?: number;
}

/** Mirror of the server's HttpChaosProfileDTO (all fields optional). */
export interface HttpChaosProfileDTO {
  errorStatus?: number;
  retryAfter?: string;
  errorProbability?: number;
  dropConnectionProbability?: number;
  latency?: DelayDTO;
  seed?: number;
  succeedFirst?: number;
  failRequestCount?: number;
  outageAfterMillis?: number;
  outageDurationMillis?: number;
  truncateBodyAtFraction?: number;
  malformedBody?: boolean;
  slowResponseChunkSize?: number;
  slowResponseChunkDelay?: DelayDTO;
  quotaName?: string;
  quotaLimit?: number;
  quotaWindowMillis?: number;
  quotaErrorStatus?: number;
  degradationRampMillis?: number;
  graphqlErrors?: boolean;
  graphqlErrorMessage?: string;
  graphqlErrorCode?: string;
  graphqlNullifyData?: boolean;
}

export interface ServiceChaosResponse {
  services: Record<string, HttpChaosProfileDTO>;
  /** Present only for TTL-bearing registrations: host → remaining ms. */
  ttlRemainingMillis?: Record<string, number>;
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/serviceChaos`;
}

async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  // the server returns {"error": "..."} on a 4xx
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body — keep the status-line message
  }
  throw new Error(message);
}

/** Fetch the current service-scoped chaos registrations and TTL countdowns. */
export async function fetchServiceChaos(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<ServiceChaosResponse> {
  const res = await fetch(endpoint(params), { signal });
  await ensureOk(res);
  const body = (await res.json()) as Partial<ServiceChaosResponse>;
  return { services: body.services ?? {}, ttlRemainingMillis: body.ttlRemainingMillis };
}

/** Register (or replace) the chaos profile for a host, optionally with a TTL (ms). */
export async function registerServiceChaos(
  params: ConnectionParams,
  host: string,
  chaos: HttpChaosProfileDTO,
  ttlMillis?: number,
): Promise<void> {
  const payload: Record<string, unknown> = { host, chaos };
  if (ttlMillis != null && ttlMillis > 0) payload.ttlMillis = ttlMillis;
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  await ensureOk(res);
}

/** Remove the chaos profile registered for a single host. */
export async function removeServiceChaos(params: ConnectionParams, host: string): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ host, remove: true }),
  });
  await ensureOk(res);
}

/** Patch (partially update) the chaos profile for a host. */
export async function patchServiceChaos(
  params: ConnectionParams,
  host: string,
  partial: Partial<HttpChaosProfileDTO>,
): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ host, chaos: partial }),
  });
  await ensureOk(res);
}

/** Clear all service-scoped chaos registrations. */
export async function clearServiceChaos(params: ConnectionParams): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clear: true }),
  });
  await ensureOk(res);
}

function pct(value: number): string {
  return `${Math.round(value * 100)}%`;
}

function delayMillis(delay: DelayDTO | undefined): number | undefined {
  if (!delay || delay.value == null) return undefined;
  const unit = (delay.timeUnit ?? 'MILLISECONDS').toUpperCase();
  switch (unit) {
    case 'DAYS':
      return delay.value * 86_400_000;
    case 'HOURS':
      return delay.value * 3_600_000;
    case 'MINUTES':
      return delay.value * 60_000;
    case 'SECONDS':
      return delay.value * 1000;
    case 'MICROSECONDS':
      return delay.value / 1000;
    case 'NANOSECONDS':
      return delay.value / 1_000_000;
    default:
      return delay.value; // MILLISECONDS
  }
}

/**
 * Human-readable summary chips for a chaos profile — one short phrase per
 * populated facet, in a stable order. Empty when nothing is configured.
 */
export function summarizeChaosProfile(profile: HttpChaosProfileDTO): string[] {
  const parts: string[] = [];
  // The server only injects an error when errorStatus is set; errorProbability
  // alone is a no-op (HttpActionHandler.chaosErrorResponseOrNull returns null
  // when errorStatus == null). So the error facet keys on errorStatus, and the
  // probability is shown only as a modifier of that status.
  if (profile.errorStatus != null) {
    const prob = profile.errorProbability != null ? ` @ ${pct(profile.errorProbability)}` : '';
    const retry = profile.retryAfter ? ` retry-after=${profile.retryAfter}` : '';
    parts.push(`error ${profile.errorStatus}${prob}${retry}`);
  }
  if (profile.dropConnectionProbability != null) {
    parts.push(`drop @ ${pct(profile.dropConnectionProbability)}`);
  }
  const latencyMs = delayMillis(profile.latency);
  if (latencyMs != null) {
    parts.push(`+${Math.round(latencyMs)}ms latency`);
  }
  if (profile.truncateBodyAtFraction != null) {
    parts.push(`truncate to ${pct(profile.truncateBodyAtFraction)}`);
  }
  if (profile.malformedBody) {
    parts.push('malformed body');
  }
  if (profile.slowResponseChunkSize != null) {
    parts.push('slow response');
  }
  if (profile.quotaName != null || profile.quotaLimit != null) {
    const limit = profile.quotaLimit != null ? ` ${profile.quotaLimit}` : '';
    const window = profile.quotaWindowMillis != null ? `/${profile.quotaWindowMillis}ms` : '';
    parts.push(`quota${limit}${window}`.trim());
  }
  if (profile.succeedFirst != null || profile.failRequestCount != null) {
    const succeed = profile.succeedFirst != null ? `succeed first ${profile.succeedFirst}` : null;
    const fail = profile.failRequestCount != null ? `fail ${profile.failRequestCount}` : null;
    parts.push([succeed, fail].filter(Boolean).join(', '));
  }
  if (profile.degradationRampMillis != null) {
    parts.push(`ramp over ${profile.degradationRampMillis}ms`);
  }
  if (profile.outageAfterMillis != null || profile.outageDurationMillis != null) {
    parts.push('outage window');
  }
  if (profile.seed != null) {
    parts.push(`seed ${profile.seed}`);
  }
  if (profile.graphqlErrors) {
    const code = profile.graphqlErrorCode ? ` (${profile.graphqlErrorCode})` : '';
    parts.push(`GraphQL error${code}`);
  }
  if (profile.graphqlErrors && profile.graphqlNullifyData) {
    parts.push('nullify data');
  }
  return parts;
}

// --- Quick Chaos (approachability layer) -----------------------------------
//
// "Quick Chaos" is a one-toggle entry point over the same per-host service-chaos
// rules the full HTTP card manages — no new server capability. It maps a small set
// of canned fault modes to REAL fields the server already supports, scoped by the
// server's genuine per-request probability (errorProbability / dropConnectionProbability).
//
// IMPORTANT SCOPING NOTES (verified against the server):
//   * Service chaos is keyed by upstream HOST (ServiceChaosRegistry) — there is no
//     global/all-traffic scope, so Quick Chaos targets one host like every other rule.
//   * errorProbability / dropConnectionProbability ARE true per-request probabilities
//     (ChaosProbability.shouldInject draws ThreadLocalRandom when no seed is set), so
//     "affecting X% of requests" is honest for the errors/reset modes. We deliberately
//     do NOT set `seed`: a fixed seed makes shouldInject a single reproducible draw
//     (all-or-nothing), which would break the percentage.
//   * Latency is NOT probability-gated server-side — it applies to every matched
//     request — so the latency mode is labelled "all requests", not X%.
//
// A Quick-Chaos rule carries no hidden state: it is a normal service-chaos rule and
// shows in the HTTP list. It is recognised ("tagged") purely by its canonical SHAPE —
// only the fields below, at canonical values — so the strip can round-trip its own
// state from the polled rules on load.

/** Fixed latency (ms) applied by the Quick Chaos "latency" mode. */
export const QUICK_CHAOS_LATENCY_MS = 3000;
/** Default percentage for the Quick Chaos slider. */
export const QUICK_CHAOS_DEFAULT_PERCENT = 10;

/** The canned Quick Chaos fault modes, mapped to real server fault fields. */
export type QuickChaosMode = 'errors' | 'reset' | 'latency';
export const QUICK_CHAOS_MODES: QuickChaosMode[] = ['errors', 'reset', 'latency'];

export interface QuickChaosState {
  host: string;
  percent: number;
  modes: QuickChaosMode[];
}

/**
 * Build the canonical service-chaos profile for a Quick Chaos selection. Percent is
 * an integer 1–100; it drives the true per-request probability of the errors/reset
 * modes. The latency mode is a fixed +{@link QUICK_CHAOS_LATENCY_MS}ms (all requests).
 */
export function buildQuickChaosProfile(modes: QuickChaosMode[], percent: number): HttpChaosProfileDTO {
  const probability = Math.min(1, Math.max(0.01, percent / 100));
  const profile: HttpChaosProfileDTO = {};
  if (modes.includes('errors')) {
    profile.errorStatus = 500;
    profile.errorProbability = probability;
  }
  if (modes.includes('reset')) {
    profile.dropConnectionProbability = probability;
  }
  if (modes.includes('latency')) {
    profile.latency = { timeUnit: 'MILLISECONDS', value: QUICK_CHAOS_LATENCY_MS };
  }
  return profile;
}

/** The profile keys Quick Chaos is allowed to set; any other populated key disqualifies. */
const QUICK_CHAOS_KEYS: ReadonlySet<string> = new Set([
  'errorStatus',
  'errorProbability',
  'dropConnectionProbability',
  'latency',
]);

function isProbability(value: number | undefined): value is number {
  return value != null && value > 0 && value <= 1;
}

/**
 * True when a profile matches the canonical Quick Chaos shape — i.e. it could have
 * been produced by {@link buildQuickChaosProfile}. This is the "tag" used to recognise
 * and adopt a Quick-Chaos rule from the polled registry (there is no name/id field on a
 * profile, so the shape is the identity). A hand-authored rule that happens to match is
 * treated as Quick Chaos too, which is safe — the strip simply surfaces and manages it.
 */
export function isQuickChaosProfile(profile: HttpChaosProfileDTO | undefined): boolean {
  if (!profile) return false;
  // Reject if any non-Quick-Chaos facet is populated.
  for (const [key, value] of Object.entries(profile)) {
    if (value != null && value !== false && !QUICK_CHAOS_KEYS.has(key)) return false;
  }
  const hasErrors = profile.errorStatus != null;
  const hasReset = profile.dropConnectionProbability != null;
  const hasLatency = profile.latency?.value != null;
  if (!hasErrors && !hasReset && !hasLatency) return false;
  // errors mode is canonical only as 500 + a real probability.
  if (hasErrors && !(profile.errorStatus === 500 && isProbability(profile.errorProbability))) return false;
  // errorProbability without an errorStatus is never canonical.
  if (!hasErrors && profile.errorProbability != null) return false;
  if (hasReset && !isProbability(profile.dropConnectionProbability)) return false;
  if (hasLatency && profile.latency?.value !== QUICK_CHAOS_LATENCY_MS) return false;
  return true;
}

/** The Quick Chaos modes present in a canonical profile (order: errors, reset, latency). */
export function quickChaosModesOf(profile: HttpChaosProfileDTO): QuickChaosMode[] {
  const modes: QuickChaosMode[] = [];
  if (profile.errorStatus != null) modes.push('errors');
  if (profile.dropConnectionProbability != null) modes.push('reset');
  if (profile.latency?.value != null) modes.push('latency');
  return modes;
}

/**
 * The percentage encoded by a canonical profile's per-request probability, preferring the
 * errors probability, then reset. Returns null for a latency-only profile (latency is not
 * probability-scoped), so the caller can fall back to the current slider value.
 */
export function quickChaosPercentOf(profile: HttpChaosProfileDTO): number | null {
  const probability = profile.errorProbability ?? profile.dropConnectionProbability;
  return probability != null ? Math.round(probability * 100) : null;
}

/**
 * Derive the current Quick Chaos state from the polled per-host registry, or null when no
 * host carries a Quick-Chaos-shaped rule. When several match, the first host in ascending
 * order wins (deterministic).
 */
export function deriveQuickChaos(services: Record<string, HttpChaosProfileDTO>): QuickChaosState | null {
  for (const host of Object.keys(services).sort()) {
    const profile = services[host];
    if (profile && isQuickChaosProfile(profile)) {
      return {
        host,
        modes: quickChaosModesOf(profile),
        percent: quickChaosPercentOf(profile) ?? QUICK_CHAOS_DEFAULT_PERCENT,
      };
    }
  }
  return null;
}

/** Format a remaining-TTL in ms as a compact countdown (e.g. "1m 05s", "12s"). */
export function formatTtl(remainingMillis: number): string {
  const totalSeconds = Math.max(0, Math.round(remainingMillis / 1000));
  if (totalSeconds < 60) return `${totalSeconds}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${String(minutes % 60).padStart(2, '0')}m`;
}
