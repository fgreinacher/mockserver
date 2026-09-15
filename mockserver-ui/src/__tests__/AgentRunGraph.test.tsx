import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import AgentRunGraph from '../components/AgentRunGraph';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn(),
}));

// jsdom cannot run the real mermaid renderer (it needs layout/measurement), so
// mock the dynamic import. `render` is controlled per-test to exercise both the
// success and failure paths.
const mermaidRender = vi.fn();
const mermaidInitialize = vi.fn();
vi.mock('mermaid', () => ({
  default: {
    initialize: (...args: unknown[]) => mermaidInitialize(...args),
    render: (...args: unknown[]) => mermaidRender(...args),
  },
}));

import { callMcpTool } from '../lib/mcpClient';

const params = { host: '127.0.0.1', port: '1080', secure: false };

const CALL_GRAPH = {
  nodes: [
    { id: 'm0', kind: 'USER', label: 'hello' },
    { id: 'm1', kind: 'ASSISTANT', label: 'calling a tool' },
    { id: 't0', kind: 'TOOL_CALL', label: 'get_weather' },
  ],
  edges: [
    { from: 'm0', to: 'm1', kind: 'NEXT' },
    { from: 'm1', to: 't0', kind: 'INVOKES' },
  ],
};

function renderGraph() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <AgentRunGraph connectionParams={params} provider="openai" path={null} />
    </ThemeProvider>,
  );
}

describe('AgentRunGraph', () => {
  beforeEach(() => {
    vi.mocked(callMcpTool).mockReset();
    mermaidRender.mockReset();
    mermaidInitialize.mockReset();
    vi.mocked(callMcpTool).mockResolvedValue({ ok: true, result: { callGraph: CALL_GRAPH } });
  });

  it('renders the call graph as an SVG via mermaid', async () => {
    mermaidRender.mockResolvedValue({ svg: '<svg data-testid="mermaid-svg"><g/></svg>' });
    const user = userEvent.setup();
    renderGraph();

    await user.click(screen.getByRole('button', { name: 'Show graph' }));

    // The component attempts to render the graph via mermaid.render(id, source).
    await waitFor(() => expect(mermaidRender).toHaveBeenCalled());
    const [, source] = mermaidRender.mock.calls[0] as [string, string];
    expect(source).toContain('flowchart TD');

    // The returned SVG is injected into the DOM.
    const container = await screen.findByTestId('agent-run-graph-svg');
    expect(container.querySelector('svg')).not.toBeNull();
  });

  it('initialises mermaid with the dark theme when the app is in dark mode', async () => {
    mermaidRender.mockResolvedValue({ svg: '<svg/>' });
    const user = userEvent.setup();
    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <AgentRunGraph connectionParams={params} provider="openai" path={null} />
      </ThemeProvider>,
    );
    await user.click(screen.getByRole('button', { name: 'Show graph' }));
    await waitFor(() => expect(mermaidInitialize).toHaveBeenCalled());
    const initArg = mermaidInitialize.mock.calls[0]?.[0] as {
      theme: string;
      startOnLoad: boolean;
      securityLevel: string;
    };
    // The default store theme is 'dark' in jsdom (no stored preference / no matchMedia).
    expect(initArg.startOnLoad).toBe(false);
    expect(initArg.theme).toBe('dark');
    // The rendered SVG is injected via dangerouslySetInnerHTML, so we pin
    // securityLevel: 'strict'. THIS assertion is the specific guard against someone
    // quietly relaxing that level — it is the only test that pins the value.
    // (mermaidRenderContract.test.ts separately proves the injected SVG is inert for
    // hostile content end-to-end, but does not by itself prove 'strict' is required.)
    expect(initArg.securityLevel).toBe('strict');
  });

  it('falls back to the Mermaid source when rendering fails', async () => {
    mermaidRender.mockRejectedValue(new Error('mermaid blew up'));
    const user = userEvent.setup();
    renderGraph();

    await user.click(screen.getByRole('button', { name: 'Show graph' }));

    await waitFor(() => expect(mermaidRender).toHaveBeenCalled());
    // Graceful fallback: the raw Mermaid source is shown instead of the SVG.
    await waitFor(() => expect(screen.getByText(/Could not render the diagram/i)).toBeInTheDocument());
    expect(screen.getByText(/flowchart TD/)).toBeInTheDocument();
    expect(screen.queryByTestId('agent-run-graph-svg')).toBeNull();
  });

  it('shows an error when the call graph cannot be loaded', async () => {
    vi.mocked(callMcpTool).mockResolvedValue({ ok: false, error: 'boom' });
    const user = userEvent.setup();
    renderGraph();
    await user.click(screen.getByRole('button', { name: 'Show graph' }));
    await waitFor(() => expect(screen.getByText('boom')).toBeInTheDocument());
    expect(mermaidRender).not.toHaveBeenCalled();
  });

  it('forwards the isolation scope to explain_agent_run when all three props are provided', async () => {
    mermaidRender.mockResolvedValue({ svg: '<svg/>' });
    const user = userEvent.setup();
    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <AgentRunGraph
          connectionParams={params}
          provider="anthropic"
          path="/v1/messages"
          isolationType="header"
          isolationKey="x-agent-id"
          isolationValue="agent-002"
        />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole('button', { name: 'Show graph' }));
    await waitFor(() => expect(callMcpTool).toHaveBeenCalled());

    const [, tool, args] = vi.mocked(callMcpTool).mock.calls[0] as [string, string, Record<string, unknown>];
    expect(tool).toBe('explain_agent_run');
    expect(args).toMatchObject({
      provider: 'anthropic',
      path: '/v1/messages',
      isolationType: 'header',
      isolationKey: 'x-agent-id',
      isolationValue: 'agent-002',
    });
  });

  it('omits the isolation scope when the props are not provided', async () => {
    mermaidRender.mockResolvedValue({ svg: '<svg/>' });
    const user = userEvent.setup();
    renderGraph();

    await user.click(screen.getByRole('button', { name: 'Show graph' }));
    await waitFor(() => expect(callMcpTool).toHaveBeenCalled());

    const [, , args] = vi.mocked(callMcpTool).mock.calls[0] as [string, string, Record<string, unknown>];
    expect(args).not.toHaveProperty('isolationType');
    expect(args).not.toHaveProperty('isolationKey');
    expect(args).not.toHaveProperty('isolationValue');
  });

  it('omits the isolation scope when the scope is only partially provided', async () => {
    mermaidRender.mockResolvedValue({ svg: '<svg/>' });
    const user = userEvent.setup();
    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <AgentRunGraph
          connectionParams={params}
          provider="anthropic"
          path="/v1/messages"
          isolationType="header"
          isolationKey="x-agent-id"
          // isolationValue intentionally omitted — a partial scope must not be forwarded.
        />
      </ThemeProvider>,
    );

    await user.click(screen.getByRole('button', { name: 'Show graph' }));
    await waitFor(() => expect(callMcpTool).toHaveBeenCalled());

    const [, , args] = vi.mocked(callMcpTool).mock.calls[0] as [string, string, Record<string, unknown>];
    expect(args).not.toHaveProperty('isolationType');
    expect(args).not.toHaveProperty('isolationKey');
    expect(args).not.toHaveProperty('isolationValue');
  });

  it('lets the user toggle the Mermaid source alongside the rendered graph', async () => {
    mermaidRender.mockResolvedValue({ svg: '<svg/>' });
    const user = userEvent.setup();
    renderGraph();

    await user.click(screen.getByRole('button', { name: 'Show graph' }));
    await screen.findByTestId('agent-run-graph-svg');

    // Source is hidden by default (graph is the primary view).
    expect(screen.queryByText(/flowchart TD/)).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Show Mermaid source' }));
    await waitFor(() => expect(screen.getByText(/flowchart TD/)).toBeInTheDocument());
  });
});
