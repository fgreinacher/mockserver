/**
 * FROZEN PRE-OPTIMIZATION COPY of src/lib/sessionGrouping.ts at git commit
 * 7dae8c885 (parent of e09495682) — for benchmarking only. Differs from the
 * shipped version ONLY in that it calls the uncached `parseTraffic` (the NEW
 * version calls `cachedParseTraffic`). Import paths are rebased to reach the
 * real production modules from this legacy directory. Imported only by benches.
 */

/**
 * Group captured LLM traffic by isolation key so multi-agent runs
 * appear as parallel swim-lanes instead of an interleaved log.
 *
 * The isolation source is encoded in the scenario name of conversation
 * expectations as `__iso=<sourceType>:<sourceKey>`, e.g.
 * `__llm_conv_myConv__iso=header:x-agent-id`.
 *
 * Requests that don't match an isolated expectation (or whose isolation
 * header/param/cookie is missing) are grouped under `<unscoped>`.
 */

import type { JsonListItem } from '../../types';
import { parseTraffic, type ParsedTraffic } from '../../lib/llmTraffic';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface SessionRequest {
  item: JsonListItem;
  parsed: ParsedTraffic;
  path: string | null;
  method: string | null;
  statusCode: number | null;
  timestamp: number; // insertion order index for chronological sort
}

export interface Session {
  scenarioName: string;
  isolationKey: string;
  requests: SessionRequest[];
}

// ---------------------------------------------------------------------------
// Isolation source parsing from scenario names
// ---------------------------------------------------------------------------

export interface IsolationSource {
  scenarioName: string;
  /** The base scenario name without the __iso= suffix */
  baseName: string;
  sourceType: 'header' | 'query_parameter' | 'cookie';
  sourceKey: string;
}

// The wire format produced by Java's `IsolationSource.encode()` is
// `<kind>:<name>` where `<kind>` is the enum name lowercased — so the
// `QUERY_PARAMETER` enum becomes `query_parameter`, not `queryParameter`.
const ISO_REGEX = /^(.+)__iso=(header|query_parameter|cookie):(.+)$/;

export function parseIsolationSource(scenarioName: string): IsolationSource | null {
  const match = ISO_REGEX.exec(scenarioName);
  if (!match) return null;
  const baseName = match[1];
  const sourceType = match[2] as 'header' | 'query_parameter' | 'cookie';
  const sourceKey = match[3];
  if (!baseName || !sourceKey) return null;
  return { scenarioName, baseName, sourceType, sourceKey };
}

// ---------------------------------------------------------------------------
// Extraction helpers for request values
// ---------------------------------------------------------------------------

function getHeaderValue(headers: unknown, name: string): string | null {
  if (!headers) return null;
  const lower = name.toLowerCase();

  if (Array.isArray(headers)) {
    for (const h of headers) {
      if (typeof h === 'object' && h !== null) {
        const entry = h as Record<string, unknown>;
        const n = entry['name'];
        if (typeof n === 'string' && n.toLowerCase() === lower) {
          const values = entry['values'];
          if (Array.isArray(values) && values.length > 0) {
            return String(values[0]);
          }
        }
      }
    }
    return null;
  }

  if (typeof headers === 'object' && headers !== null) {
    const map = headers as Record<string, unknown>;
    for (const key of Object.keys(map)) {
      if (key.toLowerCase() === lower) {
        const val = map[key];
        if (Array.isArray(val) && val.length > 0) return String(val[0]);
        if (typeof val === 'string') return val;
      }
    }
  }

  return null;
}

function getQueryParameterValue(queryParams: unknown, name: string): string | null {
  if (!queryParams) return null;
  const lower = name.toLowerCase();

  if (Array.isArray(queryParams)) {
    for (const p of queryParams) {
      if (typeof p === 'object' && p !== null) {
        const entry = p as Record<string, unknown>;
        const n = entry['name'];
        if (typeof n === 'string' && n.toLowerCase() === lower) {
          const values = entry['values'];
          if (Array.isArray(values) && values.length > 0) {
            return String(values[0]);
          }
        }
      }
    }
    return null;
  }

  if (typeof queryParams === 'object' && queryParams !== null) {
    const map = queryParams as Record<string, unknown>;
    for (const key of Object.keys(map)) {
      if (key.toLowerCase() === lower) {
        const val = map[key];
        if (Array.isArray(val) && val.length > 0) return String(val[0]);
        if (typeof val === 'string') return val;
      }
    }
  }

  return null;
}

function getCookieValue(cookies: unknown, name: string): string | null {
  if (!cookies) return null;

  if (Array.isArray(cookies)) {
    for (const c of cookies) {
      if (typeof c === 'object' && c !== null) {
        const entry = c as Record<string, unknown>;
        const n = entry['name'];
        if (typeof n === 'string' && n === name) {
          const val = entry['value'];
          if (typeof val === 'string') return val;
        }
      }
    }
    return null;
  }

  if (typeof cookies === 'object' && cookies !== null) {
    const map = cookies as Record<string, unknown>;
    const val = map[name];
    if (typeof val === 'string') return val;
  }

  return null;
}

function extractIsolationValue(
  httpRequest: Record<string, unknown>,
  source: IsolationSource,
): string | null {
  if (source.sourceType === 'header') {
    return getHeaderValue(httpRequest['headers'], source.sourceKey);
  }
  if (source.sourceType === 'query_parameter') {
    return getQueryParameterValue(httpRequest['queryStringParameters'], source.sourceKey);
  }
  if (source.sourceType === 'cookie') {
    return getCookieValue(httpRequest['cookies'], source.sourceKey);
  }
  return null;
}

// ---------------------------------------------------------------------------
// LLM traffic detection
// ---------------------------------------------------------------------------

const LLM_KINDS = new Set(['anthropic', 'openai', 'openai_responses', 'gemini', 'ollama']);

function isLlmKind(kind: string): boolean {
  return LLM_KINDS.has(kind);
}

// ---------------------------------------------------------------------------
// Host extraction for proxy-aware fallback grouping
// ---------------------------------------------------------------------------

/**
 * Extract a grouping key from a proxied LLM request when no isolation
 * expectation matched. For proxy traffic the Host header identifies the
 * upstream provider (e.g. "api.anthropic.com", "api.openai.com").
 * Returns null when no usable host is found.
 */
function extractProviderHost(httpRequest: Record<string, unknown>): string | null {
  return getHeaderValue(httpRequest['headers'], 'host');
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

const UNSCOPED = '<unscoped>';

/**
 * Shorten a scenario name for display. Strips the `__llm_conv_` prefix
 * and the `__iso=…` suffix.
 */
export function shortenScenarioName(name: string): string {
  let result = name;
  if (result.startsWith('__llm_conv_')) {
    result = result.slice('__llm_conv_'.length);
  }
  const isoIdx = result.indexOf('__iso=');
  if (isoIdx !== -1) {
    result = result.slice(0, isoIdx);
  }
  return result || name;
}

/**
 * Group proxied LLM requests into sessions based on isolation keys
 * derived from active expectations.
 */
export function groupBySession(
  proxiedRequests: JsonListItem[],
  activeExpectations: JsonListItem[],
): Session[] {
  // 1. Collect isolation sources from expectations.
  // `scenarioName` lives at the top level of the expectation payload
  // (see HttpLlmResponse builder + ExpectationDTO serialisation),
  // not nested under httpLlmResponse.
  const isolationSources: IsolationSource[] = [];
  for (const exp of activeExpectations) {
    const scenarioName = exp.value['scenarioName'] as string | undefined;
    if (!scenarioName) continue;
    const source = parseIsolationSource(scenarioName);
    if (source) {
      // Deduplicate by scenario name
      if (!isolationSources.some((s) => s.scenarioName === source.scenarioName)) {
        isolationSources.push(source);
      }
    }
  }

  // 2. Group requests by (scenarioName, isolationKeyValue)
  const sessionMap = new Map<string, Session>();

  for (let i = 0; i < proxiedRequests.length; i++) {
    const item = proxiedRequests[i]!;
    const parsed = parseTraffic(item.value);

    // Only consider LLM traffic
    if (!isLlmKind(parsed.kind)) continue;

    const httpRequest = item.value['httpRequest'] as Record<string, unknown> | undefined;
    if (!httpRequest) continue;

    const path = (httpRequest['path'] as string | undefined) ?? null;
    const method = (httpRequest['method'] as string | undefined) ?? null;
    const httpResponse = item.value['httpResponse'] as Record<string, unknown> | undefined;
    const statusCode = httpResponse
      ? (typeof httpResponse['statusCode'] === 'number' ? httpResponse['statusCode'] : null)
      : null;

    const sessionReq: SessionRequest = {
      item,
      parsed,
      path,
      method,
      statusCode,
      timestamp: i,
    };

    // Try to match against isolation sources
    let matched = false;
    for (const source of isolationSources) {
      const isolationValue = extractIsolationValue(httpRequest, source);
      if (isolationValue !== null) {
        const key = `${source.scenarioName}::${isolationValue}`;
        let session = sessionMap.get(key);
        if (!session) {
          session = {
            scenarioName: source.scenarioName,
            isolationKey: isolationValue,
            requests: [],
          };
          sessionMap.set(key, session);
        }
        session.requests.push(sessionReq);
        matched = true;
        break;
      }
    }

    if (!matched) {
      // Proxy-aware fallback: group by the upstream host (from the Host
      // header) so that proxy traffic to different LLM providers gets
      // separate session lanes instead of one giant unscoped bucket.
      const providerHost = extractProviderHost(httpRequest);
      const groupKey = providerHost ?? UNSCOPED;
      const unscopedKey = `${UNSCOPED}::${groupKey}`;
      let session = sessionMap.get(unscopedKey);
      if (!session) {
        session = {
          scenarioName: UNSCOPED,
          isolationKey: groupKey,
          requests: [],
        };
        sessionMap.set(unscopedKey, session);
      }
      session.requests.push(sessionReq);
    }
  }

  // 3. Sort requests within each session chronologically
  for (const session of sessionMap.values()) {
    session.requests.sort((a, b) => a.timestamp - b.timestamp);
  }

  // 4. Return sessions as array
  return Array.from(sessionMap.values());
}
