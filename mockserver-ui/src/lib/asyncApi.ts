/**
 * Client for MockServer's AsyncAPI broker-mocking control plane:
 *   PUT /mockserver/asyncapi       — load an AsyncAPI spec (JSON/YAML) or { spec, brokerConfig }.
 *   PUT /mockserver/asyncapi/http  — generate HTTP expectations from an AsyncAPI spec.
 *   GET /mockserver/asyncapi       — current status (loaded channels / operations).
 * All return 501 when the optional mockserver-async module is not on the server classpath.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export class AsyncApiUnavailableError extends Error {
  constructor() {
    super('AsyncAPI messaging module is not available — the server does not have mockserver-async on its classpath.');
    this.name = 'AsyncApiUnavailableError';
  }
}

async function jsonOrError(res: Response): Promise<Record<string, unknown>> {
  if (res.status === 501) throw new AsyncApiUnavailableError();
  const body = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  if (!res.ok) {
    throw new Error(typeof body.error === 'string' ? body.error : `HTTP ${res.status} ${res.statusText}`);
  }
  return body;
}

/** Load an AsyncAPI spec (raw JSON/YAML, or a { spec, brokerConfig } JSON object). */
export async function loadAsyncApi(params: ConnectionParams, specBody: string): Promise<Record<string, unknown>> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/asyncapi`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: specBody,
  });
  return jsonOrError(res);
}

/**
 * Generate HTTP expectations from an AsyncAPI spec (sibling of {@link loadAsyncApi}). The server
 * translates the spec's channels/operations into HTTP expectations, registers them, and returns
 * the created expectations as a JSON array (201). Throws {@link AsyncApiUnavailableError} when the
 * module is absent (501), or an Error carrying the server's `{error}` message on a 4xx.
 */
export async function generateHttpExpectations(params: ConnectionParams, specBody: string): Promise<unknown[]> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/asyncapi/http`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: specBody,
  });
  if (res.status === 501) throw new AsyncApiUnavailableError();
  const body = (await res.json().catch(() => null)) as unknown;
  if (!res.ok) {
    const err = body && typeof body === 'object' ? (body as Record<string, unknown>).error : undefined;
    throw new Error(typeof err === 'string' ? err : `HTTP ${res.status} ${res.statusText}`);
  }
  return Array.isArray(body) ? body : [];
}

/** Current AsyncAPI broker-mock status. Returns null when the module is unavailable (501). */
export async function getAsyncApiStatus(params: ConnectionParams, signal?: AbortSignal): Promise<Record<string, unknown> | null> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/asyncapi`, { signal });
  if (res.status === 501) return null;
  return jsonOrError(res);
}

export interface AsyncApiVerifyResult {
  /** true when the observed messages satisfy the verification (server returned 202). */
  verified: boolean;
  /** the server's failure message when not verified (empty when verified). */
  message: string;
}

/**
 * Verify observed broker messages against a verification request. The server returns 202 (verified,
 * empty body) or 406 (not verified, a plain-text failure message). Throws AsyncApiUnavailableError
 * when the module is absent (501).
 */
export async function verifyAsyncApi(params: ConnectionParams, body: string): Promise<AsyncApiVerifyResult> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/asyncapi/verify`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body,
  });
  if (res.status === 501) throw new AsyncApiUnavailableError();
  if (res.status === 202) return { verified: true, message: '' };
  if (res.status === 406) return { verified: false, message: await res.text() };
  throw new Error((await res.text()) || `HTTP ${res.status} ${res.statusText}`);
}
