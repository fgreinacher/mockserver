/**
 * Client for MockServer's control-plane audit trail (`GET /mockserver/audit`).
 * Returns the most recent control-plane mutations (expectation/config/clear/etc.)
 * recorded by the server's AuditStore — a bounded, newest-first ring of entries.
 * No request headers or bodies are ever stored, only the mutation metadata below.
 * The endpoint returns a bare JSON array (newest first); an optional `?limit=<n>`
 * caps the number of entries (server default 200, hard cap 1000).
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** A single control-plane audit entry (one mutation of server state). */
export interface AuditEntry {
  /** Milliseconds since the Unix epoch when the mutation was recorded. */
  epochTimeMs: number;
  /** HTTP method of the control-plane request (e.g. PUT, DELETE). */
  method: string;
  /** Control-plane request path (e.g. /mockserver/expectation). */
  path: string;
  /** Logical operation name (e.g. expectation, clear, reset, configuration). */
  operation: string;
  /** Source network address of the caller. */
  sourceAddress: string;
  /** Authenticated principal, when control-plane auth is configured (may be null). */
  principal?: string | null;
  /** How the principal was authenticated (e.g. BASIC, JWT, mTLS; may be null). */
  principalSource?: string | null;
  /** Authorization outcome (e.g. AUTHORIZED, FORBIDDEN, UNAUTHENTICATED). */
  outcome: string;
  /** Short human-readable summary of the mutation. */
  summary: string;
}

async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let message = `HTTP ${res.status} ${res.statusText}`;
  try {
    const body = (await res.json()) as { error?: unknown };
    if (body && typeof body.error === 'string') message = body.error;
  } catch {
    // non-JSON body — keep the status-line message
  }
  throw new Error(message);
}

/**
 * Fetch the recent control-plane audit entries (newest first). `limit`, when
 * supplied, is passed through as `?limit=<n>` (server caps it at 1000).
 */
export async function fetchAuditEntries(
  params: ConnectionParams,
  options: { limit?: number; signal?: AbortSignal } = {},
): Promise<AuditEntry[]> {
  const { limit, signal } = options;
  const query = limit != null ? `?limit=${encodeURIComponent(String(limit))}` : '';
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/audit${query}`, { signal });
  await ensureOk(res);
  const data = (await res.json()) as unknown;
  return Array.isArray(data) ? (data as AuditEntry[]) : [];
}
