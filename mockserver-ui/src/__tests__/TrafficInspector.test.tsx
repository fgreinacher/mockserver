import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import TrafficInspector, {
  maskSecretValue,
  maskSecretsInValue,
  mcpErrorInfo,
} from '../components/TrafficInspector';
import type { McpParsed } from '../lib/llmTraffic';
import { useDashboardStore } from '../store';
import * as trafficLib from '../lib/traffic';

function renderTrafficInspector() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <TrafficInspector />
    </ThemeProvider>,
  );
}

describe('TrafficInspector — ScriptedTurnsPanel wiring', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
    });
  });

  it('shows Scripted Turns tab when LLM request is selected and conversation expectations exist', async () => {
    const user = userEvent.setup();

    // Set up a proxied LLM request
    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-1',
          value: {
            httpRequest: {
              method: 'POST',
              path: '/v1/messages',
              headers: [{ name: 'host', values: ['api.anthropic.com'] }],
              body: {
                type: 'JSON',
                json: JSON.stringify({
                  model: 'claude-sonnet-4-20250514',
                  messages: [{ role: 'user', content: 'Hello' }],
                }),
              },
            },
            httpResponse: {
              statusCode: 200,
              body: {
                type: 'JSON',
                json: JSON.stringify({
                  content: [{ type: 'text', text: 'Hi there!' }],
                  usage: { input_tokens: 5, output_tokens: 3 },
                }),
              },
            },
          },
        },
      ],
      // Two-turn conversation expectations sharing a scenarioName
      activeExpectations: [
        {
          key: 'exp-turn0',
          value: {
            // scenarioName/scenarioState/newScenarioState are top-level Expectation fields
            scenarioName: 'weather_conversation',
            scenarioState: 'Started',
            newScenarioState: 'turn_1',
            httpLlmResponse: {
              provider: 'ANTHROPIC',
              model: 'claude-sonnet-4-20250514',
              conversationPredicates: {
                turnIndex: 0,
              },
              completion: {
                text: '',
                toolCalls: [{ name: 'get_weather', arguments: '{"city":"London"}' }],
                stopReason: 'tool_use',
              },
            },
          },
        },
        {
          key: 'exp-turn1',
          value: {
            scenarioName: 'weather_conversation',
            scenarioState: 'turn_1',
            newScenarioState: '__done',
            httpLlmResponse: {
              provider: 'ANTHROPIC',
              model: 'claude-sonnet-4-20250514',
              conversationPredicates: {
                turnIndex: 1,
                containsToolResultFor: 'get_weather',
              },
              completion: {
                text: 'The weather in London is sunny, 22C.',
                stopReason: 'end_turn',
              },
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // Click on the proxied request row to select it
    // The path shows as "api.anthropic.com/v1/messages" in a single text node
    const row = screen.getByText(/\/v1\/messages/);
    await user.click(row);

    // The Scripted Turns tab should be visible
    expect(screen.getByRole('tab', { name: 'Scripted Turns' })).toBeInTheDocument();

    // Click on the Scripted Turns tab
    await user.click(screen.getByRole('tab', { name: 'Scripted Turns' }));

    // The scripted turns content should be visible
    expect(screen.getByText('Scripted Conversation Turns')).toBeInTheDocument();
    expect(screen.getByText('Turn 0')).toBeInTheDocument();
    expect(screen.getByText('Turn 1')).toBeInTheDocument();
    expect(screen.getByText('Started')).toBeInTheDocument();
    expect(screen.getByText('__done')).toBeInTheDocument();
  });

  it('does not show Scripted Turns tab when no conversation expectations exist', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-1',
          value: {
            httpRequest: {
              method: 'POST',
              path: '/v1/messages',
              body: {
                type: 'JSON',
                json: JSON.stringify({
                  model: 'claude-sonnet-4-20250514',
                  messages: [{ role: 'user', content: 'Hello' }],
                }),
              },
            },
            httpResponse: {
              statusCode: 200,
              body: {
                type: 'JSON',
                json: JSON.stringify({
                  content: [{ type: 'text', text: 'Hi!' }],
                  usage: { input_tokens: 3, output_tokens: 1 },
                }),
              },
            },
          },
        },
      ],
      activeExpectations: [],
    });

    renderTrafficInspector();

    const row = screen.getByText(/\/v1\/messages/);
    await user.click(row);

    // No Scripted Turns tab when no conversation expectations
    expect(screen.queryByRole('tab', { name: 'Scripted Turns' })).not.toBeInTheDocument();
  });
});

describe('TrafficInspector — per-request timing display', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
    });
  });

  it('shows timing chip in master list when timing data is present on a proxied request', () => {
    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-timed',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/data',
              headers: [{ name: 'host', values: ['example.com'] }],
            },
            httpResponse: {
              statusCode: 200,
              timing: {
                connectionTimeInMillis: 12,
                timeToFirstByteInMillis: 85,
                totalTimeInMillis: 142,
                requestStartedMillis: 1700000000000,
                connectionEstablishedMillis: 1700000000012,
                responseReceivedMillis: 1700000000142,
              },
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // The compact timing label should be visible in the master list row
    expect(screen.getByText('142ms')).toBeInTheDocument();
  });

  it('does not show timing chip when timing data is absent (mocked response)', () => {
    useDashboardStore.setState({
      recordedRequests: [
        {
          key: 'req-mocked',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/mocked',
            },
            httpResponse: {
              statusCode: 200,
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // No timing chip should be present
    expect(screen.queryByText(/\d+ms/)).not.toBeInTheDocument();
  });

  it('shows timing waterfall in detail pane when a timed request is selected', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-timed-detail',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/data',
              headers: [{ name: 'host', values: ['example.com'] }],
            },
            httpResponse: {
              statusCode: 200,
              timing: {
                connectionTimeInMillis: 15,
                timeToFirstByteInMillis: 90,
                totalTimeInMillis: 200,
                requestStartedMillis: 1700000000000,
                connectionEstablishedMillis: 1700000000015,
                responseReceivedMillis: 1700000000200,
              },
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // Click on the row to select it
    const row = screen.getByText(/\/api\/data/);
    await user.click(row);

    // The timing waterfall should appear in the detail pane
    expect(screen.getByTestId('timing-waterfall')).toBeInTheDocument();
    // Timing breakdown chips should be visible
    expect(screen.getByText('connect 15ms')).toBeInTheDocument();
    expect(screen.getByText('TTFB 90ms')).toBeInTheDocument();
    expect(screen.getByText('total 200ms')).toBeInTheDocument();
    // Waterfall bar should be present
    expect(screen.getByTestId('timing-bar')).toBeInTheDocument();
  });

  it('does not show timing waterfall for requests without timing data', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-no-timing',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/data',
            },
            httpResponse: {
              statusCode: 200,
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    const row = screen.getByText(/\/api\/data/);
    await user.click(row);

    // No timing waterfall should be shown
    expect(screen.queryByTestId('timing-waterfall')).not.toBeInTheDocument();
  });

  it('shows timing alongside LLM usage detail for proxied LLM traffic', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-llm-timed',
          value: {
            httpRequest: {
              method: 'POST',
              path: '/v1/messages',
              headers: [{ name: 'host', values: ['api.anthropic.com'] }],
              body: {
                type: 'JSON',
                json: JSON.stringify({
                  model: 'claude-sonnet-4-20250514',
                  messages: [{ role: 'user', content: 'Hello' }],
                }),
              },
            },
            httpResponse: {
              statusCode: 200,
              body: {
                type: 'JSON',
                json: JSON.stringify({
                  content: [{ type: 'text', text: 'Hi!' }],
                  usage: { input_tokens: 5, output_tokens: 2 },
                }),
              },
              timing: {
                connectionTimeInMillis: 8,
                timeToFirstByteInMillis: 1200,
                totalTimeInMillis: 1500,
                requestStartedMillis: 1700000000000,
                connectionEstablishedMillis: 1700000000008,
                responseReceivedMillis: 1700000001500,
              },
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // Master list should show both the timing chip and the token summary
    expect(screen.getByText('1500ms')).toBeInTheDocument();
    expect(screen.getByText('5 in / 2 out')).toBeInTheDocument();

    // Click to open detail pane
    const row = screen.getByText(/\/v1\/messages/);
    await user.click(row);

    // Both LLM usage and timing waterfall should be visible
    expect(screen.getByTestId('timing-waterfall')).toBeInTheDocument();
    expect(screen.getByText('connect 8ms')).toBeInTheDocument();
    expect(screen.getByText('TTFB 1200ms')).toBeInTheDocument();
    expect(screen.getByText('total 1500ms')).toBeInTheDocument();
  });
});

describe('TrafficInspector — compare two requests (diff)', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [
        {
          key: 'req-a',
          value: {
            httpRequest: { method: 'GET', path: '/api/users', headers: [{ name: 'host', values: ['example.com'] }] },
            httpResponse: { statusCode: 200 },
          },
        },
        {
          key: 'req-b',
          value: {
            httpRequest: { method: 'POST', path: '/api/users', headers: [{ name: 'host', values: ['example.com'] }] },
            httpResponse: { statusCode: 201 },
          },
        },
      ],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
    });
  });
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('lets the user pick two requests and diffs them via PUT /mockserver/diff', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        diffCount: 1,
        identical: false,
        diffs: [{ field: 'method', expectedValue: 'GET', actualValue: 'POST', diffType: 'CHANGED' }],
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    renderTrafficInspector();

    // No compare checkboxes until compare mode is enabled.
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Compare requests/i }));

    const checkboxes = screen.getAllByRole('checkbox');
    expect(checkboxes).toHaveLength(2);

    // Diff button disabled until exactly two are picked.
    const diffButton = screen.getByRole('button', { name: /Diff \(/ });
    expect(diffButton).toBeDisabled();

    await user.click(checkboxes[0]!);
    expect(screen.getByRole('button', { name: /Diff \(1\/2\)/ })).toBeDisabled();
    await user.click(checkboxes[1]!);

    const ready = screen.getByRole('button', { name: /Diff \(2\/2\)/ });
    expect(ready).toBeEnabled();
    await user.click(ready);

    // Dialog opens pre-populated; Compare submits the two picked requests to the diff endpoint.
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Compare' }));

    expect(await within(dialog).findByText('Request Diff')).toBeInTheDocument();

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toMatch(/\/mockserver\/diff$/);
    expect((init as RequestInit).method).toBe('PUT');
    const body = JSON.parse((init as RequestInit).body as string);
    // First pick (req-a) maps to expected, second pick (req-b) to actual, sending the httpRequest definitions.
    expect(body.expected).toMatchObject({ method: 'GET', path: '/api/users' });
    expect(body.actual).toMatchObject({ method: 'POST', path: '/api/users' });
  });

  it('caps the selection at two requests', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      recordedRequests: [
        { key: 'r1', value: { httpRequest: { method: 'GET', path: '/a' }, httpResponse: { statusCode: 200 } } },
        { key: 'r2', value: { httpRequest: { method: 'GET', path: '/b' }, httpResponse: { statusCode: 200 } } },
        { key: 'r3', value: { httpRequest: { method: 'GET', path: '/c' }, httpResponse: { statusCode: 200 } } },
      ],
    });

    renderTrafficInspector();
    await user.click(screen.getByRole('button', { name: /Compare requests/i }));

    const checkboxes = screen.getAllByRole('checkbox');
    await user.click(checkboxes[0]!);
    await user.click(checkboxes[1]!);

    // Once two are picked, the remaining unchecked checkbox is disabled.
    expect(checkboxes[2]!).toBeDisabled();
    // Already-checked ones remain interactive so the user can deselect.
    expect(checkboxes[0]!).toBeEnabled();
  });
});

describe('TrafficInspector — Capture as mock for standard HTTP (WS5.2)', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('shows the "Capture as mock" button when a standard (non-LLM) HTTP request is selected', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-plain-http',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/widgets',
              headers: [{ name: 'host', values: ['example.com'] }],
            },
            httpResponse: {
              statusCode: 200,
              body: { type: 'JSON', json: '{"widgets":[]}' },
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // Select the standard HTTP row
    const row = screen.getByText(/\/api\/widgets/);
    await user.click(row);

    // The capture button must be offered for plain HTTP traffic, not just LLM traffic.
    expect(
      screen.getByRole('button', { name: /Capture as mock/i }),
    ).toBeInTheDocument();
  });

  it('opens the capture dialog and registers a generic expectation via PUT /mockserver/expectation', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-capture-http',
          value: {
            httpRequest: {
              method: 'POST',
              path: '/api/orders',
              headers: [{ name: 'host', values: ['example.com'] }],
              body: { type: 'JSON', json: '{"sku":"A1"}' },
            },
            httpResponse: {
              statusCode: 201,
              body: { type: 'JSON', json: '{"id":7}' },
            },
          },
        },
      ],
    });

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    renderTrafficInspector();

    const row = screen.getByText(/\/api\/orders/);
    await user.click(row);

    await user.click(screen.getByRole('button', { name: /Capture as mock/i }));

    // Dialog opens in generic-HTTP mode pre-populated from the captured request.
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Capture as Mock')).toBeInTheDocument();

    // Register the expectation.
    await user.click(within(dialog).getByRole('button', { name: 'Register' }));

    // The generic path uses PUT /mockserver/expectation with the captured method/path/status/body.
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/mockserver/expectation'),
      expect.objectContaining({ method: 'PUT' }),
    );
    const [, init] = fetchMock.mock.calls[0]!;
    const body = JSON.parse((init as RequestInit).body as string);
    expect(body.httpRequest).toMatchObject({ method: 'POST', path: '/api/orders' });
    expect(body.httpResponse).toMatchObject({ statusCode: 201 });
    expect(body.httpResponse.body).toBe('{"id":7}');
  });
});

describe('TrafficInspector — Replay button', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows Replay button in detail pane for a selected generic request', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-replay',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/test',
              headers: [{ name: 'host', values: ['example.com'] }],
            },
            httpResponse: {
              statusCode: 200,
              body: { type: 'STRING', string: 'original response' },
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // Click on the row to select it
    const row = screen.getByText(/\/api\/test/);
    await user.click(row);

    // The Replay button should be visible in the detail pane
    expect(screen.getByRole('button', { name: /Replay/i })).toBeInTheDocument();
  });

  it('opens replay dialog and calls PUT /mockserver/replay on click', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-replay-2',
          value: {
            httpRequest: {
              method: 'POST',
              path: '/api/submit',
              headers: [{ name: 'host', values: ['example.com'] }],
              body: { type: 'JSON', json: '{"data":"test"}' },
            },
            httpResponse: {
              statusCode: 200,
            },
          },
        },
      ],
    });

    // Mock fetch to intercept the replay call
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ statusCode: 200, body: 'replayed OK' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    renderTrafficInspector();

    // Select the request
    const row = screen.getByText(/\/api\/submit/);
    await user.click(row);

    // Click the Replay button in the detail pane
    const replayBtn = screen.getByRole('button', { name: /Replay/i });
    await user.click(replayBtn);

    // The replay dialog should open
    expect(screen.getByText('Replay Request')).toBeInTheDocument();

    // Click the Replay button inside the dialog
    const dialogReplayBtn = within(screen.getByRole('dialog')).getByRole('button', { name: /Replay/i });
    await user.click(dialogReplayBtn);

    // Verify fetch was called with the correct URL and method
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/mockserver/replay'),
      expect.objectContaining({
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    // The response should be displayed
    expect(await screen.findByText('Upstream Response')).toBeInTheDocument();
  });

  it('shows error alert and clears loading spinner when replay returns a server error', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-replay-err',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/failing',
              headers: [{ name: 'host', values: ['example.com'] }],
            },
            httpResponse: { statusCode: 200 },
          },
        },
      ],
    });

    // Stub fetch to return a 503 error response
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: false,
      status: 503,
      text: async () => 'Service Unavailable',
    } as Response);

    renderTrafficInspector();

    // Select the request
    const row = screen.getByText(/\/api\/failing/);
    await user.click(row);

    // Open the replay dialog
    const replayBtn = screen.getByRole('button', { name: /Replay/i });
    await user.click(replayBtn);

    const dialog = screen.getByRole('dialog');

    // Click the Replay button inside the dialog
    const dialogReplayBtn = within(dialog).getByRole('button', { name: /Replay/i });
    await user.click(dialogReplayBtn);

    // An error Alert (severity="error") should appear. The replay failure is now
    // routed through humanizeError, so a 503 surfaces the friendly internal-error
    // message (the raw "503: Service Unavailable" text is kept for a Details pane,
    // not shown inline by this Alert).
    const errorAlert = await within(dialog).findByRole('alert');
    expect(errorAlert).toBeInTheDocument();
    expect(errorAlert).toHaveTextContent(/internal error/i);

    // The loading spinner should be gone (dialog replay button should be re-enabled)
    expect(within(dialog).getByRole('button', { name: /Replay/i })).toBeEnabled();
    expect(within(dialog).queryByRole('progressbar')).not.toBeInTheDocument();
  });
});

/**
 * Master/detail resize-divider tests.
 *
 * The vertical drag handle between the master list and the detail pane appears
 * ONLY in the side-by-side case (not stacked, an entry selected, not comparing).
 * jsdom has no layout, so we assert handle presence/absence and accessibility,
 * not pixel widths — the default master width renders without measurement.
 */
function stubMatchMedia(matches: boolean) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}

const simpleRequest = {
  key: 'req-resize',
  value: {
    httpRequest: {
      method: 'GET',
      path: '/api/widgets',
      headers: [{ name: 'host', values: ['example.com'] }],
    },
    httpResponse: { statusCode: 200 },
  },
};

describe('TrafficInspector — search filtering', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [
        {
          key: 'req-users',
          value: {
            httpRequest: { method: 'GET', path: '/api/users', headers: [{ name: 'host', values: ['example.com'] }] },
            httpResponse: { statusCode: 200 },
          },
        },
        {
          key: 'req-orders',
          value: {
            httpRequest: { method: 'POST', path: '/api/orders', headers: [{ name: 'host', values: ['shop.example.com'] }] },
            httpResponse: { statusCode: 201, body: { type: 'JSON', json: '{"sku":"WIDGET-42"}' } },
          },
        },
      ],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
    });
  });

  it('filters rows by a field match (path) as the user types', async () => {
    const user = userEvent.setup();
    renderTrafficInspector();

    // Both rows visible initially.
    expect(screen.getByText(/\/api\/users/)).toBeInTheDocument();
    expect(screen.getByText(/\/api\/orders/)).toBeInTheDocument();

    const search = screen.getByPlaceholderText('Search...');
    await user.type(search, 'orders');

    expect(screen.queryByText(/\/api\/users/)).not.toBeInTheDocument();
    expect(screen.getByText(/\/api\/orders/)).toBeInTheDocument();
  });

  it('filters rows by a full-text body match (the cached fallback path)', async () => {
    const user = userEvent.setup();
    renderTrafficInspector();

    const search = screen.getByPlaceholderText('Search...');
    // "WIDGET-42" only appears inside the response body, exercising the cached
    // JSON.stringify fallback rather than the field-level match.
    await user.type(search, 'widget-42');

    expect(screen.getByText(/\/api\/orders/)).toBeInTheDocument();
    expect(screen.queryByText(/\/api\/users/)).not.toBeInTheDocument();
  });

  it('shows the empty-state message when nothing matches', async () => {
    const user = userEvent.setup();
    renderTrafficInspector();

    const search = screen.getByPlaceholderText('Search...');
    await user.type(search, 'nonexistent-term-xyz');

    expect(screen.getByText('No matching requests')).toBeInTheDocument();
  });
});

describe('TrafficInspector — master/detail resize divider', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
    });
  });

  afterEach(() => {
    // @ts-expect-error allow deleting the optional stub
    delete window.matchMedia;
  });

  it('renders the resize divider when an entry is selected side-by-side (desktop)', () => {
    useDashboardStore.setState({
      recordedRequests: [simpleRequest],
      selectedTrafficKey: 'req-resize',
    });
    renderTrafficInspector();
    const handle = screen.getByTestId('traffic-master-resizer');
    expect(handle).toBeInTheDocument();
    expect(handle).toHaveAttribute('role', 'separator');
    expect(handle).toHaveAttribute('aria-orientation', 'vertical');
  });

  it('does NOT render the divider when nothing is selected', () => {
    useDashboardStore.setState({
      recordedRequests: [simpleRequest],
      selectedTrafficKey: null,
    });
    renderTrafficInspector();
    expect(screen.queryByTestId('traffic-master-resizer')).not.toBeInTheDocument();
  });

  it('does NOT render the divider on a stacked (small-screen) viewport even with a selection', () => {
    stubMatchMedia(true);
    useDashboardStore.setState({
      recordedRequests: [simpleRequest],
      selectedTrafficKey: 'req-resize',
    });
    renderTrafficInspector();
    expect(screen.queryByTestId('traffic-master-resizer')).not.toBeInTheDocument();
  });
});

describe('TrafficInspector — secret-header masking (pure helper)', () => {
  it('preserves the auth scheme and keeps the last 4 chars of a Bearer token', () => {
    expect(maskSecretValue('Bearer sk-secret-abcd1234')).toBe('Bearer ••••1234');
    expect(maskSecretValue('Basic dXNlcjpXXYY')).toBe('Basic ••••XXYY');
  });

  it('masks a raw token entirely when shorter than 5 chars', () => {
    expect(maskSecretValue('abcd')).toBe('••••');
    expect(maskSecretValue('')).toBe('');
  });

  it('masks an api-key value without a scheme, keeping the last 4 chars', () => {
    expect(maskSecretValue('sk-ant-api03-XYZ9876')).toBe('••••9876');
  });

  it('masks secret headers in array form on httpRequest, leaving non-secrets intact', () => {
    const value = {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        headers: [
          { name: 'host', values: ['api.anthropic.com'] },
          { name: 'authorization', values: ['Bearer sk-live-TOPSECRET4321'] },
          { name: 'x-api-key', values: ['anthropic-key-ABCDEFGH'] },
        ],
      },
    };
    const masked = maskSecretsInValue(value) as typeof value;
    const headers = masked.httpRequest.headers;
    expect(headers[0]).toEqual({ name: 'host', values: ['api.anthropic.com'] });
    expect(headers[1]!.values).toEqual(['Bearer ••••4321']);
    expect(headers[2]!.values).toEqual(['••••EFGH']);
    // The original object must not be mutated.
    expect(value.httpRequest.headers[1]!.values[0]).toBe('Bearer sk-live-TOPSECRET4321');
  });

  it('masks secret headers in object form on httpResponse (e.g. Set-Cookie)', () => {
    const value = {
      httpResponse: {
        statusCode: 200,
        headers: { 'Set-Cookie': ['session=SUPERSECRETVALUE'], 'content-type': ['application/json'] },
      },
    };
    const masked = maskSecretsInValue(value) as typeof value;
    expect(masked.httpResponse.headers['Set-Cookie']).toEqual(['••••ALUE']);
    expect(masked.httpResponse.headers['content-type']).toEqual(['application/json']);
  });

  it('returns the original reference when there is nothing to mask', () => {
    const value = { httpRequest: { method: 'GET', headers: [{ name: 'host', values: ['x'] }] } };
    expect(maskSecretsInValue(value)).toBe(value);
  });
});

describe('TrafficInspector — masked secrets flow into the Diff/Compare view', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [
        {
          key: 'req-secret',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/secure',
              headers: [
                { name: 'host', values: ['example.com'] },
                { name: 'authorization', values: ['Bearer sk-secret-abcd1234'] },
              ],
            },
            httpResponse: { statusCode: 200 },
          },
        },
        {
          key: 'req-plain',
          value: {
            httpRequest: { method: 'GET', path: '/api/plain', headers: [{ name: 'host', values: ['example.com'] }] },
            httpResponse: { statusCode: 200 },
          },
        },
      ],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
    });
  });
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('seeds the diff editor with the MASKED Authorization value, not the raw token', async () => {
    const user = userEvent.setup();
    renderTrafficInspector();

    await user.click(screen.getByRole('button', { name: /Compare requests/i }));
    const checkboxes = screen.getAllByRole('checkbox');
    await user.click(checkboxes[0]!); // first row = /api/plain (newest at top) — order not important
    await user.click(checkboxes[1]!);
    await user.click(screen.getByRole('button', { name: /Diff \(2\/2\)/ }));

    const dialog = await screen.findByRole('dialog');
    // The masked token must appear in one of the seeded editors; the raw token never does.
    const expected = within(dialog).getByLabelText('Expected request (JSON)') as HTMLTextAreaElement;
    const actual = within(dialog).getByLabelText('Actual request (JSON)') as HTMLTextAreaElement;
    const combined = `${expected.value}\n${actual.value}`;
    expect(combined).toContain('••••1234');
    expect(combined).not.toContain('sk-secret-abcd1234');
  });
});

describe('TrafficInspector — search indexes decoded BINARY body text', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
    });
  });

  it('matches a word that only exists in the DECODED base64 body', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      recordedRequests: [
        {
          key: 'req-binary',
          value: {
            httpRequest: { method: 'POST', path: '/api/binary', headers: [{ name: 'host', values: ['example.com'] }] },
            httpResponse: {
              statusCode: 200,
              // base64 of {"prompt":"PINEAPPLE-TOKEN"} — searching the base64 string would not match.
              body: { type: 'BINARY', base64Bytes: btoa('{"prompt":"PINEAPPLE-TOKEN"}') },
            },
          },
        },
        {
          key: 'req-other',
          value: {
            httpRequest: { method: 'GET', path: '/api/other', headers: [{ name: 'host', values: ['example.com'] }] },
            httpResponse: { statusCode: 200 },
          },
        },
      ],
    });

    renderTrafficInspector();
    const search = screen.getByPlaceholderText('Search...');
    await user.type(search, 'pineapple-token');

    expect(screen.getByText(/\/api\/binary/)).toBeInTheDocument();
    expect(screen.queryByText(/\/api\/other/)).not.toBeInTheDocument();
  });
});

describe('TrafficInspector — MCP JSON-RPC error classification (pure helper)', () => {
  const base: McpParsed = {
    kind: 'mcp', method: 'tools/call', id: 1, params: {}, result: null, error: null, isResponse: true,
  };

  it('flags a JSON-RPC error object and exposes its numeric code + message', () => {
    const info = mcpErrorInfo({ ...base, error: { code: -32601, message: 'Method not found' } }, 200);
    expect(info.isError).toBe(true);
    expect(info.code).toBe(-32601);
    expect(info.message).toBe('Method not found');
  });

  it('flags a non-2xx HTTP status even without a JSON-RPC error object', () => {
    expect(mcpErrorInfo(base, 500).isError).toBe(true);
    expect(mcpErrorInfo(base, 400).isError).toBe(true);
  });

  it('treats a clean 2xx result as not an error', () => {
    const info = mcpErrorInfo({ ...base, result: { ok: true } }, 200);
    expect(info.isError).toBe(false);
    expect(info.code).toBeNull();
  });
});

describe('TrafficInspector — bulk select + clear', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [
        { key: 'r1', value: { httpRequest: { method: 'GET', path: '/a' }, httpResponse: { statusCode: 200 } } },
        { key: 'r2', value: { httpRequest: { method: 'GET', path: '/b' }, httpResponse: { statusCode: 200 } } },
        { key: 'r3', value: { httpRequest: { method: 'GET', path: '/c' }, httpResponse: { statusCode: 200 } } },
      ],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficKey: null,
      notification: null,
    });
  });
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it('reveals uncapped select checkboxes and a Clear button in select mode', async () => {
    const user = userEvent.setup();
    renderTrafficInspector();

    // No checkboxes until a mode is enabled.
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Select requests/i }));

    // Per-row checkboxes (3) + the header "select all" checkbox = 4, none capped/disabled.
    const rowCheckboxes = screen.getAllByRole('checkbox').filter(
      (c) => c.getAttribute('aria-label')?.startsWith('Select request'),
    );
    expect(rowCheckboxes).toHaveLength(3);
    await user.click(rowCheckboxes[0]!);
    await user.click(rowCheckboxes[1]!);
    await user.click(rowCheckboxes[2]!);
    // No two-item cap — all three stay enabled and checked.
    rowCheckboxes.forEach((c) => expect(c).toBeEnabled());
    expect(screen.getByRole('button', { name: /Clear \(3\)/ })).toBeEnabled();
  });

  it('bulk-clears selected requests via clearLoggedRequest and drops the rows', async () => {
    const user = userEvent.setup();
    const spy = vi.spyOn(trafficLib, 'clearLoggedRequest').mockResolvedValue(undefined);
    renderTrafficInspector();

    await user.click(screen.getByRole('button', { name: /Select requests/i }));
    await user.click(screen.getByLabelText('Select all requests'));
    await user.click(screen.getByRole('button', { name: /Clear \(3\)/ }));

    // Confirmation gate — nothing cleared until confirmed.
    expect(spy).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Clear selected' }));

    await vi.waitFor(() => expect(spy).toHaveBeenCalledTimes(3));
    // Each call sends the request definition (the httpRequest) for a selected row.
    const paths = spy.mock.calls.map((c) => (c[1] as { path: string }).path).sort();
    expect(paths).toEqual(['/a', '/b', '/c']);

    await vi.waitFor(() =>
      expect(useDashboardStore.getState().recordedRequests).toHaveLength(0),
    );
    expect(useDashboardStore.getState().notification).toMatchObject({ severity: 'success' });
  });

  it('leaving compare mode is not broken by select mode (mutually exclusive)', async () => {
    const user = userEvent.setup();
    renderTrafficInspector();

    await user.click(screen.getByRole('button', { name: /Compare requests/i }));
    expect(screen.getByRole('button', { name: /Diff \(/ })).toBeInTheDocument();

    // Entering select mode exits compare mode (Diff button gone).
    await user.click(screen.getByRole('button', { name: /Select requests/i }));
    expect(screen.queryByRole('button', { name: /Diff \(/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Clear \(0\)/ })).toBeDisabled();
  });
});
