import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  getConfiguration,
  updateConfiguration,
  getProxyConfiguration,
  bindAdditionalPort,
} from '../lib/configuration';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('configuration client', () => {
  it('GETs the configuration', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ logLevel: 'INFO', metricsEnabled: false }) });
    vi.stubGlobal('fetch', fetchMock);
    const config = await getConfiguration(params);
    expect(config).toEqual({ logLevel: 'INFO', metricsEnabled: false });
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/configuration');
  });

  it('PUTs a partial configuration change', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);
    await updateConfiguration(params, { logLevel: 'DEBUG' });
    const [, init] = fetchMock.mock.calls[0]!;
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ logLevel: 'DEBUG' });
  });

  it('throws the server error text on a failed update', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, statusText: 'Bad Request', text: async () => 'invalid logLevel' }));
    await expect(updateConfiguration(params, { logLevel: 'NOPE' })).rejects.toThrow('invalid logLevel');
  });
});

describe('getProxyConfiguration', () => {
  it('GETs /mockserver/proxyConfiguration and normalises the payload', async () => {
    const payload = {
      caCertificatePath: '/data/ca.pem',
      caCertificatePem: '-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----',
      httpsProxy: 'http://127.0.0.1:1080',
      environmentVariables: { unix: 'export https_proxy=...', powershell: '$env:HTTPS_PROXY=...' },
      usingDefaultCa: true,
      warning: null,
    };
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => payload });
    vi.stubGlobal('fetch', fetchMock);

    const result = await getProxyConfiguration(params);
    expect(result).toEqual(payload);
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/proxyConfiguration');
  });

  it('fills defaults for missing fields', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ httpsProxy: 'http://h:1080' }) }));
    const result = await getProxyConfiguration(params);
    expect(result.httpsProxy).toBe('http://h:1080');
    expect(result.environmentVariables).toEqual({ unix: '', powershell: '' });
    expect(result.usingDefaultCa).toBe(false);
    expect(result.warning).toBeNull();
  });

  it('throws the server error envelope on a 400', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 400, statusText: 'Bad Request',
      text: async () => '{"error":"failed to get proxy configuration"}',
    }));
    await expect(getProxyConfiguration(params)).rejects.toThrow('failed to get proxy configuration');
  });
});

describe('bindAdditionalPort', () => {
  it('PUTs a PortBinding body and returns the bound ports', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ports: [1080, 1090] }) });
    vi.stubGlobal('fetch', fetchMock);

    const ports = await bindAdditionalPort(params, 1090);
    expect(ports).toEqual([1080, 1090]);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/bind');
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ ports: [1090] });
  });

  it('throws the server message when the port is already in use', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false, status: 400, statusText: 'Bad Request',
      text: async () => 'Failed to bind to port port already in use',
    }));
    await expect(bindAdditionalPort(params, 1090)).rejects.toThrow('port already in use');
  });
});
