/**
 * Client for MockServer's "promote recordings to mocks" endpoint
 * (`PUT /mockserver/recordings/promote`).
 *
 * The server retrieves the recorded (forwarded/proxied) exchanges matching an
 * optional request-matcher filter, redacts secrets, consolidates/parameterizes
 * them into reusable mocks (unlimited times), and ACTIVATES them (adds them to
 * the active expectation set). It responds `201 Created` with a JSON array of
 * the activated {@link https://www.mock-server.com Expectation} objects (each
 * carrying its assigned id), so an operator can "record then mock" in one step.
 *
 * See HttpState.promoteRecordings (mockserver-core), the shared implementation
 * behind this endpoint and the `promote_recordings` MCP tool.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/**
 * Optional request-matcher filter, narrowing which recorded exchanges are
 * promoted. When neither field is set, all recorded traffic is promoted. The
 * fields map onto a MockServer request matcher (method / path support the usual
 * regex matching the control plane applies to a `RequestDefinition`).
 */
export interface PromoteRecordingsFilter {
  method?: string;
  path?: string;
}

/**
 * Server-side promotion options. All default to the server's own defaults (on),
 * so an omitted field sends no query parameter and inherits the default.
 */
export interface PromoteRecordingsOptions {
  /** Collapse duplicate exchanges into consolidated mocks (server default: on). */
  consolidate?: boolean;
  /** Generalise volatile path/query/header/body values (server default: on). */
  parameterize?: boolean;
  /** Redact captured secrets before promoting (server default: on). */
  redactSensitiveData?: boolean;
}

/** Outcome of a promote run. */
export interface PromoteRecordingsResult {
  /** The activated expectations returned by the server (with assigned ids). */
  expectations: Array<Record<string, unknown>>;
  /** Convenience count of activated expectations. */
  count: number;
}

/**
 * Throw a humanizable error for a non-OK response. The promote endpoint returns
 * a plain-text body carrying the failure message (not a JSON `{ "error": ... }`
 * envelope), so surface it in the `MockServer returned <status>: <body>` shape
 * that {@link humanizeError} recognises and maps to an actionable message.
 */
async function ensureOk(res: Response): Promise<void> {
  if (res.ok) return;
  let body = '';
  try {
    body = await res.text();
  } catch {
    // no body — keep it empty; humanizeServerError still maps by status
  }
  throw new Error(`MockServer returned ${res.status}: ${body}`);
}

/**
 * Build the request-matcher body from the filter. Returns `undefined` when no
 * field is set so the caller can omit the body entirely (which promotes all
 * recorded traffic server-side).
 */
function buildFilterBody(filter?: PromoteRecordingsFilter): Record<string, unknown> | undefined {
  if (!filter) return undefined;
  const body: Record<string, unknown> = {};
  const method = filter.method?.trim();
  const path = filter.path?.trim();
  if (method) body['method'] = method;
  if (path) body['path'] = path;
  return Object.keys(body).length > 0 ? body : undefined;
}

/**
 * Promote recorded (forwarded/proxied) traffic into active mock expectations.
 * Throws on any non-2xx response so callers can surface the failure via
 * {@link humanizeError}.
 */
export async function promoteRecordings(
  params: ConnectionParams,
  filter?: PromoteRecordingsFilter,
  options?: PromoteRecordingsOptions,
  signal?: AbortSignal,
): Promise<PromoteRecordingsResult> {
  const base = buildBaseUrl(params);

  // Only the server's "off" query values are meaningful — the server treats any
  // value other than "false" as the default (on) — so append a parameter only
  // when the caller explicitly disables it, keeping the URL clean.
  const query = new URLSearchParams();
  if (options?.consolidate === false) query.set('consolidate', 'false');
  if (options?.parameterize === false) query.set('parameterize', 'false');
  if (options?.redactSensitiveData === false) query.set('redactSensitiveData', 'false');
  const qs = query.toString();

  const filterBody = buildFilterBody(filter);
  const res = await fetch(`${base}/mockserver/recordings/promote${qs ? `?${qs}` : ''}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    // Omit the body (rather than send `{}`) when there is no filter, so the
    // server promotes all recorded traffic instead of matching an empty request.
    ...(filterBody ? { body: JSON.stringify(filterBody) } : {}),
    signal,
  });
  await ensureOk(res);

  const parsed = (await res.json().catch(() => [])) as unknown;
  const expectations = Array.isArray(parsed) ? (parsed as Array<Record<string, unknown>>) : [];
  return { expectations, count: expectations.length };
}
