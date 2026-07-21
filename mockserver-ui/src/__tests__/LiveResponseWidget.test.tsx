import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, waitFor, fireEvent, act } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import LiveResponseWidget from '../components/LiveResponseWidget';

/**
 * The dashboard is served by MockServer itself, so its own origin is a valid
 * mock target. Mirror that here by deriving the connection params from the jsdom
 * location — anything else would be cross-origin and trip the CORS path.
 */
const sameOriginParams = {
  host: window.location.hostname,
  port: window.location.port,
  secure: window.location.protocol === 'https:',
};

interface StubResponse {
  ok?: boolean;
  status?: number;
  statusText?: string;
  headers?: Record<string, string>;
  body?: string;
  /** Spy used in place of Response.text(), to prove an oversize body is never read. */
  text?: () => Promise<string>;
}

/** Route the two calls this widget makes: bound-ports status, and the live send. */
function stubFetch(options: {
  ports?: number[];
  statusFails?: boolean;
  live?: StubResponse;
  liveError?: Error;
  /** Never settle the live send until its signal aborts (for timeout/unmount tests). */
  liveHangs?: boolean;
}) {
  const fetchMock = vi.fn(async (url: string | URL, init?: RequestInit) => {
    const href = String(url);
    if (href.endsWith('/mockserver/status')) {
      if (options.statusFails) throw new TypeError('Failed to fetch');
      return {
        ok: true,
        status: 200,
        statusText: 'OK',
        json: async () => ({ ports: options.ports ?? [Number(sameOriginParams.port) || 80] }),
      } as unknown as Response;
    }
    if (init?.signal?.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
    if (options.liveHangs) {
      return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () =>
          reject(Object.assign(new Error('The operation was aborted.'), { name: 'AbortError' })),
        );
      });
    }
    if (options.liveError) throw options.liveError;
    const live = options.live ?? {};
    return {
      ok: live.ok ?? true,
      status: live.status ?? 200,
      statusText: live.statusText ?? 'OK',
      headers: new Headers(live.headers ?? {}),
      text: live.text ?? (async () => live.body ?? ''),
    } as unknown as Response;
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function widget(expectationJson: string, params = sameOriginParams) {
  return (
    <ThemeProvider theme={buildTheme('dark')}>
      <LiveResponseWidget
        expectationJson={expectationJson}
        connectionParams={params}
        onClose={() => {}}
      />
    </ThemeProvider>
  );
}

async function renderWidget(expectationJson: string, params = sameOriginParams) {
  const utils = render(widget(expectationJson, params));
  // Let the mount-time bound-ports lookup settle so its state update lands inside act().
  await act(async () => {});
  return utils;
}

function input(label: string): HTMLInputElement | HTMLTextAreaElement {
  return screen.getByLabelText(label) as HTMLInputElement | HTMLTextAreaElement;
}

function sendButton(): HTMLButtonElement {
  return screen.getByRole('button', { name: /Send request/i }) as HTMLButtonElement;
}

beforeEach(() => {
  stubFetch({});
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('LiveResponseWidget request derivation', () => {
  it('pre-fills every literal matcher field', async () => {
    await renderWidget(
      JSON.stringify({
        httpRequest: {
          method: 'POST',
          path: '/api/orders',
          queryStringParameters: { page: ['2'], sort: ['name'] },
          headers: { Accept: ['application/json'] },
          body: '{"id":1}',
        },
      }),
    );

    expect(input('Live request method').value).toBe('POST');
    expect(input('Live request path').value).toBe('/api/orders');
    expect(input('Live request query').value).toBe('page=2\nsort=name');
    expect(input('Live request headers').value).toBe('Accept: application/json');
    expect(input('Live request body').value).toBe('{"id":1}');
    expect(screen.queryByTestId('try-it-derivation-notes')).toBeNull();
    expect(sendButton()).toBeEnabled();
  });

  it('refuses to send a regex path as a literal and blocks sending until one is typed', async () => {
    await renderWidget(JSON.stringify({ httpRequest: { method: 'GET', path: '/api/.*' } }));

    expect(input('Live request path').value).toBe('');
    expect(sendButton()).toBeDisabled();
    const notes = screen.getByTestId('try-it-derivation-notes');
    expect(notes.textContent).toContain('path');
    expect(notes.textContent).toContain('/api/.*');

    fireEvent.change(input('Live request path'), { target: { value: '/api/users' } });
    expect(sendButton()).toBeEnabled();
  });

  it.each([
    ['character class', '/api/[0-9]+/x'],
    ['alternation', '/(a|b)/c'],
    ['anchored', '^/api/users$'],
    ['escape', '/api/\\d+'],
    ['negation', '!/api/users'],
  ])('treats a %s path as a pattern, not a literal', async (_label, path) => {
    await renderWidget(JSON.stringify({ httpRequest: { path } }));
    expect(input('Live request path').value).toBe('');
    expect(screen.getByTestId('try-it-derivation-notes').textContent).toContain('path');
  });

  it('keeps a literal path containing a dot', async () => {
    await renderWidget(JSON.stringify({ httpRequest: { path: '/v1/report.json' } }));
    expect(input('Live request path').value).toBe('/v1/report.json');
    expect(screen.queryByTestId('try-it-derivation-notes')).toBeNull();
  });

  it('drops negated and schema headers but keeps the literal ones', async () => {
    await renderWidget(
      JSON.stringify({
        httpRequest: {
          path: '/api/users',
          headers: {
            Accept: ['application/json'],
            '!X-Forbidden': ['nope'],
            'X-Trace': [{ not: true, value: 'abc' }],
            'X-Schema': [{ schema: { type: 'string' } }],
            'X-Pattern': ['v[0-9]+'],
          },
        },
      }),
    );

    expect(input('Live request headers').value).toBe('Accept: application/json');
    const notes = screen.getByTestId('try-it-derivation-notes').textContent ?? '';
    expect(notes).toContain('headers');
    expect(notes).toContain('!X-Forbidden');
    expect(notes).toContain('X-Trace');
    expect(notes).toContain('X-Schema');
    expect(notes).toContain('X-Pattern');
  });

  it('reads the array form of the header matcher', async () => {
    await renderWidget(
      JSON.stringify({
        httpRequest: {
          path: '/api/users',
          headers: [{ name: 'Accept', values: ['text/plain'] }],
        },
      }),
    );
    expect(input('Live request headers').value).toBe('Accept: text/plain');
  });

  it('substitutes literal path parameters into a templated path', async () => {
    await renderWidget(
      JSON.stringify({
        httpRequest: { path: '/users/{userId}/orders', pathParameters: { userId: ['42'] } },
      }),
    );
    expect(input('Live request path').value).toBe('/users/42/orders');
  });

  it('leaves a templated path blank when the path parameter is itself a pattern', async () => {
    await renderWidget(
      JSON.stringify({
        httpRequest: { path: '/users/{userId}', pathParameters: { userId: ['[0-9]+'] } },
      }),
    );
    expect(input('Live request path').value).toBe('');
    expect(screen.getByTestId('try-it-derivation-notes').textContent).toContain('path');
  });

  it('names cookie matchers as unexercisable instead of pretending to send them', async () => {
    await renderWidget(
      JSON.stringify({ httpRequest: { path: '/api/users', cookies: { session: 'abc', theme: 'dark' } } }),
    );
    // No Cookie header (fetch would strip it) and no cookie input at all — a page
    // cannot set one, so offering a field would promise something undeliverable.
    expect(input('Live request headers').value).toBe('');
    expect(screen.queryByLabelText('Live request cookies')).toBeNull();
    const notes = screen.getByTestId('try-it-derivation-notes').textContent ?? '';
    expect(notes).toContain('cookies (browser-blocked)');
    expect(notes).toContain('session');
    expect(notes).toContain('theme');
  });

  it('does not write document.cookie when deriving or sending a cookie matcher', async () => {
    stubFetch({ live: { body: 'ok' } });
    const writes: string[] = [];
    const original = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: () => original?.get?.call(document) ?? '',
      set: (value: string) => {
        writes.push(value);
      },
    });
    try {
      await renderWidget(
        JSON.stringify({
          // A value that, if interpolated into document.cookie, would create a
          // domain-scoped cookie surviving any host-only cleanup.
          httpRequest: { path: '/api/orders', cookies: { session: 'abc; domain=example.com; max-age=31536000' } },
        }),
      );
      fireEvent.click(sendButton());
      await waitFor(() => expect(screen.getByTestId('try-it-result')).toBeInTheDocument());
      expect(writes).toEqual([]);
    } finally {
      delete (document as unknown as Record<string, unknown>)['cookie'];
    }
  });

  it.each([
    ['Cookie', 'Cookie'],
    ['Host', 'Host'],
    ['Content-Length', 'Content-Length'],
    ['Proxy-Authorization', 'Proxy-Authorization'],
    ['Sec-Fetch-Mode', 'Sec-Fetch-Mode'],
  ])('never pre-fills %s, which fetch would strip silently', async (_label, header) => {
    await renderWidget(
      JSON.stringify({
        httpRequest: { path: '/api/users', headers: { [header]: ['x'], Accept: ['text/plain'] } },
      }),
    );
    expect(input('Live request headers').value).toBe('Accept: text/plain');
    const notes = screen.getByTestId('try-it-derivation-notes').textContent ?? '';
    expect(notes).toContain('browser-blocked');
    expect(notes).toContain(header);
  });

  it.each([
    ['spaced numeric comparison', '> 60'],
    ['unspaced numeric comparison', '>60'],
    ['unspaced greater-or-equal', '>=60'],
    ['unspaced less-or-equal', '<=30'],
    ['numeric equality', '== 5'],
    ['unspaced numeric equality', '==5'],
    ['negative operand', '> -3'],
    ['decimal operand', '>= 1.5'],
    ['Accept negotiation', 'accept:application/json'],
  ])('refuses a %s header value, which does not fall back to string equality', async (_label, value) => {
    await renderWidget(
      JSON.stringify({ httpRequest: { path: '/api/users', headers: { 'X-Count': [value] } } }),
    );
    expect(input('Live request headers').value).toBe('');
    expect(screen.getByTestId('try-it-derivation-notes').textContent).toContain('X-Count');
  });

  it.each([
    ['non-numeric operand', '> abc'],
    ['no operand', '>'],
    ['comparison mid-value', 'count > 60'],
  ])('keeps %s as a literal — Java matches those by string equality', async (_label, value) => {
    await renderWidget(
      JSON.stringify({ httpRequest: { path: '/api/users', headers: { 'X-Note': [value] } } }),
    );
    expect(input('Live request headers').value).toBe(`X-Note: ${value}`);
    expect(screen.queryByTestId('try-it-derivation-notes')).toBeNull();
  });

  it('still allows those syntaxes in a path, where they are not matcher syntax', async () => {
    await renderWidget(JSON.stringify({ httpRequest: { path: '/accept:json' } }));
    expect(input('Live request path').value).toBe('/accept:json');
  });

  it('rejects an optional-and-negated key (?!name), not just !name', async () => {
    await renderWidget(
      JSON.stringify({
        httpRequest: { path: '/api/users', headers: { '?!X-Forbidden': ['nope'], '?X-Optional': ['yes'] } },
      }),
    );
    // The optional marker alone is stripped; the negation behind it is honoured.
    expect(input('Live request headers').value).toBe('X-Optional: yes');
    expect(screen.getByTestId('try-it-derivation-notes').textContent).toContain('?!X-Forbidden');
  });

  it('keeps every value of a repeated header or query parameter', async () => {
    await renderWidget(
      JSON.stringify({
        httpRequest: {
          path: '/api/users',
          headers: { 'X-Tag': ['a', 'b'] },
          queryStringParameters: { tag: ['one', 'two'] },
        },
      }),
    );
    expect(input('Live request headers').value).toBe('X-Tag: a\nX-Tag: b');
    expect(input('Live request query').value).toBe('tag=one\ntag=two');
    expect(screen.queryByTestId('try-it-derivation-notes')).toBeNull();
  });

  it('notes a key whose later values are not literal instead of truncating in silence', async () => {
    await renderWidget(
      JSON.stringify({ httpRequest: { path: '/api/users', headers: { 'X-Tag': ['a', '[0-9]+'] } } }),
    );
    expect(input('Live request headers').value).toBe('X-Tag: a');
    expect(screen.getByTestId('try-it-derivation-notes').textContent).toContain('X-Tag (some values)');
  });

  it('notes an array-form entry with a non-string key rather than dropping it silently', async () => {
    await renderWidget(
      JSON.stringify({
        httpRequest: {
          path: '/api/users',
          headers: [{ name: { schema: { type: 'string' } }, values: ['x'] }],
        },
      }),
    );
    expect(input('Live request headers').value).toBe('');
    expect(screen.getByTestId('try-it-derivation-notes').textContent).toContain('non-string key');
  });

  it('treats a !-prefixed plain string body as negated, not as a literal body', async () => {
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users', body: '!secret' } }));
    expect(input('Live request body').value).toBe('');
    expect(screen.getByTestId('try-it-derivation-notes').textContent).toContain('negated body matcher');
  });

  it.each([
    ['STRING', { type: 'STRING', string: 'hello', subString: true }, 'hello'],
    ['JSON', { type: 'JSON', json: { a: 1 } }, '{\n  "a": 1\n}'],
    ['XML', { type: 'XML', xml: '<a/>' }, '<a/>'],
  ])('derives a literal body from a %s body matcher', async (_label, body, expected) => {
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users', body } }));
    expect(input('Live request body').value).toBe(expected);
  });

  it.each([
    ['JSON_SCHEMA', { type: 'JSON_SCHEMA', jsonSchema: '{"type":"object"}' }],
    ['JSON_PATH', { type: 'JSON_PATH', jsonPath: '$.a' }],
    ['XPATH', { type: 'XPATH', xpath: '/a' }],
    ['REGEX', { type: 'REGEX', regex: '.*' }],
  ])('never sends a %s body matcher as a literal body', async (label, body) => {
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users', body } }));
    expect(input('Live request body').value).toBe('');
    const notes = screen.getByTestId('try-it-derivation-notes').textContent ?? '';
    expect(notes).toContain('body');
    expect(notes).toContain(label);
  });

  it('defaults the method to GET and flags a non-literal method', async () => {
    await renderWidget(JSON.stringify({ httpRequest: { method: 'GET|POST', path: '/api/users' } }));
    expect(input('Live request method').value).toBe('GET');
    expect(screen.getByTestId('try-it-derivation-notes').textContent).toContain('method');
  });

  it('refuses to fire a DNS expectation over HTTP', async () => {
    await renderWidget(JSON.stringify({ httpRequest: { dnsName: 'example.com', dnsType: 'A' } }));
    expect(screen.getByText(/DNS expectation/i)).toBeInTheDocument();
    expect(screen.queryByLabelText('Live request path')).toBeNull();
  });

  it('degrades gracefully when the draft is not valid JSON yet', async () => {
    await renderWidget('{ not json');
    expect(screen.getByText(/not valid JSON/i)).toBeInTheDocument();
  });
});

describe('LiveResponseWidget sending', () => {
  it('fires the edited request and renders status, headers, body and round-trip time', async () => {
    const fetchMock = stubFetch({
      live: {
        status: 201,
        statusText: 'Created',
        headers: { 'content-type': 'application/json', 'x-mock': 'yes' },
        body: '{"ok":true}',
      },
    });
    await renderWidget(
      JSON.stringify({
        httpRequest: {
          method: 'POST',
          path: '/api/orders',
          queryStringParameters: { page: ['2'] },
          headers: { Accept: ['application/json'] },
          body: '{"id":1}',
        },
      }),
    );

    fireEvent.click(sendButton());

    await waitFor(() => expect(screen.getByTestId('try-it-result')).toBeInTheDocument());

    const liveCall = fetchMock.mock.calls.find(([url]) => !String(url).endsWith('/mockserver/status'));
    expect(liveCall).toBeDefined();
    const [url, init] = liveCall as [string, RequestInit];
    expect(url).toBe(`${window.location.origin}/api/orders?page=2`);
    expect(init.method).toBe('POST');
    expect(init.body).toBe('{"id":1}');
    expect(new Headers(init.headers).get('Accept')).toBe('application/json');

    expect(screen.getByText('201 Created')).toBeInTheDocument();
    expect(screen.getByText('content-type: application/json')).toBeInTheDocument();
    expect(screen.getByText('x-mock: yes')).toBeInTheDocument();
    expect(screen.getByText('{"ok":true}')).toBeInTheDocument();
    expect(screen.getByText(/^\d+ ms$/)).toBeInTheDocument();
  });

  it('sends the user edits, not the derived values', async () => {
    const fetchMock = stubFetch({ live: { body: 'ok' } });
    await renderWidget(JSON.stringify({ httpRequest: { method: 'GET', path: '/api/.*' } }));

    fireEvent.change(input('Live request path'), { target: { value: '/api/users' } });
    fireEvent.change(input('Live request headers'), { target: { value: 'X-Trace: 123' } });
    fireEvent.click(sendButton());

    await waitFor(() => expect(screen.getByTestId('try-it-result')).toBeInTheDocument());
    const [url, init] = fetchMock.mock.calls.find(
      ([u]) => !String(u).endsWith('/mockserver/status'),
    ) as [string, RequestInit];
    expect(url).toBe(`${window.location.origin}/api/users`);
    expect(new Headers(init.headers).get('X-Trace')).toBe('123');
  });

  it('does not attach a body to a GET request', async () => {
    const fetchMock = stubFetch({ live: { body: 'ok' } });
    await renderWidget(JSON.stringify({ httpRequest: { method: 'GET', path: '/api/users', body: 'ignored' } }));

    fireEvent.click(sendButton());
    await waitFor(() => expect(screen.getByTestId('try-it-result')).toBeInTheDocument());
    const [, init] = fetchMock.mock.calls.find(
      ([u]) => !String(u).endsWith('/mockserver/status'),
    ) as [string, RequestInit];
    expect(init.body).toBeUndefined();
  });

  it('reports an empty response body explicitly', async () => {
    stubFetch({ live: { status: 204, statusText: 'No Content', body: '' } });
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users' } }));

    fireEvent.click(sendButton());
    await waitFor(() => expect(screen.getByTestId('try-it-result')).toBeInTheDocument());
    expect(screen.getByText('(empty response body)')).toBeInTheDocument();
  });
});

describe('LiveResponseWidget cross-origin / failure handling', () => {
  const otherOriginParams = { host: '127.0.0.1', port: '9999', secure: false };

  it('warns up-front when the target is a different origin from the dashboard', async () => {
    stubFetch({});
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users' } }), otherOriginParams);

    const warning = await screen.findByTestId('try-it-cors-warning');
    expect(warning.textContent).toContain('http://127.0.0.1:9999');
    expect(warning.textContent).toContain('CORS');
    expect(warning.textContent).toContain('enableCORSForAllResponses');
  });

  it('explains a cross-origin fetch rejection as a CORS block, not a bare "Failed to fetch"', async () => {
    stubFetch({ liveError: new TypeError('Failed to fetch') });
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users' } }), otherOriginParams);

    fireEvent.click(sendButton());
    const failure = await screen.findByTestId('try-it-failure');
    expect(failure.textContent).toContain('blocked');
    expect(failure.textContent).toContain('cross-origin');
    expect(failure.textContent).toContain('enableCORSForAllResponses');
    // The raw browser message is kept as supporting detail, never as the whole story.
    expect(failure.textContent).toContain('Failed to fetch');
    expect(failure.textContent!.trim()).not.toBe('Failed to fetch');
  });

  it('reports a same-origin failure as unreachable without blaming CORS', async () => {
    stubFetch({ liveError: new TypeError('Failed to fetch') });
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users' } }));

    fireEvent.click(sendButton());
    const failure = await screen.findByTestId('try-it-failure');
    expect(failure.textContent).toContain('Could not reach');
    expect(failure.textContent).not.toContain('CORS');
  });

  it('rejects an invalid request header before firing anything', async () => {
    const fetchMock = stubFetch({});
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users' } }));

    fireEvent.change(input('Live request headers'), { target: { value: 'Bad Header Name: x' } });
    fireEvent.click(sendButton());

    const failure = await screen.findByTestId('try-it-failure');
    expect(failure.textContent).toContain('Invalid request header');
    expect(fetchMock.mock.calls.some(([u]) => !String(u).endsWith('/mockserver/status'))).toBe(false);
  });

  it('degrades with a note when the bound ports cannot be read', async () => {
    stubFetch({ statusFails: true });
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users' } }));

    await waitFor(() =>
      expect(screen.getByText(/Could not read the server’s bound ports/)).toBeInTheDocument(),
    );
    // The dashboard's own origin remains a usable target, so sending stays available.
    expect(sendButton()).toBeEnabled();
  });

  it('refuses to send a forbidden header the user typed by hand', async () => {
    const fetchMock = stubFetch({});
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users' } }));

    fireEvent.change(input('Live request headers'), { target: { value: 'Host: example.com' } });
    fireEvent.click(sendButton());

    const failure = await screen.findByTestId('try-it-failure');
    expect(failure.textContent).toContain('will not let a page set Host');
    expect(fetchMock.mock.calls.some(([u]) => !String(u).endsWith('/mockserver/status'))).toBe(false);
  });

  it('offers other bound ports as one-click targets', async () => {
    stubFetch({ ports: [Number(sameOriginParams.port) || 80, 1090] });
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users' } }));

    const chip = await screen.findByText('1090');
    fireEvent.click(chip);
    expect((input('Target base URL') as HTMLInputElement).value).toBe(
      `http://${sameOriginParams.host}:1090`,
    );
    expect(await screen.findByTestId('try-it-cors-warning')).toBeInTheDocument();
  });
});

describe('LiveResponseWidget re-derivation when the matcher changes', () => {
  it('freezes the request and offers a re-derive rather than contradicting itself', async () => {
    stubFetch({});
    const { rerender } = render(widget(JSON.stringify({ httpRequest: { path: '/api/users' } })));
    await act(async () => {});

    expect(input('Live request path').value).toBe('/api/users');
    expect(screen.queryByTestId('try-it-stale')).toBeNull();

    // The composer keeps editing the draft while the panel is open.
    rerender(widget(JSON.stringify({ httpRequest: { path: '/api/.*' } })));
    await act(async () => {});

    // The already-derived request is untouched and still sendable — the notes do
    // not describe a field the user cannot see.
    expect(input('Live request path').value).toBe('/api/users');
    expect(sendButton()).toBeEnabled();
    expect(screen.queryByTestId('try-it-derivation-notes')).toBeNull();
    expect(screen.getByTestId('try-it-stale')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /Re-derive/i }));

    expect(input('Live request path').value).toBe('');
    expect(sendButton()).toBeDisabled();
    expect(screen.getByTestId('try-it-derivation-notes').textContent).toContain('/api/.*');
    expect(screen.queryByTestId('try-it-stale')).toBeNull();
  });

  it('does not nag when only the response action changed', async () => {
    stubFetch({});
    const { rerender } = render(
      widget(JSON.stringify({ httpRequest: { path: '/api/users' }, httpResponse: { statusCode: 200 } })),
    );
    await act(async () => {});

    rerender(
      widget(JSON.stringify({ httpRequest: { path: '/api/users' }, httpResponse: { statusCode: 404 } })),
    );
    await act(async () => {});

    expect(screen.queryByTestId('try-it-stale')).toBeNull();
  });

  it('preserves user edits while the matcher is unchanged', async () => {
    stubFetch({});
    const json = JSON.stringify({ httpRequest: { path: '/api/users' } });
    const { rerender } = render(widget(json));
    await act(async () => {});

    fireEvent.change(input('Live request path'), { target: { value: '/edited' } });
    rerender(widget(json));
    await act(async () => {});

    expect(input('Live request path').value).toBe('/edited');
  });
});

describe('LiveResponseWidget in-flight lifecycle', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('gives up after the request timeout with a timeout message, not a CORS one', async () => {
    vi.useFakeTimers();
    stubFetch({ liveHangs: true });
    render(widget(JSON.stringify({ httpRequest: { path: '/api/users' } })));
    await act(async () => {});

    fireEvent.click(sendButton());
    expect(screen.getByRole('button', { name: /Sending…/i })).toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(15_001);
    });

    const failure = screen.getByTestId('try-it-failure');
    expect(failure.textContent).toContain('No response within 15s');
    expect(failure.textContent).not.toContain('CORS');
    expect(screen.queryByTestId('try-it-result')).toBeNull();
  });

  it('abandons an in-flight send on unmount without touching state afterwards', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    stubFetch({ liveHangs: true });
    const { unmount } = render(widget(JSON.stringify({ httpRequest: { path: '/api/users' } })));
    await act(async () => {});

    fireEvent.click(sendButton());
    unmount();
    // Let the abort rejection propagate through the send's catch/finally.
    await act(async () => {});

    expect(
      consoleError.mock.calls.some((args) => String(args[0]).includes('not wrapped in act')),
    ).toBe(false);
    consoleError.mockRestore();
  });
});

describe('LiveResponseWidget oversize responses', () => {
  it('refuses to read a body whose declared size exceeds the cap', async () => {
    const text = vi.fn(async () => 'x'.repeat(10));
    stubFetch({ live: { headers: { 'content-length': String(6 * 1024 * 1024) }, text } });
    await renderWidget(JSON.stringify({ httpRequest: { path: '/api/users' } }));

    fireEvent.click(sendButton());
    await waitFor(() => expect(screen.getByTestId('try-it-result')).toBeInTheDocument());

    expect(text).not.toHaveBeenCalled();
    expect(screen.getByText(/too large to display/)).toBeInTheDocument();
  });
});
