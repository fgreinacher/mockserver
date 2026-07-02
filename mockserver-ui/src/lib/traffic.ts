/**
 * Client for clearing individual captured requests from MockServer's event log.
 *
 * MockServer has no "delete request by id" endpoint, but the same clear endpoint
 * used for "clear all logs" accepts a request matcher in the body and removes
 * only the log entries (recorded/proxied requests) that match it:
 *
 *   PUT /mockserver/clear?type=log
 *   Content-Type: application/json
 *   { "method": "GET", "path": "/api/users", ... }
 *
 * See HttpState.clear (mockserver-core) → MockServerEventLog.clear(requestDefinition),
 * which builds a matcher from the body and removes every matching log entry.
 *
 * Because the match is by request shape (not a unique id), clearing a request
 * also removes any identical requests captured alongside it — an inherent limit
 * of the server API, surfaced to the user in the bulk-clear confirmation.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/**
 * Throw a descriptive Error on a non-2xx response, preferring the server's
 * `{ "error": "..." }` envelope when present (matching expectations.ts /
 * serviceChaos.ts / drift.ts and what humanizeError expects to parse).
 */
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
 * The request definition to match on when clearing a captured request. Recorded
 * and proxied request rows wrap the request under `httpRequest`; fall back to
 * the whole value for any row that does not (so the matcher is always as
 * specific as the data allows).
 */
export function requestDefinitionOf(value: Record<string, unknown>): Record<string, unknown> {
  const req = value['httpRequest'];
  if (req && typeof req === 'object' && !Array.isArray(req)) {
    return req as Record<string, unknown>;
  }
  return value;
}

/**
 * Clear the log entries matching a single captured request. Throws on any
 * non-2xx response so callers can surface the failure via humanizeError.
 */
export async function clearLoggedRequest(
  params: ConnectionParams,
  requestDefinition: Record<string, unknown>,
): Promise<void> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/clear?type=log`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(requestDefinition),
  });
  await ensureOk(res);
}
