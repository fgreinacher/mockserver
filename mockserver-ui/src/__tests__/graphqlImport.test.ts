import { describe, it, expect, vi, afterEach } from 'vitest';
import { importGraphql } from '../lib/graphqlImport';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface FetchCall {
  url: string;
  init?: RequestInit;
}

function stubFetch(status: number, body: unknown): FetchCall[] {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return {
        ok: status >= 200 && status < 300,
        status,
        statusText: 'stub',
        json: async () => body,
        text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
      };
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('importGraphql', () => {
  it('PUTs the SDL body and returns created expectations', async () => {
    const created = [{ id: 'graphql' }];
    const calls = stubFetch(201, created);
    const result = await importGraphql(params, 'type Query { hello: String }');
    expect(result).toEqual(created);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/graphql');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(calls[0]?.init?.body).toBe('type Query { hello: String }');
  });

  it('appends the endpoint path as a query parameter when provided', async () => {
    const calls = stubFetch(201, []);
    await importGraphql(params, 'type Query { hello: String }', '/api/graphql');
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/graphql?path=%2Fapi%2Fgraphql');
  });

  it('omits the path query parameter when the path is blank', async () => {
    const calls = stubFetch(201, []);
    await importGraphql(params, 'type Query { hello: String }', '   ');
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/graphql');
  });

  it('returns an empty array when the response is not an array', async () => {
    stubFetch(201, { unexpected: true });
    expect(await importGraphql(params, 'type Query { hello: String }')).toEqual([]);
  });

  it('throws the server message on an invalid schema', async () => {
    stubFetch(400, 'could not parse GraphQL schema');
    await expect(importGraphql(params, 'not a schema')).rejects.toThrow('could not parse GraphQL schema');
  });
});
