import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ScenarioPanel from '../components/ScenarioPanel';
import { useDashboardStore } from '../store';

// jsdom cannot run the real mermaid renderer (it needs layout/measurement), so
// mock the dynamic import — exactly as AgentRunGraph's tests do. We capture the
// source string passed to mermaid.render to assert the diagram is generated
// correctly from the scenario data even though the SVG render is mocked.
const mermaidRender = vi.fn();
const mermaidInitialize = vi.fn();
vi.mock('mermaid', () => ({
  default: {
    initialize: (...args: unknown[]) => mermaidInitialize(...args),
    render: (...args: unknown[]) => mermaidRender(...args),
  },
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ScenarioPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  useDashboardStore.setState({ activeExpectations: [] });
});

describe('ScenarioPanel — Trigger confirmation', () => {
  it('does not call the trigger endpoint until the confirmation is accepted', async () => {
    const user = userEvent.setup();
    let triggerCalled = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: { method?: string }) => {
        const urlStr = String(url);
        if (urlStr.includes('/trigger') && init?.method === 'PUT') {
          triggerCalled = true;
          return { ok: true, status: 200, json: async () => ({ scenarioName: 'checkout', currentState: 'paid' }) };
        }
        // GET /mockserver/scenario list
        return { ok: true, status: 200, json: async () => ({ scenarios: [] }) };
      }),
    );

    renderPanel();

    // Fill scenario name and trigger state so the Trigger button enables.
    const nameInput = screen.getByPlaceholderText('Scenario name');
    await user.type(nameInput, 'checkout');
    const triggerInput = screen.getByPlaceholderText('New state');
    await user.type(triggerInput, 'paid');

    const triggerBtn = screen.getByRole('button', { name: 'Trigger' });
    await user.click(triggerBtn);

    // A confirmation dialog appears; the endpoint has NOT been called yet.
    await waitFor(() => {
      expect(screen.getByText('Trigger scenario transition?')).toBeInTheDocument();
    });
    expect(triggerCalled).toBe(false);

    // Confirm — now the trigger endpoint is called.
    await user.click(screen.getByRole('button', { name: /Trigger transition/i }));

    await waitFor(() => {
      expect(triggerCalled).toBe(true);
    });
  });

  it('does not trigger when the confirmation is cancelled', async () => {
    const user = userEvent.setup();
    let triggerCalled = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: { method?: string }) => {
        const urlStr = String(url);
        if (urlStr.includes('/trigger') && init?.method === 'PUT') {
          triggerCalled = true;
          return { ok: true, status: 200, json: async () => ({ scenarioName: 'checkout', currentState: 'paid' }) };
        }
        return { ok: true, status: 200, json: async () => ({ scenarios: [] }) };
      }),
    );

    renderPanel();

    await user.type(screen.getByPlaceholderText('Scenario name'), 'checkout');
    await user.type(screen.getByPlaceholderText('New state'), 'paid');

    await user.click(screen.getByRole('button', { name: 'Trigger' }));

    await waitFor(() => {
      expect(screen.getByText('Trigger scenario transition?')).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /Cancel/i }));

    await waitFor(() => {
      expect(screen.queryByText('Trigger scenario transition?')).not.toBeInTheDocument();
    });
    expect(triggerCalled).toBe(false);
  });
});

describe('ScenarioPanel — state-machine diagram (UI3)', () => {
  beforeEach(() => {
    mermaidRender.mockReset();
    mermaidInitialize.mockReset();
    mermaidRender.mockResolvedValue({ svg: '<svg data-testid="scenario-mermaid-svg"><g/></svg>' });
  });

  it('renders a stateDiagram-v2 from a selected scenario chip, highlighting the current state', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        status: 200,
        json: async () => ({ scenarios: [{ scenarioName: 'checkout', currentState: 'paid' }] }),
      })),
    );

    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <ScenarioPanel connectionParams={params} />
      </ThemeProvider>,
    );

    // Click the existing-scenario chip to select it (records the current state).
    const chip = await screen.findByText('checkout: paid');
    await user.click(chip);

    // mermaid.render is called with a stateDiagram-v2 source built from the data.
    await waitFor(() => expect(mermaidRender).toHaveBeenCalled());
    const [, source] = mermaidRender.mock.calls[0] as [string, string];
    expect(source.startsWith('stateDiagram-v2')).toBe(true);
    expect(source).toContain('paid : paid');
    expect(source).toContain('[*] --> paid');
    expect(source).toContain('class paid current');
    expect(source).not.toContain('<br');

    // The mocked SVG is injected into the DOM.
    const container = await screen.findByTestId('scenario-state-graph-svg');
    expect(container.querySelector('svg')).not.toBeNull();
  });

  it('records the transition into the diagram when Set State changes the state', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init?: { method?: string }) => {
        if (init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ scenarioName: 'checkout', currentState: 'shipped' }) };
        }
        // initial list: scenario already at 'paid'
        return { ok: true, status: 200, json: async () => ({ scenarios: [{ scenarioName: 'checkout', currentState: 'paid' }] }) };
      }),
    );

    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <ScenarioPanel connectionParams={params} />
      </ThemeProvider>,
    );

    // Select the scenario (current state 'paid' observed).
    await user.click(await screen.findByText('checkout: paid'));
    await waitFor(() => expect(mermaidRender).toHaveBeenCalled());

    // Set a new state 'shipped' — a paid -> shipped transition is recorded.
    await user.type(screen.getByPlaceholderText('State'), 'shipped');
    await user.click(screen.getByRole('button', { name: 'Set' }));

    await waitFor(() => {
      const calls = mermaidRender.mock.calls;
      const lastSource = calls[calls.length - 1]?.[1] as string;
      expect(lastSource).toContain('paid --> shipped');
      expect(lastSource).toContain('class shipped current');
    });
  });
});

describe('ScenarioPanel — scenario details + Edit hand-off', () => {
  const checkoutExpectations = [
    {
      key: 'e-start',
      value: {
        id: 'e-start',
        scenarioName: 'checkout',
        scenarioState: 'Started',
        newScenarioState: 'PAID',
        httpRequest: { method: 'POST', path: '/pay' },
        httpResponse: { statusCode: 200 },
      },
    },
    {
      key: 'e-paid',
      value: {
        id: 'e-paid',
        scenarioName: 'checkout',
        scenarioState: 'PAID',
        newScenarioState: 'SHIPPED',
        httpRequest: { method: 'POST', path: '/ship' },
        httpResponse: { statusCode: 200 },
      },
    },
  ];

  function stubEmptyScenarioList() {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ scenarios: [] }) })),
    );
  }

  it('renders per-scenario states, method/path, and transition arrows from store fixtures', async () => {
    const user = userEvent.setup();
    stubEmptyScenarioList();
    useDashboardStore.setState({ activeExpectations: checkoutExpectations });

    renderPanel();

    // The scenario card appears; expand it to reveal its states.
    expect(await screen.findByText('Scenario Details')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Expand scenario' }));

    // States are grouped and shown in canonical order with their bound mocks.
    expect(screen.getByText('POST /pay')).toBeInTheDocument();
    expect(screen.getByText('POST /ship')).toBeInTheDocument();
    // Transition arrows: matched-state → transition-state.
    expect(screen.getByText('Started → PAID')).toBeInTheDocument();
    expect(screen.getByText('PAID → SHIPPED')).toBeInTheDocument();
  });

  it('per-row Edit dispatches editExpectation with the full JSON (bindings intact)', async () => {
    const user = userEvent.setup();
    stubEmptyScenarioList();
    const editSpy = vi.fn();
    useDashboardStore.setState({ activeExpectations: checkoutExpectations, editExpectation: editSpy });

    renderPanel();

    await user.click(await screen.findByRole('button', { name: 'Expand scenario' }));
    const rowEditButtons = screen.getAllByRole('button', { name: 'Edit expectation' });
    await user.click(rowEditButtons[0]!);

    expect(editSpy).toHaveBeenCalledTimes(1);
    const arg = editSpy.mock.calls[0]![0] as Record<string, unknown>;
    // The exact expectation JSON is handed off, preserving the scenario bindings
    // so the Composer edit-overlay can round-trip them.
    expect(arg['scenarioName']).toBe('checkout');
    expect(arg['scenarioState']).toBe('Started');
    expect(arg['newScenarioState']).toBe('PAID');
  });

  it('scenario-level Edit on a single-mock scenario edits it directly', async () => {
    const user = userEvent.setup();
    stubEmptyScenarioList();
    const editSpy = vi.fn();
    useDashboardStore.setState({
      activeExpectations: [checkoutExpectations[0]!],
      editExpectation: editSpy,
    });

    renderPanel();

    await screen.findByText('Scenario Details');
    // The scenario-level "Edit" button (single bound mock → edits it directly).
    await user.click(screen.getByRole('button', { name: 'Edit scenario' }));

    expect(editSpy).toHaveBeenCalledTimes(1);
    expect((editSpy.mock.calls[0]![0] as Record<string, unknown>)['scenarioName']).toBe('checkout');
  });
});
