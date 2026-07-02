/**
 * Client for MockServer's WASM custom rule module endpoints
 * (`/mockserver/wasm/modules`). Upload, list, delete, and dry-run-test WASM modules.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

function endpoint(params: ConnectionParams): string {
  return `${buildBaseUrl(params)}/mockserver/wasm/modules`;
}

/** A minimal sample request accepted by the WASM test endpoint. */
export interface WasmSampleRequest {
  method?: string;
  path?: string;
  headers?: Record<string, string | string[]>;
  queryStringParameters?: Record<string, string | string[]>;
  cookies?: Record<string, string>;
  body?: string;
}

/** The decision returned by POST /mockserver/wasm/test. */
export interface WasmTestResult {
  /** true when the module's match export accepted the sample request. */
  matched: boolean;
  /** the shaped response, when a candidate response was supplied and the module shapes it (ABI v3). */
  shaped?: unknown;
  [key: string]: unknown;
}

/**
 * Dry-run a loaded WASM module against a sample request via POST /mockserver/wasm/test,
 * returning the module's decision ({ matched, shaped? }) without a live expectation.
 * Throws in the `MockServer returned <status>: <body>` shape on any non-2xx so
 * {@code humanizeError} can surface the server's `{error}` message.
 */
export async function testWasmModule(
  params: ConnectionParams,
  moduleName: string,
  request: WasmSampleRequest,
): Promise<WasmTestResult> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/wasm/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ moduleName, request }),
  });
  if (!res.ok) {
    throw new Error(`MockServer returned ${res.status}: ${await res.text().catch(() => '')}`);
  }
  return (await res.json()) as WasmTestResult;
}

/** List all loaded WASM module names. */
export async function listWasmModules(
  params: ConnectionParams,
  signal?: AbortSignal,
): Promise<string[]> {
  const res = await fetch(endpoint(params), { signal });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} ${res.statusText}`);
  }
  const body = await res.json();
  return Array.isArray(body) ? body : [];
}

/** Upload a WASM module with the given name. */
export async function uploadWasmModule(
  params: ConnectionParams,
  name: string,
  fileBytes: ArrayBuffer,
): Promise<void> {
  const url = `${endpoint(params)}?name=${encodeURIComponent(name)}`;
  const res = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: fileBytes,
  });
  if (!res.ok) {
    let message = `HTTP ${res.status} ${res.statusText}`;
    try {
      message = await res.text();
    } catch {
      // keep status-line message
    }
    throw new Error(message);
  }
}

/** Delete a WASM module by name. */
export async function deleteWasmModule(
  params: ConnectionParams,
  name: string,
): Promise<void> {
  const url = `${endpoint(params)}?name=${encodeURIComponent(name)}`;
  const res = await fetch(url, { method: 'DELETE' });
  if (!res.ok) {
    let message = `HTTP ${res.status} ${res.statusText}`;
    try {
      message = await res.text();
    } catch {
      // keep status-line message
    }
    throw new Error(message);
  }
}
