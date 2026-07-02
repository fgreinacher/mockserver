import { describe, it, expect, vi, afterEach } from 'vitest';
import { validateRecordedTraffic, type TrafficValidationReport } from '../lib/contractTest';

const params = { host: '127.0.0.1', port: '1080', secure: false };

afterEach(() => { vi.restoreAllMocks(); });

const report: TrafficValidationReport = {
  totalRequests: 1,
  passed: 0,
  failed: 1,
  allPassed: false,
  results: [
    {
      method: 'POST',
      path: '/pet',
      matchedOperation: 'createPet',
      passed: false,
      requestErrors: ['body did not match schema'],
      responseErrors: ['response status 500 is not defined in the spec'],
    },
  ],
};

describe('validateRecordedTraffic', () => {
  it('PUTs the spec to /mockserver/trafficValidate with no baseUrl and returns the report', async () => {
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => report }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await validateRecordedTraffic(params, { spec: 'https://example.com/openapi.json' });

    expect(result).toEqual(report);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain('/mockserver/trafficValidate');
    expect(init.method).toBe('PUT');
    const sent = JSON.parse(init.body as string) as Record<string, unknown>;
    expect(sent).toEqual({ spec: 'https://example.com/openapi.json' });
    expect(sent).not.toHaveProperty('baseUrl');
  });

  it('surfaces the server { error } envelope as the thrown message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 403,
        statusText: 'Forbidden',
        json: async () => ({ error: 'traffic validation spec fetch blocked by SSRF policy: blocked' }),
      })),
    );

    await expect(validateRecordedTraffic(params, { spec: 'http://169.254.169.254/spec' })).rejects.toThrow(
      /blocked by SSRF policy/,
    );
  });

  it('falls back to the status line when the error body is not JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        json: async () => { throw new Error('not json'); },
      })),
    );

    await expect(validateRecordedTraffic(params, { spec: 'inline' })).rejects.toThrow(/HTTP 500/);
  });
});
