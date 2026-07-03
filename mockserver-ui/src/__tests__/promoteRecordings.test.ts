import { describe, it, expect, vi, afterEach } from 'vitest';
import { promoteRecordings } from '../lib/promoteRecordings';
import type { ConnectionParams } from '../hooks/useConnectionParams';

const params: ConnectionParams = { host: 'localhost', port: '1080', secure: false };

afterEach(() => {
  vi.restoreAllMocks();
});

describe('promoteRecordings', () => {
  it('omits the body entirely when no filter is set (promote all) and returns the count', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
    });
    vi.stubGlobal('fetch', fetchMock);

    const result = await promoteRecordings(params);

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://localhost:1080/mockserver/recordings/promote');
    expect((init as RequestInit).method).toBe('PUT');
    // No body — so the server promotes all recorded traffic.
    expect((init as RequestInit).body).toBeUndefined();
    expect(result.count).toBe(3);
    expect(result.expectations).toHaveLength(3);
  });

  it('sends only the populated filter fields as the request-matcher body', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => [] });
    vi.stubGlobal('fetch', fetchMock);

    await promoteRecordings(params, { path: '/api/.*' });

    const [, init] = fetchMock.mock.calls[0]!;
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ path: '/api/.*' });
  });

  it('appends off-switch query parameters only when a promotion option is explicitly disabled', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => [] });
    vi.stubGlobal('fetch', fetchMock);

    await promoteRecordings(params, undefined, { redactSensitiveData: false, consolidate: false });

    const [url] = fetchMock.mock.calls[0]!;
    expect(url).toContain('consolidate=false');
    expect(url).toContain('redactSensitiveData=false');
    // parameterize left at its default → no parameter appended.
    expect(url).not.toContain('parameterize');
  });

  it('throws a humanizable error carrying the plain-text server body on a non-2xx response', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      text: async () => 'no recorded traffic to promote',
    });
    vi.stubGlobal('fetch', fetchMock);

    await expect(promoteRecordings(params)).rejects.toThrow(
      /MockServer returned 400: no recorded traffic to promote/,
    );
  });
});
