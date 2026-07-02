import { describe, it, expect, vi, afterEach } from 'vitest';
import { loadAsyncApi, generateHttpExpectations, getAsyncApiStatus, verifyAsyncApi, AsyncApiUnavailableError } from '../lib/asyncApi';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('asyncApi client', () => {
  it('PUTs the spec body to /asyncapi and returns the result', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => ({ channels: ['orders'] }) });
    vi.stubGlobal('fetch', fetchMock);
    const result = await loadAsyncApi(params, 'asyncapi: 3.0.0');
    expect(result).toEqual({ channels: ['orders'] });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/asyncapi');
    expect((init as RequestInit).method).toBe('PUT');
    expect((init as RequestInit).body).toBe('asyncapi: 3.0.0');
  });

  it('throws AsyncApiUnavailableError on 501 from load', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 501, json: async () => ({ error: 'not available' }) }));
    await expect(loadAsyncApi(params, 'x')).rejects.toBeInstanceOf(AsyncApiUnavailableError);
  });

  it('getAsyncApiStatus returns null on 501 and the status object otherwise', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 501 }));
    expect(await getAsyncApiStatus(params)).toBeNull();

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ loaded: true }) }));
    expect(await getAsyncApiStatus(params)).toEqual({ loaded: true });
  });

  it('surfaces the server error message on a 400', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, statusText: 'Bad Request', json: async () => ({ error: 'failed to load AsyncAPI spec: bad yaml' }) }));
    await expect(loadAsyncApi(params, 'bad')).rejects.toThrow('bad yaml');
  });

  it('verifyAsyncApi returns verified on 202', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 202 });
    vi.stubGlobal('fetch', fetchMock);
    const result = await verifyAsyncApi(params, '{"channel":"orders"}');
    expect(result).toEqual({ verified: true, message: '' });
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/asyncapi/verify');
    expect((fetchMock.mock.calls[0]![1] as RequestInit).method).toBe('PUT');
  });

  it('verifyAsyncApi returns the failure message on 406', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 406, text: async () => 'expected at least 1 message on orders' }));
    expect(await verifyAsyncApi(params, '{}')).toEqual({ verified: false, message: 'expected at least 1 message on orders' });
  });

  it('verifyAsyncApi throws AsyncApiUnavailableError on 501', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 501 }));
    await expect(verifyAsyncApi(params, '{}')).rejects.toBeInstanceOf(AsyncApiUnavailableError);
  });

  it('generateHttpExpectations PUTs the spec to /asyncapi/http and returns the created expectations', async () => {
    const created = [{ id: 'http-1' }, { id: 'http-2' }];
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => created });
    vi.stubGlobal('fetch', fetchMock);
    expect(await generateHttpExpectations(params, 'asyncapi: 3.0.0')).toEqual(created);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/asyncapi/http');
    expect((init as RequestInit).method).toBe('PUT');
    expect((init as RequestInit).body).toBe('asyncapi: 3.0.0');
  });

  it('generateHttpExpectations throws AsyncApiUnavailableError on 501', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 501 }));
    await expect(generateHttpExpectations(params, 'x')).rejects.toBeInstanceOf(AsyncApiUnavailableError);
  });

  it('generateHttpExpectations surfaces the server error envelope on a 400', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, statusText: 'Bad Request', json: async () => ({ error: 'no channels in spec' }) }));
    await expect(generateHttpExpectations(params, 'bad')).rejects.toThrow('no channels in spec');
  });
});
