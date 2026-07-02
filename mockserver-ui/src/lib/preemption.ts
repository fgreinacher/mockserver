/**
 * Client for MockServer's preemption-simulation control-plane endpoint
 * (`/mockserver/preemption`). Simulate a Kubernetes node drain / Spot
 * reclamation / pre-SIGTERM sequence: the server cordons itself (turning away
 * new exchanges with 503 and/or signalling HTTP/2 clients to drain via GOAWAY),
 * lets in-flight requests drain for a bounded window, and auto-uncordons after
 * an optional TTL (a dead-man's switch). It is a simulation only — it never
 * stops the JVM or event loops.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/**
 * Rejection + GOAWAY strategy while cordoned (mirror of the server's
 * `PreemptionRequest.Mode`).
 * - `reject503` — reject new exchanges with 503 + Retry-After + Connection: close.
 * - `goaway` — emit an HTTP/2 GOAWAY so clients stop opening streams (no 503).
 * - `both` — reject with 503 and emit GOAWAY on HTTP/2 (default).
 */
export type PreemptionMode = 'reject503' | 'goaway' | 'both';

/** Live cordon state reported by GET /mockserver/preemption. */
export type PreemptionState = 'inactive' | 'draining' | 'drained';

/** Status snapshot returned by GET /mockserver/preemption. */
export interface PreemptionStatusDTO {
  state: PreemptionState;
  inFlight: number;
  drainRemainingMillis: number;
  /** Present only while a simulation is active. */
  mode?: PreemptionMode;
}

/** The preemption request sent to PUT /mockserver/preemption (all fields optional). */
export interface PreemptionRequestDTO {
  mode?: PreemptionMode;
  drainMillis?: number;
  ttlMillis?: number;
  lastStreamId?: number;
}

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/preemption`;
}

async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body -- keep the status-line message
  }
  throw new Error(message);
}

/** Fetch the current preemption state (GET). */
export async function getPreemption(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<PreemptionStatusDTO> {
  const res = await fetch(endpoint(params), { signal });
  await ensureOk(res);
  const body = (await res.json()) as Partial<PreemptionStatusDTO>;
  return {
    state: body.state ?? 'inactive',
    inFlight: body.inFlight ?? 0,
    drainRemainingMillis: body.drainRemainingMillis ?? 0,
    mode: body.mode,
  };
}

/** Start (or replace) a preemption simulation (PUT). */
export async function startPreemption(
  params: ConnectionParams,
  request: PreemptionRequestDTO,
): Promise<void> {
  const res = await fetch(endpoint(params), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  await ensureOk(res);
}

/** Uncordon immediately, clearing the preemption simulation (DELETE). Idempotent. */
export async function clearPreemption(params: ConnectionParams): Promise<void> {
  const res = await fetch(endpoint(params), { method: 'DELETE' });
  await ensureOk(res);
}
