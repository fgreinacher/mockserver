/**
 * Client for MockServer's PUT /mockserver/scim — stands up a mock SCIM 2.0 provider
 * (serving `/scim/v2/Users`, `/scim/v2/Groups`, and the discovery endpoints) as a set of
 * expectations. All fields are optional; the server fills in sensible defaults so an empty
 * body produces a fully functional SCIM provider.
 *
 * Field names mirror the server's ScimProviderConfiguration exactly.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

/** ID-generation strategy for created SCIM resources (mirrors the server's IdStrategy enum). */
export type ScimIdStrategy = 'UUID' | 'AUTO_INCREMENT';

export interface ScimConfig {
  /** Base path the SCIM endpoints are mounted under. Default: /scim/v2 */
  basePath?: string;
  /** How ids for created resources are generated. Default: UUID */
  idStrategy?: ScimIdStrategy;
  /** Seed User resources served immediately after registration. */
  initialUsers?: Record<string, unknown>[];
  /** Seed Group resources served immediately after registration. */
  initialGroups?: Record<string, unknown>[];
  /** Enforce SCIM filter query parsing on list endpoints. Default: true */
  enforceFilter?: boolean;
  /** Enforce SCIM PATCH semantics. Default: true */
  enforcePatch?: boolean;
  /** Require an `Authorization: Bearer <token>` header on SCIM requests. Default: false */
  requireBearerToken?: boolean;
  /**
   * Pin the accepted bearer token to a specific value. Only meaningful with
   * requireBearerToken=true; when left unset any non-empty token is accepted.
   * Write-only on the server — never echoed back in responses.
   */
  expectedBearerToken?: string;
}

/**
 * Create the mock SCIM provider. Returns the number of expectations the server registered.
 *
 * On failure throws an Error whose message carries the `MockServer returned <status>: <body>`
 * shape so callers can pass it through {@link import('./errorMessage').humanizeError}.
 */
export async function createScimProvider(params: ConnectionParams, config: ScimConfig): Promise<number> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/scim`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`MockServer returned ${res.status}: ${text}`);
  }
  const body = await res.json().catch(() => []);
  return Array.isArray(body) ? body.length : 0;
}
