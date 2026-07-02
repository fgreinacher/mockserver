import { describe, it, expect, vi, afterEach } from 'vitest';
import { exportPact, verifyPact, importPact } from '../lib/pactExport';

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

describe('exportPact', () => {
  it('PUTs with consumer and provider query parameters', async () => {
    const pact = { consumer: { name: 'Web' }, provider: { name: 'Api' }, interactions: [] };
    const calls = stubFetch(200, pact);
    const result = await exportPact(params, 'Web', 'Api');
    expect(result).toEqual(pact);
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/pact?consumer=Web&provider=Api');
    expect(calls[0]?.init?.method).toBe('PUT');
  });

  it('omits blank consumer/provider so the server defaults apply', async () => {
    const calls = stubFetch(200, {});
    await exportPact(params, '  ', '');
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/pact');
  });

  it('throws on a non-2xx response', async () => {
    stubFetch(500, 'boom');
    await expect(exportPact(params, 'c', 'p')).rejects.toThrow('boom');
  });
});

describe('verifyPact', () => {
  it('PUTs the contract to /pact/verify and reports verified on 202', async () => {
    const calls = stubFetch(202, { matched: 3 });
    const result = await verifyPact(params, '{"interactions":[]}');
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/pact/verify');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(calls[0]?.init?.body).toBe('{"interactions":[]}');
    expect(result).toEqual({ verified: true, result: { matched: 3 } });
  });

  it('reports not-verified on 406 with the report', async () => {
    stubFetch(406, { unmatched: 1 });
    expect(await verifyPact(params, '{}')).toEqual({ verified: false, result: { unmatched: 1 } });
  });

  it('throws the text body on other errors', async () => {
    stubFetch(400, 'Pact contract JSON must not be empty');
    await expect(verifyPact(params, '')).rejects.toThrow('Pact contract JSON must not be empty');
  });
});

describe('importPact', () => {
  it('PUTs the contract to /pact/import and returns the created expectations', async () => {
    const created = [{ id: 'pact-1' }, { id: 'pact-2' }];
    const calls = stubFetch(201, created);
    const result = await importPact(params, '{"interactions":[]}');
    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/pact/import');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(calls[0]?.init?.body).toBe('{"interactions":[]}');
    expect(result).toEqual(created);
  });

  it('normalises a non-array 2xx body to an empty array', async () => {
    stubFetch(201, { not: 'an array' });
    expect(await importPact(params, '{}')).toEqual([]);
  });

  it('throws in the MockServer-returned shape so the error envelope surfaces', async () => {
    stubFetch(400, 'Pact import request body is required — must be a Pact v3 contract JSON document');
    await expect(importPact(params, '')).rejects.toThrow(
      'MockServer returned 400: Pact import request body is required — must be a Pact v3 contract JSON document',
    );
  });
});
