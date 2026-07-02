import { describe, it, expect, vi, afterEach } from 'vitest';
import { createScimProvider } from '../lib/scim';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('createScimProvider', () => {
  it('PUTs the config to /mockserver/scim and returns the created-expectation count', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [{ id: '1' }, { id: '2' }, { id: '3' }] });
    vi.stubGlobal('fetch', fetchMock);

    const count = await createScimProvider(params, {
      basePath: '/scim/v2',
      idStrategy: 'AUTO_INCREMENT',
      requireBearerToken: true,
      expectedBearerToken: 'secret',
    });
    expect(count).toBe(3);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/scim');
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      basePath: '/scim/v2',
      idStrategy: 'AUTO_INCREMENT',
      requireBearerToken: true,
      expectedBearerToken: 'secret',
    });
  });

  it('PUTs an empty config body for a fully-defaulted provider', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [{ id: '1' }] });
    vi.stubGlobal('fetch', fetchMock);
    const count = await createScimProvider(params, {});
    expect(count).toBe(1);
    const [, init] = fetchMock.mock.calls[0]!;
    expect((init as RequestInit).body).toBe('{}');
  });

  it('throws a humanizable "MockServer returned <status>: <body>" error on failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, statusText: 'Bad Request', text: async () => 'invalid base path' }));
    await expect(createScimProvider(params, {})).rejects.toThrow('MockServer returned 400: invalid base path');
  });

  it('returns 0 when the server body is not an array', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }));
    expect(await createScimProvider(params, {})).toBe(0);
  });
});
