/**
 * Client for MockServer's Pact endpoints:
 *   PUT /mockserver/pact        — export active response expectations as a Pact v3 contract.
 *   PUT /mockserver/pact/verify — verify a Pact contract against the registered expectations.
 *   PUT /mockserver/pact/import — import a Pact v3 contract as expectations.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface PactVerifyResult {
  /** true when every interaction in the contract is satisfied (server returned 202). */
  verified: boolean;
  /** the server's verification report JSON (matched/unmatched interactions). */
  result: unknown;
}

/**
 * Verify a Pact contract against the registered expectations.
 * The server returns 202 when verified and 406 when not — both carry a JSON report.
 */
export async function verifyPact(params: ConnectionParams, contractJson: string): Promise<PactVerifyResult> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/pact/verify`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: contractJson,
  });
  if (res.status === 202 || res.status === 406) {
    return { verified: res.status === 202, result: await res.json().catch(() => ({})) };
  }
  throw new Error((await res.text()) || `HTTP ${res.status} ${res.statusText}`);
}

/**
 * Import a Pact v3 contract as expectations.
 *
 * The server registers each interaction as an expectation and returns the created
 * expectations (201). Throws in the `MockServer returned <status>: <body>` shape on any
 * non-2xx so {@code humanizeError} can surface the server's message.
 *
 * @param contractJson the Pact v3 contract JSON document
 * @returns the array of created / upserted expectations
 */
export async function importPact(params: ConnectionParams, contractJson: string): Promise<unknown[]> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/pact/import`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: contractJson,
  });
  if (!res.ok) {
    throw new Error(`MockServer returned ${res.status}: ${await res.text().catch(() => '')}`);
  }
  const body = (await res.json().catch(() => [])) as unknown;
  return Array.isArray(body) ? body : [];
}

/**
 * Export the active response expectations as a Pact v3 consumer contract.
 *
 * @param consumer optional consumer name (server defaults to "consumer")
 * @param provider optional provider name (server defaults to "provider")
 * @returns the parsed Pact contract JSON
 */
export async function exportPact(
  params: ConnectionParams,
  consumer: string,
  provider: string,
): Promise<unknown> {
  const query = new URLSearchParams();
  if (consumer.trim()) query.set('consumer', consumer.trim());
  if (provider.trim()) query.set('provider', provider.trim());
  const suffix = query.toString() ? `?${query.toString()}` : '';
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/pact${suffix}`, {
    method: 'PUT',
  });
  if (!res.ok) {
    throw new Error((await res.text()) || `HTTP ${res.status} ${res.statusText}`);
  }
  return res.json() as Promise<unknown>;
}
