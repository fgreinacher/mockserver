import { describe, it, expect, vi, afterEach } from 'vitest';
import { diffRuns } from '../lib/runDiff';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface FetchCall { url: string; init?: RequestInit; }

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

describe('diffRuns', () => {
  it('PUTs { before, after } to /mockserver/llm/diffRuns and parses the result', async () => {
    const serverResult = {
      promptChanged: true,
      messageCountBefore: 2,
      messageCountAfter: 3,
      messageDiffs: [
        { changeType: 'MODIFIED', role: 'user', beforeText: 'hi', afterText: 'hello' },
        { changeType: 'ADDED', role: 'assistant', beforeText: null, afterText: 'new turn' },
      ],
      toolCallsAdded: ['search'],
      toolCallsRemoved: [],
      tokenDelta: {
        inputTokensBefore: 10, inputTokensAfter: 20, inputTokensDelta: 10,
        outputTokensBefore: 5, outputTokensAfter: 8, outputTokensDelta: 3,
        costUsdBefore: 0.01, costUsdAfter: 0.02, costUsdDelta: 0.01,
      },
    };
    const calls = stubFetch(200, serverResult);

    const result = await diffRuns(params, { host: 'api.anthropic.com' }, { host: 'api.anthropic.com' });
    expect(result.promptChanged).toBe(true);
    expect(result.messageCountBefore).toBe(2);
    expect(result.messageCountAfter).toBe(3);
    expect(result.messageDiffs).toHaveLength(2);
    expect(result.toolCallsAdded).toEqual(['search']);
    expect(result.tokenDelta?.inputTokensDelta).toBe(10);

    expect(calls[0]?.url).toBe('http://127.0.0.1:1080/mockserver/llm/diffRuns');
    expect(calls[0]?.init?.method).toBe('PUT');
    expect(JSON.parse(String(calls[0]?.init?.body))).toEqual({
      before: { host: 'api.anthropic.com' },
      after: { host: 'api.anthropic.com' },
    });
  });

  it('fills defaults for a sparse server response', async () => {
    stubFetch(200, { promptChanged: false });
    const result = await diffRuns(params, {}, {});
    expect(result.promptChanged).toBe(false);
    expect(result.messageDiffs).toEqual([]);
    expect(result.toolCallsAdded).toEqual([]);
    expect(result.toolCallsRemoved).toEqual([]);
    expect(result.tokenDelta).toBeNull();
  });

  it('throws the server message on a 500', async () => {
    stubFetch(500, 'Internal error diffing agent runs');
    await expect(diffRuns(params, {}, {})).rejects.toThrow('Internal error diffing agent runs');
  });
});
