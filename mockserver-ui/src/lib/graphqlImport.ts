/**
 * Client for MockServer's GraphQL import endpoint (`PUT /mockserver/graphql`).
 * Converts a GraphQL SDL schema document into expectations and returns the
 * created expectations.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/**
 * Import a GraphQL SDL schema. Returns the list of created expectations (as the
 * server's expectation JSON objects).
 *
 * The SDL document is sent verbatim as the request body (it is raw text, not
 * JSON/XML). An optional request `path` — the GraphQL endpoint path the
 * generated expectation should match — is passed as a query-string parameter;
 * when blank the server defaults to `/graphql`.
 *
 * @throws Error with the server's message on a non-2xx response (e.g. a schema
 *         that cannot be parsed).
 */
export async function importGraphql(
  params: ConnectionParams,
  schema: string,
  path?: string,
): Promise<unknown[]> {
  const query = path && path.trim() ? `?path=${encodeURIComponent(path.trim())}` : '';
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/graphql${query}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'text/plain' },
    body: schema,
  });
  if (!res.ok) {
    throw new Error((await res.text()) || `HTTP ${res.status} ${res.statusText}`);
  }
  const created = (await res.json()) as unknown;
  return Array.isArray(created) ? created : [];
}
