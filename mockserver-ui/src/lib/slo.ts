/**
 * Client and types for MockServer's SLO verification endpoint
 * (`PUT /mockserver/verifySLO`).
 *
 * A {@link SloCriteria} is a named set of {@link SloObjective}s evaluated over an
 * already-elapsed {@link SloWindow} against the recorded forward/proxy traffic
 * samples. The server answers with an {@link SloVerdict}: an overall PASS / FAIL /
 * INCONCLUSIVE plus the observed-vs-threshold breakdown per objective. The wire
 * shape mirrors the core model (org.mockserver.slo.*): see the SloCriteriaSerializer.
 *
 * HTTP status mapping (per HttpState.handleVerifySlo):
 *   - 200 OK            → PASS or INCONCLUSIVE verdict (body is the verdict JSON)
 *   - 406 NOT_ACCEPTABLE → FAIL verdict (still carries the verdict JSON body)
 *   - 403 FORBIDDEN      → SLO tracking disabled (body is `{ "error": "..." }`)
 *   - 400 BAD_REQUEST    → malformed criteria (body is `{ "error": "..." }`)
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** The service-level indicator computed over the window. */
export type SloSli = 'LATENCY_P50' | 'LATENCY_P95' | 'LATENCY_P99' | 'ERROR_RATE';

/** How the observed indicator is compared against the threshold. */
export type SloComparator =
  | 'LESS_THAN'
  | 'LESS_THAN_OR_EQUAL'
  | 'GREATER_THAN'
  | 'GREATER_THAN_OR_EQUAL';

/** Which traffic an objective applies to. */
export type SloScope = 'FORWARD' | 'INBOUND';

/** The overall / per-objective outcome. */
export type SloResult = 'PASS' | 'FAIL' | 'INCONCLUSIVE';

/** A single objective: one indicator compared against a threshold. */
export interface SloObjective {
  sli: SloSli;
  comparator: SloComparator;
  threshold: number;
  scope?: SloScope;
}

/**
 * The window over which the criteria is evaluated. EXPLICIT uses an absolute
 * [from, to] epoch-millis range; LOOKBACK is a trailing window of `lookbackMillis`
 * ending at evaluation time.
 */
export interface SloWindow {
  type: 'EXPLICIT' | 'LOOKBACK';
  fromEpochMillis?: number;
  toEpochMillis?: number;
  lookbackMillis?: number;
}

/** The verify request body. */
export interface SloCriteria {
  name?: string;
  window: SloWindow;
  objectives: SloObjective[];
  minimumSampleCount?: number;
  upstreamHosts?: string[];
}

/** The evaluated outcome of one objective. observedValue is omitted when it could not be computed. */
export interface SloObjectiveResult {
  sli: SloSli;
  comparator: SloComparator;
  threshold: number;
  observedValue?: number;
  result: SloResult;
  detail?: string;
}

/** The verify response body. */
export interface SloVerdict {
  name?: string;
  result: SloResult;
  windowFromEpochMillis: number;
  windowToEpochMillis: number;
  sampleCount: number;
  objectiveResults: SloObjectiveResult[];
}

/**
 * A FAIL verdict comes back as a 406 (so a CI gate can assert on the status code
 * alone) but still carries the verdict JSON body. We treat both 200 and 406 as a
 * verdict response and only throw for genuine errors (400 / 5xx / non-JSON).
 */
async function readVerdictOrThrow(res: Response): Promise<SloVerdict> {
  if (res.ok || res.status === 406) {
    return res.json() as Promise<SloVerdict>;
  }
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string' && body.error.trim()) message = body.error;
  } catch {
    // non-JSON body — keep the status-line message
  }
  throw new Error(message);
}

/** Submit an SLO criteria and return the verdict. Throws on a 400 / 5xx error. */
export async function verifySlo(
  params: ConnectionParams,
  criteria: SloCriteria,
  signal?: AbortSignal,
): Promise<SloVerdict> {
  const base = buildBaseUrl(params);
  const res = await fetch(`${base}/mockserver/verifySLO`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(criteria),
    signal,
  });
  return readVerdictOrThrow(res);
}

/** Human-readable label for an SLI. */
export const SLI_LABELS: Record<SloSli, string> = {
  LATENCY_P50: 'Latency p50 (ms)',
  LATENCY_P95: 'Latency p95 (ms)',
  LATENCY_P99: 'Latency p99 (ms)',
  ERROR_RATE: 'Error rate (0–1)',
};

/** Human-readable symbol for a comparator. */
export const COMPARATOR_SYMBOLS: Record<SloComparator, string> = {
  LESS_THAN: '<',
  LESS_THAN_OR_EQUAL: '≤',
  GREATER_THAN: '>',
  GREATER_THAN_OR_EQUAL: '≥',
};
