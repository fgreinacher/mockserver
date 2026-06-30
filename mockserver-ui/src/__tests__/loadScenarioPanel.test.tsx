import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import LoadScenarioPanel from '../components/LoadScenarioPanel';

const params = { host: '127.0.0.1', port: '1080', secure: false };

afterEach(() => {
  vi.restoreAllMocks();
});

/**
 * Pick an option from a MUI (non-native) Select. fireEvent.change can't drive these — the visible
 * combobox is a div, so open it (mouseDown) and click the option in the portal-rendered listbox.
 */
function selectOption(combobox: HTMLElement, optionName: string | RegExp) {
  fireEvent.mouseDown(combobox);
  const option = within(screen.getByRole('listbox')).getByRole('option', { name: optionName });
  fireEvent.click(option);
}

/** A fetch mock that returns `none` for GET and records PUT/DELETE calls. */
function stubFetch(getBody: unknown, status = 200) {
  const calls: Array<{ url: string; method: string; body?: unknown }> = [];
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ url: String(url), method, body });
    if (method === 'GET') {
      return { ok: status === 200, status, json: async () => getBody } as unknown as Response;
    }
    return { ok: true, status: 200, json: async () => ({ status: method === 'DELETE' ? 'stopped' : 'started' }) } as unknown as Response;
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls, fetchMock };
}

/**
 * A fetch mock that distinguishes the registry-list GET (returns `{scenarios}`) from
 * the legacy status GET (returns `{state:'none'}`). Both hit `/mockserver/loadScenario`,
 * so the stub returns the registry shape for plain GETs; PUT/DELETE are recorded.
 */
function stubRegistry(scenarios: unknown[]) {
  const calls: Array<{ url: string; method: string; body?: unknown }> = [];
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ url: String(url), method, body });
    if (method === 'GET') {
      return { ok: true, status: 200, json: async () => ({ state: 'none', scenarios }) } as unknown as Response;
    }
    return { ok: true, status: 200, json: async () => ({ status: 'ok' }) } as unknown as Response;
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls, fetchMock };
}

describe('LoadScenarioPanel', () => {
  it('renders the author form with VELOCITY/MUSTACHE template options', async () => {
    stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    expect(screen.getByLabelText(/Scenario name/)).toBeInTheDocument();
    // template type select offers only Velocity + Mustache
    expect(screen.getByText('Velocity')).toBeInTheDocument();
  });

  it('builds a valid PUT body with a staged profile from the form', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: 'checkout-load' } });

    // Default seeded profile is a VU ramp (stage 0) then a VU hold (stage 1). Tweak the hold to 8 VUs.
    const stage1 = screen.getByTestId('load-stage-1');
    fireEvent.change(within(stage1).getByLabelText('Virtual users (VUs)'), { target: { value: '8' } });
    fireEvent.change(within(stage1).getByLabelText('Duration (ms)'), { target: { value: '20000' } });

    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'target.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '8080' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/api/item' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));

    await waitFor(() => {
      const put = calls.find((c) => c.method === 'PUT');
      expect(put).toBeTruthy();
    });
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.url).toContain('/mockserver/loadScenario');
    expect(put.body).toMatchObject({
      name: 'checkout-load',
      templateType: 'VELOCITY',
      profile: {
        stages: [
          { type: 'VU', startVus: 1, endVus: 10, durationMillis: 30000, curve: 'LINEAR' },
          { type: 'VU', vus: 8, durationMillis: 20000 },
        ],
      },
      steps: [
        {
          request: {
            method: 'GET',
            path: '/api/item',
            socketAddress: { host: 'target.svc', port: 8080, scheme: 'HTTP' },
          },
        },
      ],
    });
    // The hold stage must NOT carry ramp-only fields.
    const stages = (put.body as { profile: { stages: Array<Record<string, unknown>> } }).profile.stages;
    expect(stages[1]).not.toHaveProperty('startVus');
    expect(stages[1]).not.toHaveProperty('curve');
    // No header rows were added — `headers` must be omitted to keep the body minimal.
    const builtStep = (put.body as { steps: Array<{ request: Record<string, unknown> }> }).steps[0]!;
    expect(builtStep.request).not.toHaveProperty('headers');
  });

  it('builds profile.stages including a RATE stage and a PAUSE stage', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: 'rate-load' } });

    // Stage 1 (the seeded hold) → make it a RATE hold of 50/s.
    const stage1 = screen.getByTestId('load-stage-1');
    selectOption(within(stage1).getByLabelText('Stage type'), 'Rate (iterations/sec)');
    fireEvent.change(within(stage1).getByLabelText('Rate (iterations/sec)'), { target: { value: '50' } });
    fireEvent.change(within(stage1).getByLabelText('Max VUs (optional)'), { target: { value: '20' } });
    fireEvent.change(within(stage1).getByLabelText('Duration (ms)'), { target: { value: '15000' } });

    // Add a third stage and make it a PAUSE.
    fireEvent.click(screen.getByRole('button', { name: /Add stage/i }));
    const stage2 = screen.getByTestId('load-stage-2');
    selectOption(within(stage2).getByLabelText('Stage type'), 'Pause (no load)');
    fireEvent.change(within(stage2).getByLabelText('Duration (ms)'), { target: { value: '5000' } });

    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'target.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '8080' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/api/item' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    const stages = (put.body as { profile: { stages: Array<Record<string, unknown>> } }).profile.stages;
    expect(stages).toHaveLength(3);
    expect(stages[0]).toMatchObject({ type: 'VU', startVus: 1, endVus: 10 });
    expect(stages[1]).toMatchObject({ type: 'RATE', rate: 50, maxVus: 20, durationMillis: 15000 });
    expect(stages[2]).toMatchObject({ type: 'PAUSE', durationMillis: 5000 });
    // The PAUSE stage carries only its duration.
    expect(stages[2]).not.toHaveProperty('vus');
    expect(stages[2]).not.toHaveProperty('rate');
  });

  it('reorders and removes stages', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: 'reorder-load' } });
    // Move the hold stage (stage 2) up so it runs first.
    fireEvent.click(screen.getByRole('button', { name: /Move stage 2 up/i }));

    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'target.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '8080' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/api/item' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    const stages = (put.body as { profile: { stages: Array<Record<string, unknown>> } }).profile.stages;
    // After moving up, the hold (vus: 10) is now first, the ramp second.
    expect(stages[0]).toMatchObject({ type: 'VU', vus: 10 });
    expect(stages[1]).toMatchObject({ type: 'VU', startVus: 1, endVus: 10 });
  });

  it('emits per-step request headers in MockServer KeyToMultiValue object-map shape', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: 'auth-load' } });
    // The seeded default profile (a VU ramp then hold) is already valid; no profile edits needed.

    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'api.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '443' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/secure' } });

    fireEvent.click(within(step0).getByRole('button', { name: /Add header/i }));
    fireEvent.change(within(step0).getByLabelText('Header name'), { target: { value: 'Authorization' } });
    fireEvent.change(within(step0).getByLabelText('Header value'), { target: { value: 'Bearer xyz' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.body).toMatchObject({
      steps: [
        {
          request: {
            path: '/secure',
            headers: { Authorization: ['Bearer xyz'] },
          },
        },
      ],
    });
  });

  it('reloads a definition that already carries request headers into the header rows on Edit', async () => {
    stubFetch({
      state: 'running',
      name: 'header-run',
      runId: 'rH',
      currentVus: 2,
      requestsSent: 5,
      succeeded: 5,
      failed: 0,
      p50Millis: 5,
      p95Millis: 9,
      p99Millis: 12,
      elapsedMillis: 1000,
      definition: {
        name: 'header-run',
        templateType: 'VELOCITY',
        profile: { stages: [{ type: 'VU', vus: 2, durationMillis: 10000 }] },
        steps: [
          {
            request: {
              method: 'GET',
              path: '/secure',
              headers: { Authorization: ['Bearer abc'], 'X-Tenant': ['acme'] },
              socketAddress: { host: 'api.svc', port: 443, scheme: 'HTTPS' },
            },
          },
        ],
      },
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-live-status')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Edit running/i }));

    await waitFor(() =>
      expect((screen.getByLabelText(/Scenario name/) as HTMLInputElement).value).toBe('header-run'),
    );
    const step0 = screen.getByTestId('load-step-0');
    const names = within(step0).getAllByLabelText('Header name') as HTMLInputElement[];
    const values = within(step0).getAllByLabelText('Header value') as HTMLInputElement[];
    expect(names.map((n) => n.value)).toEqual(['Authorization', 'X-Tenant']);
    expect(values.map((v) => v.value)).toEqual(['Bearer abc', 'acme']);
  });

  it('shows live status from a mocked running GET', async () => {
    stubFetch({
      state: 'running',
      name: 'live-run',
      runId: 'r1',
      currentVus: 4,
      requestsSent: 1000,
      succeeded: 950,
      failed: 50,
      p50Millis: 12,
      p95Millis: 88,
      p99Millis: 140,
      elapsedMillis: 5000,
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-live-status')).toBeInTheDocument());
    const live = screen.getByTestId('load-live-status');
    expect(within(live).getByText('1,000')).toBeInTheDocument(); // requests sent
    expect(within(live).getByText('5.0%')).toBeInTheDocument(); // error rate 50/1000
    expect(screen.getByRole('button', { name: /Stop/i })).toBeInTheDocument();
  });

  it('shows the active-stage readout from stageIndex/stageType/currentTarget', async () => {
    stubFetch({
      state: 'running',
      name: 'staged-run',
      runId: 'rS',
      currentVus: 12,
      stageIndex: 1,
      stageType: 'RATE',
      currentTarget: 50,
      requestsSent: 200,
      succeeded: 200,
      failed: 0,
      p50Millis: 5,
      p95Millis: 9,
      p99Millis: 12,
      elapsedMillis: 4000,
      definition: {
        name: 'staged-run',
        templateType: 'VELOCITY',
        profile: {
          stages: [
            { type: 'VU', startVus: 1, endVus: 10, durationMillis: 30000, curve: 'LINEAR' },
            { type: 'RATE', rate: 50, durationMillis: 30000 },
            { type: 'PAUSE', durationMillis: 5000 },
          ],
        },
        steps: [{ request: { method: 'GET', path: '/x', socketAddress: { host: 'h', port: 80, scheme: 'HTTP' } } }],
      },
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-live-status')).toBeInTheDocument());
    const readout = screen.getByTestId('load-stage-readout');
    expect(readout).toHaveTextContent('Stage 2/3');
    expect(readout).toHaveTextContent('RATE');
    expect(readout).toHaveTextContent('target 50/s');
  });

  it('toggles a chart series on and off', async () => {
    stubFetch({
      state: 'running', name: 'r', runId: 'r1', currentVus: 2,
      requestsSent: 10, succeeded: 10, failed: 0, p50Millis: 5, p95Millis: 9, p99Millis: 12, elapsedMillis: 1000,
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-chart')).toBeInTheDocument());

    // p99 is not in the default-visible subset.
    const p99Toggle = screen.getByRole('checkbox', { name: 'p99 ms' }) as HTMLInputElement;
    expect(p99Toggle.checked).toBe(false);
    fireEvent.click(p99Toggle);
    expect((screen.getByRole('checkbox', { name: 'p99 ms' }) as HTMLInputElement).checked).toBe(true);

    // RPS is in the default subset — turning it off unchecks it.
    const rpsToggle = screen.getByRole('checkbox', { name: 'RPS' }) as HTMLInputElement;
    expect(rpsToggle.checked).toBe(true);
    fireEvent.click(rpsToggle);
    expect((screen.getByRole('checkbox', { name: 'RPS' }) as HTMLInputElement).checked).toBe(false);
  });

  it('loads the server-echoed definition into the form when editing a run started elsewhere', async () => {
    // A run this tab never started (no in-session lastSubmitted) — the server echoes its full
    // definition so "Edit running" can still populate the author form from it.
    stubFetch({
      state: 'running',
      name: 'foreign-run',
      runId: 'r9',
      currentVus: 3,
      requestsSent: 100,
      succeeded: 100,
      failed: 0,
      p50Millis: 5,
      p95Millis: 9,
      p99Millis: 12,
      elapsedMillis: 2000,
      definition: {
        name: 'foreign-run',
        templateType: 'VELOCITY',
        profile: { stages: [{ type: 'VU', vus: 7, durationMillis: 45000 }] },
        steps: [
          { request: { method: 'GET', path: '/echoed/path', socketAddress: { host: 'echoed.svc', port: 9090, scheme: 'HTTP' } } },
        ],
      },
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-live-status')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Edit running/i }));

    // the author form is now populated from the server-provided definition, not any in-tab state
    await waitFor(() =>
      expect((screen.getByLabelText(/Scenario name/) as HTMLInputElement).value).toBe('foreign-run'),
    );
    // The single echoed VU-hold stage round-trips into one stage card with vus 7 / duration 45000.
    const stage0 = screen.getByTestId('load-stage-0');
    expect((within(stage0).getByLabelText('Virtual users (VUs)') as HTMLInputElement).value).toBe('7');
    expect((within(stage0).getByLabelText('Duration (ms)') as HTMLInputElement).value).toBe('45000');
    expect(screen.queryByTestId('load-stage-1')).toBeNull();
    const step0 = screen.getByTestId('load-step-0');
    expect((within(step0).getByLabelText('Path') as HTMLInputElement).value).toBe('/echoed/path');
    expect((within(step0).getByLabelText('Target host') as HTMLInputElement).value).toBe('echoed.svc');
    expect((within(step0).getByLabelText('Target port') as HTMLInputElement).value).toBe('9090');
  });

  it('shows the load-generation-disabled message on a 403', async () => {
    stubFetch({ error: 'load generation not enabled' }, 403);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-disabled-alert')).toBeInTheDocument());
    expect(screen.getByText(/loadGenerationEnabled=true/)).toBeInTheDocument();
    expect(screen.getByText(/MOCKSERVER_LOAD_GENERATION_ENABLED/)).toBeInTheDocument();
  });

  // --- Wave C: registry UX ---

  /** Switch to the Create / Edit sub-tab, where the create/edit form + inline codegen live. */
  function gotoAuthorTab() {
    fireEvent.click(screen.getByRole('tab', { name: 'Create / Edit' }));
  }

  /** Fill the seeded form's first step so buildScenario succeeds. */
  function fillValidStep(name = 'reg-load') {
    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: name } });
    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'target.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '8080' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/api/item' } });
  }

  it('lists registered scenarios with their state badges', async () => {
    // The server emits live fields FLAT on each node; the lib parser synthesises `status`.
    stubRegistry([
      { name: 'alpha', state: 'LOADED' },
      { name: 'beta', state: 'RUNNING', requestsSent: 100, succeeded: 100, failed: 0, currentVus: 3 },
      { name: 'gamma', state: 'COMPLETED', requestsSent: 50, succeeded: 50, failed: 0 },
    ]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-registry-row-alpha')).toBeInTheDocument());
    expect(screen.getByTestId('load-registry-state-alpha')).toHaveTextContent('LOADED');
    expect(screen.getByTestId('load-registry-state-beta')).toHaveTextContent('RUNNING');
    expect(screen.getByTestId('load-registry-state-gamma')).toHaveTextContent('COMPLETED');
  });

  it('multi-selects scenarios and starts them with PUT /loadScenario/start {names}', async () => {
    const { calls } = stubRegistry([
      { name: 'alpha', state: 'LOADED' },
      { name: 'beta', state: 'LOADED' },
    ]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-registry-row-alpha')).toBeInTheDocument());

    fireEvent.click(screen.getByLabelText('Select alpha'));
    fireEvent.click(screen.getByLabelText('Select beta'));
    fireEvent.click(screen.getByTestId('load-start-selected'));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))).toBeTruthy());
    const start = calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))!;
    expect(start.body).toEqual({ names: ['alpha', 'beta'] });
  });

  it('starts a single selected scenario with {name} (not {names})', async () => {
    const { calls } = stubRegistry([{ name: 'solo', state: 'LOADED' }]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-registry-row-solo')).toBeInTheDocument());

    fireEvent.click(screen.getByLabelText('Select solo'));
    fireEvent.click(screen.getByTestId('load-start-selected'));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))).toBeTruthy());
    const start = calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))!;
    expect(start.body).toEqual({ name: 'solo' });
  });

  it('stops an individual running scenario with PUT /stop {names:[name]}', async () => {
    const { calls } = stubRegistry([
      { name: 'runner', state: 'RUNNING', requestsSent: 10, succeeded: 10, failed: 0, currentVus: 2 },
    ]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-running-runner')).toBeInTheDocument());

    const running = screen.getByTestId('load-running-runner');
    fireEvent.click(within(running).getByRole('button', { name: /Stop/i }));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT' && c.url.endsWith('/stop'))).toBeTruthy());
    const stop = calls.find((c) => c.method === 'PUT' && c.url.endsWith('/stop'))!;
    expect(stop.body).toEqual({ names: ['runner'] });
  });

  it('shows multiple running scenarios concurrently', async () => {
    // Live metrics arrive FLAT on each node (the real server shape) and flow through the
    // lib parser into the nested status the panel reads — proving the flat→nested mapping.
    stubRegistry([
      { name: 'r1', state: 'RUNNING', requestsSent: 100, succeeded: 100, failed: 0, currentVus: 3, p95Millis: 40 },
      { name: 'r2', state: 'RUNNING', requestsSent: 200, succeeded: 200, failed: 0, currentVus: 6, p95Millis: 60 },
    ]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-running-r1')).toBeInTheDocument());
    expect(screen.getByTestId('load-running-r2')).toBeInTheDocument();
    expect(screen.getByTestId('load-running-scenarios')).toHaveTextContent('Running Now (2)');
    // The flat live fields rendered through into the running card (Active VUs = 3 is unique to r1).
    const r1 = screen.getByTestId('load-running-r1');
    expect(within(r1).getByText('3')).toBeInTheDocument();
  });

  it('renders the live chart with a per-scenario toggle for each running scenario plus a total', async () => {
    stubRegistry([
      { name: 'r1', state: 'RUNNING', requestsSent: 100, succeeded: 100, failed: 0, currentVus: 3, p95Millis: 40 },
      { name: 'r2', state: 'RUNNING', requestsSent: 200, succeeded: 200, failed: 0, currentVus: 6, p95Millis: 60 },
    ]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-chart')).toBeInTheDocument());

    // Metric-type toggles are present (as before), defaulting to the RPS/p95/VUs subset.
    const metricGroup = screen.getByRole('group', { name: 'Chart metric toggles' });
    expect((within(metricGroup).getByRole('checkbox', { name: 'RPS' }) as HTMLInputElement).checked).toBe(true);

    // A scenario-toggle group appears with one checkbox per scenario, all enabled by default.
    const scenarioGroup = screen.getByRole('group', { name: 'Chart scenario toggles' });
    const r1Toggle = within(scenarioGroup).getByRole('checkbox', { name: 'r1' }) as HTMLInputElement;
    const r2Toggle = within(scenarioGroup).getByRole('checkbox', { name: 'r2' }) as HTMLInputElement;
    expect(r1Toggle.checked).toBe(true);
    expect(r2Toggle.checked).toBe(true);

    // With 2+ scenarios enabled the chart advertises the total + per-scenario split.
    expect(screen.getByTestId('load-chart')).toHaveTextContent('total + per scenario');

    // Disabling a scenario unchecks it (removing its line + recomputing the total).
    fireEvent.click(r1Toggle);
    expect((within(screen.getByRole('group', { name: 'Chart scenario toggles' }))
      .getByRole('checkbox', { name: 'r1' }) as HTMLInputElement).checked).toBe(false);
  });

  it('hides per-scenario toggles when only one scenario is running', async () => {
    stubRegistry([
      { name: 'solo', state: 'RUNNING', requestsSent: 50, succeeded: 50, failed: 0, currentVus: 2, p95Millis: 30 },
    ]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-chart')).toBeInTheDocument());
    // One scenario → no scenario toggles and no "total" framing (the single line speaks for itself).
    expect(screen.queryByRole('group', { name: 'Chart scenario toggles' })).not.toBeInTheDocument();
    expect(screen.getByTestId('load-chart')).not.toHaveTextContent('total + per scenario');
  });

  it('defaults to the Run & Monitor sub-tab and reveals the form only on Create / Edit', async () => {
    stubRegistry([]);
    render(<LoadScenarioPanel connectionParams={params} />);
    // Both sub-tabs exist; Run & Monitor is the default, so the create/edit form is hidden.
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Run & Monitor' })).toBeInTheDocument());
    expect(screen.getByRole('tab', { name: 'Create / Edit' })).toBeInTheDocument();
    expect(screen.queryByTestId('load-author-form')).not.toBeInTheDocument();
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
  });

  it('jumps to the Author tab when editing a registered scenario', async () => {
    stubRegistry([{
      name: 'editable', state: 'LOADED',
      definition: {
        name: 'editable', templateType: 'VELOCITY',
        profile: { stages: [{ type: 'VU', vus: 2, durationMillis: 10000 }] },
        steps: [{ request: { method: 'GET', path: '/x', socketAddress: { host: 'h', port: 80, scheme: 'HTTP' } } }],
      },
    }]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-registry-row-editable')).toBeInTheDocument());
    // On the default Run tab the form is hidden; clicking Edit switches to Author and loads it.
    expect(screen.queryByTestId('load-author-form')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Edit editable' }));
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    expect((screen.getByLabelText(/Scenario name/) as HTMLInputElement).value).toBe('editable');
  });

  it('jumps to the Run & Monitor tab when starting a scenario from the Author tab', async () => {
    stubRegistry([{ name: 'startme', state: 'LOADED' }]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-registry-row-startme')).toBeInTheDocument());
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    // Starting the scenario from its registry row switches back to Run & Monitor (form hidden).
    fireEvent.click(screen.getByRole('button', { name: 'Start startme' }));
    await waitFor(() => expect(screen.queryByTestId('load-author-form')).not.toBeInTheDocument());
  });

  it('renders a determinate run-progress bar (not the old indeterminate sweep)', async () => {
    stubRegistry([{
      name: 'r1', state: 'RUNNING', requestsSent: 10, succeeded: 10, failed: 0, currentVus: 2,
      p95Millis: 5, elapsedMillis: 5000, stageType: 'VU',
      definition: {
        name: 'r1', templateType: 'VELOCITY',
        profile: { stages: [{ type: 'VU', vus: 2, durationMillis: 10000 }] },
        steps: [{ request: { method: 'GET', path: '/', socketAddress: { host: 'h', port: 80, scheme: 'HTTP' } } }],
      },
    }]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-running-r1')).toBeInTheDocument());
    const bar = within(screen.getByTestId('load-running-r1')).getByRole('progressbar');
    // Determinate bars expose aria-valuenow (~50% at 5s of a 10s profile); indeterminate ones don't.
    expect(bar).toHaveAttribute('aria-valuenow', '50');
  });

  it('deletes one registered scenario via DELETE /loadScenario/{name}', async () => {
    const { calls } = stubRegistry([{ name: 'gone', state: 'LOADED' }]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-registry-row-gone')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Delete gone' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'DELETE' && c.url.endsWith('/loadScenario/gone'))).toBeTruthy());
  });

  it('clears all registered scenarios via DELETE /loadScenario', async () => {
    const { calls } = stubRegistry([{ name: 'a', state: 'LOADED' }, { name: 'b', state: 'LOADED' }]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-clear-all')).not.toBeDisabled());

    fireEvent.click(screen.getByTestId('load-clear-all'));
    await waitFor(() =>
      expect(calls.find((c) => c.method === 'DELETE' && c.url.endsWith('/mockserver/loadScenario'))).toBeTruthy(),
    );
  });

  it('"Load" registers without starting (no /start call)', async () => {
    const { calls } = stubRegistry([]);
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('register-only');

    fireEvent.click(screen.getByTestId('load-register'));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());

    const puts = calls.filter((c) => c.method === 'PUT');
    expect(puts.some((p) => p.url.endsWith('/mockserver/loadScenario'))).toBe(true);
    expect(puts.some((p) => p.url.endsWith('/start'))).toBe(false);
  });

  it('"Load & Run" registers then starts the authored scenario by name', async () => {
    const { calls } = stubRegistry([]);
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('run-now');

    fireEvent.click(screen.getByTestId('load-register-run'));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))).toBeTruthy());
    const register = calls.find((c) => c.method === 'PUT' && c.url.endsWith('/mockserver/loadScenario'))!;
    expect((register.body as { name: string }).name).toBe('run-now');
    const start = calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))!;
    expect(start.body).toEqual({ name: 'run-now' });
  });

  it('binds the Start delay field to startDelayMillis in the registered body', async () => {
    const { calls } = stubRegistry([]);
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('delayed');
    fireEvent.change(screen.getByLabelText(/Start delay/), { target: { value: '5000' } });

    fireEvent.click(screen.getByTestId('load-register'));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect((put.body as { startDelayMillis?: number }).startDelayMillis).toBe(5000);
  });

  // --- Generated code (inline below the Author form) ---

  it('renders generated register & start snippets inline below the form', async () => {
    stubRegistry([]);
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('code-scenario');

    // A valid scenario renders the code review inline (no separate Code tab any more).
    // The default tab (Java) uses the idiomatic client API, not a raw REST call.
    await waitFor(() => expect(screen.getByTestId('load-code-review')).toBeInTheDocument());
    const review = screen.getByTestId('load-code-review');
    expect(review).toHaveTextContent('client.loadScenario(scenario)');
    expect(review).toHaveTextContent('client.startLoadScenarios("code-scenario")');
    expect(review).toHaveTextContent('code-scenario');
  });

  it('still renders the code preview AND a non-blocking hint when the scenario is incomplete', async () => {
    stubRegistry([]);
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    // No name filled — buildScenario fails the submit gate, so the inline non-blocking hint shows...
    await waitFor(() => expect(screen.getByTestId('load-code-incomplete')).toBeInTheDocument());
    // ...but the code preview ALWAYS renders now, from the best-effort partial scenario.
    expect(screen.getByTestId('load-code-review')).toBeInTheDocument();
    expect(screen.getByTestId('load-code-review')).toHaveTextContent('client.loadScenario(scenario)');
  });

  // --- new load-injection fields (this session) ---

  it('emits thresholds + abortOnFail in the registered body', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('threshold-load');

    fireEvent.click(screen.getByTestId('load-add-threshold'));
    const t0 = screen.getByTestId('load-threshold-0');
    selectOption(within(t0).getByLabelText('Metric'), 'Latency p99 (ms)');
    selectOption(within(t0).getByLabelText('Comparator'), '<');
    fireEvent.change(within(t0).getByLabelText('Threshold'), { target: { value: '750' } });
    fireEvent.click(screen.getByTestId('load-abort-on-fail'));
    fireEvent.change(screen.getByLabelText(/Abort grace/), { target: { value: '5000' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.body).toMatchObject({
      thresholds: [{ metric: 'LATENCY_P99', comparator: 'LESS_THAN', threshold: 750 }],
      abortOnFail: true,
      abortGraceMillis: 5000,
    });
  });

  it('emits stepSelection WEIGHTED with per-step weights', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('weighted-load');

    selectOption(within(screen.getByTestId('load-step-selection')).getByRole('combobox'), /Weighted/);
    fireEvent.change(within(screen.getByTestId('load-step-weight-0')).getByRole('textbox'), { target: { value: '7' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.body).toMatchObject({ stepSelection: 'WEIGHTED', steps: [{ weight: 7 }] });
  });

  it('emits per-step captures', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('capture-load');

    fireEvent.click(screen.getByTestId('load-add-capture-0'));
    const cap = screen.getByTestId('load-capture-0-0');
    fireEvent.change(within(cap).getByLabelText('Variable name'), { target: { value: 'token' } });
    fireEvent.change(within(cap).getByLabelText('Expression'), { target: { value: '$.token' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.body).toMatchObject({
      steps: [{ captures: [{ name: 'token', source: 'BODY_JSONPATH', expression: '$.token' }] }],
    });
  });

  it('emits pacing when a pacing mode is selected', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('pacing-load');

    selectOption(within(screen.getByTestId('load-pacing-mode')).getByRole('combobox'), /Constant pacing/);
    fireEvent.change(screen.getByLabelText(/Cycle \(ms\)/), { target: { value: '1000' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.body).toMatchObject({ pacing: { mode: 'CONSTANT_PACING', value: 1000 } });
  });

  it('emits profile.shape when authoring with a named shape', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('shape-load');

    // Switch the profile to the named-shape mode; the default shape is RAMP_HOLD.
    fireEvent.click(screen.getByTestId('load-profile-mode-shape'));
    await waitFor(() => expect(screen.getByTestId('load-shape')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    const profile = (put.body as { profile: Record<string, unknown> }).profile;
    expect(profile).toHaveProperty('shape');
    expect(profile).not.toHaveProperty('stages');
    expect(profile.shape).toMatchObject({ type: 'RAMP_HOLD', metric: 'VU' });
  });

  it('emits an inline-rows feeder', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('feeder-load');

    fireEvent.click(screen.getByTestId('load-feeder-enabled'));
    await waitFor(() => expect(screen.getByTestId('load-feeder')).toBeInTheDocument());
    const row = screen.getByTestId('load-feeder-row-0');
    fireEvent.change(within(row).getByLabelText('Column'), { target: { value: 'userId' } });
    fireEvent.change(within(row).getByLabelText('Value'), { target: { value: '42' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.body).toMatchObject({ feeder: { rows: [{ userId: '42' }], strategy: 'CIRCULAR' } });
  });

  it('shows the threshold verdict chip for a completed run', async () => {
    stubRegistry([
      {
        name: 'verdict-run', state: 'COMPLETED', requestsSent: 100, succeeded: 95, failed: 5,
        p95Millis: 120, p999Millis: 480, droppedIterations: 3, verdict: 'FAIL', abortedByThreshold: false,
        thresholdResults: [
          { metric: 'LATENCY_P95', comparator: 'LESS_THAN', threshold: 100, observed: 120, satisfied: false },
        ],
      },
    ]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-registry-row-verdict-run')).toBeInTheDocument());
    expect(screen.getByTestId('load-registry-verdict-verdict-run')).toHaveTextContent('FAIL');
  });

  it('generates a scenario from an OpenAPI spec and loads it into the editor', async () => {
    const calls: Array<{ url: string; method: string; body?: unknown }> = [];
    const generated = {
      name: 'petstore-load',
      profile: { stages: [{ type: 'VU', vus: 2, durationMillis: 10000 }] },
      steps: [{ request: { method: 'GET', path: '/pets', socketAddress: { host: 'petstore.svc', port: 8080, scheme: 'HTTP' } } }],
    };
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      const body = init?.body ? JSON.parse(init.body as string) : undefined;
      calls.push({ url: String(url), method, body });
      if (String(url).includes('/generateFromOpenAPI')) {
        return { ok: true, status: 200, json: async () => ({ status: 'loaded', name: 'petstore-load', state: 'LOADED', scenario: generated }) } as unknown as Response;
      }
      if (method === 'GET') return { ok: true, status: 200, json: async () => ({ state: 'none', scenarios: [] }) } as unknown as Response;
      return { ok: true, status: 200, json: async () => ({ status: 'ok' }) } as unknown as Response;
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<LoadScenarioPanel connectionParams={params} />);
    gotoAuthorTab();
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());

    fireEvent.click(screen.getByTestId('load-generate-openapi-open'));
    await waitFor(() => expect(screen.getByTestId('load-generate-dialog')).toBeInTheDocument());
    fireEvent.change(within(screen.getByTestId('load-generate-name')).getByRole('textbox'), { target: { value: 'petstore-load' } });
    fireEvent.change(within(screen.getByTestId('load-generate-spec')).getByRole('textbox'), { target: { value: 'https://example.com/openapi.yaml' } });
    fireEvent.click(screen.getByTestId('load-generate-submit'));

    // The returned scenario is loaded into the form (name field populated).
    await waitFor(() => expect((screen.getByLabelText(/Scenario name/) as HTMLInputElement).value).toBe('petstore-load'));
    const gen = calls.find((c) => c.url.includes('/generateFromOpenAPI'))!;
    expect(gen.method).toBe('PUT');
    expect(gen.body).toMatchObject({ name: 'petstore-load', specUrlOrPayload: 'https://example.com/openapi.yaml' });
  });
});
