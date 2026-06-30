import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import CaptureAsMockDialog from '../components/CaptureAsMockDialog';
import { _clearMcpSessionCache } from '../lib/mcpClient';
import { useDashboardStore } from '../store';
import type { AnthropicParsed, OpenAiParsed, GenericParsed } from '../lib/llmTraffic';

function renderDialog(overrides: Partial<Parameters<typeof CaptureAsMockDialog>[0]> = {}) {
  const defaults = {
    open: true,
    onClose: vi.fn(),
    parsed: {
      kind: 'anthropic' as const,
      model: 'claude-sonnet-4-20250514',
      stream: false,
      messages: [],
      system: null,
      tools: null,
      maxTokens: null,
      responseContent: [{ type: 'text', text: 'Hello from Claude' }],
      usage: null,
      stopReason: 'end_turn',
      sseEvents: null,
      streamed: false,
      streamTruncated: false,
    } satisfies AnthropicParsed,
    path: '/v1/messages',
    connectionParams: { host: 'localhost', port: '1080', secure: false },
    ...overrides,
  };
  return { ...render(
    <ThemeProvider theme={buildTheme('dark')}>
      <CaptureAsMockDialog {...defaults} />
    </ThemeProvider>,
  ), ...defaults };
}

describe('CaptureAsMockDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    _clearMcpSessionCache();
  });

  it('renders dialog title', () => {
    renderDialog();
    expect(screen.getByText('Capture as Mock')).toBeInTheDocument();
  });

  it('shows editable fields on Edit tab', () => {
    renderDialog();

    // Path field
    expect(screen.getByLabelText('Path')).toHaveValue('/v1/messages');
    // Model field
    expect(screen.getByLabelText('Model')).toHaveValue('claude-sonnet-4-20250514');
    // Text field
    expect(screen.getByLabelText('Text')).toHaveValue('Hello from Claude');
    // Stop Reason field
    expect(screen.getByLabelText('Stop Reason')).toHaveValue('end_turn');
  });

  it('shows Register and Cancel buttons', () => {
    renderDialog();

    expect(screen.getByRole('button', { name: 'Register' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
  });

  it('calls onClose when Cancel is clicked', async () => {
    const user = userEvent.setup();
    const { onClose } = renderDialog();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('switches to Copy as JSON tab and shows JSON output', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Copy as JSON' }));

    // Should show the JSON representation
    expect(screen.getByText(/httpLlmResponse/)).toBeInTheDocument();
    expect(screen.getByText(/ANTHROPIC/)).toBeInTheDocument();
  });

  it('switches to Copy as Java tab and shows Java output', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Copy as Java' }));

    // Should show the Java representation
    expect(screen.getByText(/import static org.mockserver.client.Llm.\*/)).toBeInTheDocument();
    expect(screen.getByText(/withProvider\(Provider.ANTHROPIC\)/)).toBeInTheDocument();
  });

  it('shows a before→after preview diff of the mock that will be created', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Preview Diff' }));

    // The diff is rendered (mocked DiffEditor exposes original/modified panes).
    // It loads via a lazy/Suspense split point (JsonDiffViewerLazy keeps monaco
    // out of the main bundle), so it resolves asynchronously — await it.
    expect(await screen.findByTestId('json-diff-viewer')).toBeInTheDocument();
    // "before" is an empty object — capture creates a brand-new mock.
    expect(screen.getByTestId('monaco-diff-original')).toHaveValue('{}');
    // "after" is the generated expectation JSON that will be registered.
    const modified = screen.getByTestId('monaco-diff-modified') as HTMLTextAreaElement;
    expect(modified.value).toMatch(/httpLlmResponse/);
    expect(modified.value).toMatch(/ANTHROPIC/);
  });

  it('previewed JSON matches the JSON the Copy tab shows', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Preview Diff' }));
    // JsonDiffViewerLazy resolves asynchronously (Suspense), so await its pane.
    const previewModified = ((await screen.findByTestId('monaco-diff-modified')) as HTMLTextAreaElement).value;

    await user.click(screen.getByRole('tab', { name: 'Copy as JSON' }));
    const copyJson = screen.getByText(/httpLlmResponse/).textContent ?? '';

    // The diff's "after" pane is exactly what the Copy-as-JSON tab outputs, so the
    // preview is faithful to what gets PUT.
    expect(previewModified.trim()).toBe(copyJson.trim());
  });

  it('populates fields from OpenAI traffic', () => {
    const openAiParsed: OpenAiParsed = {
      kind: 'openai',
      model: 'gpt-4o',
      stream: true,
      messages: [],
      tools: null,
      choices: [
        {
          message: { role: 'assistant', content: 'OpenAI response' },
          finish_reason: 'stop',
        },
      ],
      usage: null,
      sseEvents: null,
      streamed: false,
      streamTruncated: false,
    };

    renderDialog({ parsed: openAiParsed, path: '/chat/completions' });

    expect(screen.getByLabelText('Path')).toHaveValue('/chat/completions');
    expect(screen.getByLabelText('Model')).toHaveValue('gpt-4o');
    expect(screen.getByLabelText('Text')).toHaveValue('OpenAI response');
    expect(screen.getByLabelText('Stop Reason')).toHaveValue('stop');
  });

  it('allows adding a tool call', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByLabelText('Add tool call'));

    // Should see Name and Arguments fields for the new tool call
    expect(screen.getByLabelText('Name')).toBeInTheDocument();
    expect(screen.getByLabelText('Arguments')).toBeInTheDocument();
  });

  it('does not render when open is false', () => {
    renderDialog({ open: false });
    expect(screen.queryByText('Capture as Mock')).not.toBeInTheDocument();
  });

  it('calls MCP endpoint on Register click', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();

    // Same mock response for initialize + notifications/initialized + tools/call.
    // The MCP client now performs a 3-call handshake; the test cares only about
    // the final tools/call envelope.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'test-session' },
      json: () => Promise.resolve({
        jsonrpc: '2.0',
        id: 1,
        result: {
          content: [{ type: 'text', text: '{"status":"created","count":1}' }],
        },
      }),
    }));

    renderDialog({ onClose });

    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(fetch).toHaveBeenCalledTimes(3);
    const toolCall = (fetch as ReturnType<typeof vi.fn>).mock.calls[2]!;
    expect(toolCall[0]).toBe('http://localhost:1080/mockserver/mcp');

    const body = JSON.parse(toolCall[1].body);
    expect(body.params.name).toBe('mock_llm_completion');
    expect(body.params.arguments.provider).toBe('ANTHROPIC');
  });

  // -------------------------------------------------------------------------
  // Generic HTTP capture mode
  // -------------------------------------------------------------------------

  it('renders generic HTTP fields for non-LLM traffic', () => {
    const genericParsed: GenericParsed = {
      kind: 'generic',
      method: 'GET',
      path: '/api/health',
      statusCode: 200,
    };

    renderDialog({
      parsed: genericParsed,
      path: '/api/health',
      itemValue: {
        httpRequest: { method: 'GET', path: '/api/health' },
        httpResponse: { statusCode: 200, body: '{"status":"ok"}' },
      },
    });

    // Should show Method and Path fields, not Provider/Model
    expect(screen.getByLabelText('Method')).toHaveValue('GET');
    expect(screen.getByLabelText('Path')).toHaveValue('/api/health');
    expect(screen.getByLabelText('Status Code')).toHaveValue(200);
    expect(screen.queryByLabelText('Provider')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Model')).not.toBeInTheDocument();
  });

  it('shows matcher precision toggle for generic traffic', () => {
    const genericParsed: GenericParsed = {
      kind: 'generic',
      method: 'POST',
      path: '/api/data',
      statusCode: 201,
    };

    renderDialog({
      parsed: genericParsed,
      path: '/api/data',
      itemValue: {
        httpRequest: { method: 'POST', path: '/api/data' },
        httpResponse: { statusCode: 201 },
      },
    });

    // Should show the matcher precision buttons
    expect(screen.getByText('Matcher Precision')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Exact precision' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Moderate precision' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Loose precision' })).toBeInTheDocument();
  });

  it('switches matcher precision and updates JSON output', async () => {
    const user = userEvent.setup();
    const genericParsed: GenericParsed = {
      kind: 'generic',
      method: 'POST',
      path: '/api/data',
      statusCode: 200,
    };

    renderDialog({
      parsed: genericParsed,
      path: '/api/data',
      itemValue: {
        httpRequest: {
          method: 'POST',
          path: '/api/data',
          body: { type: 'STRING', string: '{"key":"val"}' },
        },
        httpResponse: { statusCode: 200, body: '{"result":true}' },
      },
    });

    // Click Loose precision
    await user.click(screen.getByRole('button', { name: 'Loose precision' }));

    // Switch to JSON tab
    await user.click(screen.getByRole('tab', { name: 'Copy as JSON' }));

    // In loose mode, the httpRequest block should only contain method + path
    const pre = screen.getByText(/httpRequest/);
    const json = JSON.parse(pre.textContent!);
    expect(json.httpRequest).toEqual({ method: 'POST', path: '/api/data' });
    expect(json.httpRequest).not.toHaveProperty('body');
  });

  it('calls PUT /mockserver/expectation for generic traffic Register', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const genericParsed: GenericParsed = {
      kind: 'generic',
      method: 'GET',
      path: '/api/health',
      statusCode: 200,
    };

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      text: () => Promise.resolve(''),
    }));

    renderDialog({
      onClose,
      parsed: genericParsed,
      path: '/api/health',
      itemValue: {
        httpRequest: { method: 'GET', path: '/api/health' },
        httpResponse: { statusCode: 200, body: '{"status":"ok"}' },
      },
    });

    await user.click(screen.getByRole('button', { name: 'Register' }));

    // Generic path uses PUT /mockserver/expectation, not MCP
    expect(fetch).toHaveBeenCalledTimes(1);
    const call = (fetch as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(call[0]).toBe('http://localhost:1080/mockserver/expectation');
    expect(call[1].method).toBe('PUT');

    const body = JSON.parse(call[1].body);
    expect(body).toHaveProperty('httpRequest');
    expect(body).toHaveProperty('httpResponse');
    expect(body).not.toHaveProperty('httpLlmResponse');
  });

  // -------------------------------------------------------------------------
  // Dialog correctness: reset-on-reopen + addable body matcher + humanised error
  // -------------------------------------------------------------------------

  it('exposes the Request Body matcher even when no body was captured', () => {
    const genericParsed: GenericParsed = {
      kind: 'generic',
      method: 'GET',
      path: '/api/health',
      statusCode: 200,
    };

    renderDialog({
      parsed: genericParsed,
      path: '/api/health',
      itemValue: {
        // No request body in the capture.
        httpRequest: { method: 'GET', path: '/api/health' },
        httpResponse: { statusCode: 200, body: '{"status":"ok"}' },
      },
    });

    // The Request Body field must still be available so a body matcher can be ADDED.
    const bodyField = screen.getByLabelText('Request Body');
    expect(bodyField).toBeInTheDocument();
    expect(bodyField).toHaveValue('');
    expect(
      screen.getByText(/add one to match on the request body/),
    ).toBeInTheDocument();
  });

  it('hides the Request Body matcher only in loose precision', async () => {
    const user = userEvent.setup();
    const genericParsed: GenericParsed = {
      kind: 'generic',
      method: 'GET',
      path: '/api/health',
      statusCode: 200,
    };

    renderDialog({
      parsed: genericParsed,
      path: '/api/health',
      itemValue: {
        httpRequest: { method: 'GET', path: '/api/health' },
        httpResponse: { statusCode: 200 },
      },
    });

    expect(screen.getByLabelText('Request Body')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Loose precision' }));
    expect(screen.queryByLabelText('Request Body')).not.toBeInTheDocument();
  });

  it('clears a prior error and edits when the same item is reopened', async () => {
    const user = userEvent.setup();
    const genericParsed: GenericParsed = {
      kind: 'generic',
      method: 'GET',
      path: '/api/health',
      statusCode: 200,
    };

    // Register fails -> humanised error Alert appears.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      text: () => Promise.resolve('boom: invalid expectation'),
    }));

    const props = {
      open: true,
      onClose: vi.fn(),
      parsed: genericParsed,
      path: '/api/health',
      connectionParams: { host: 'localhost', port: '1080', secure: false },
      itemValue: {
        httpRequest: { method: 'GET', path: '/api/health' },
        httpResponse: { statusCode: 200 },
      },
    };

    const { rerender } = render(
      <ThemeProvider theme={buildTheme('dark')}>
        <CaptureAsMockDialog {...props} />
      </ThemeProvider>,
    );

    // Edit a field, then trigger the failing register.
    fireEvent.change(screen.getByLabelText('Response Body'), { target: { value: 'EDITED' } });
    await user.click(screen.getByRole('button', { name: 'Register' }));

    // Humanised message (400 -> "rejected as invalid") + Details expander with raw body.
    expect(await screen.findByText(/rejected as invalid/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByText(/boom: invalid expectation/)).toBeInTheDocument();

    // Cancel (close) the dialog with the SAME path.
    rerender(
      <ThemeProvider theme={buildTheme('dark')}>
        <CaptureAsMockDialog {...props} open={false} />
      </ThemeProvider>,
    );
    // Reopen the SAME captured item.
    rerender(
      <ThemeProvider theme={buildTheme('dark')}>
        <CaptureAsMockDialog {...props} open />
      </ThemeProvider>,
    );

    // The stale error must be gone and the edit reverted to the captured default.
    expect(screen.queryByText(/rejected as invalid/i)).not.toBeInTheDocument();
    expect(screen.getByLabelText('Response Body')).not.toHaveValue('EDITED');
  });

  it('generates Java code for generic traffic', async () => {
    const user = userEvent.setup();
    const genericParsed: GenericParsed = {
      kind: 'generic',
      method: 'GET',
      path: '/api/health',
      statusCode: 200,
    };

    renderDialog({
      parsed: genericParsed,
      path: '/api/health',
      itemValue: {
        httpRequest: { method: 'GET', path: '/api/health' },
        httpResponse: { statusCode: 200, body: '{"status":"ok"}' },
      },
    });

    await user.click(screen.getByRole('tab', { name: 'Copy as Java' }));

    // Should use HttpResponse imports, not LLM
    expect(screen.getByText(/import static org.mockserver.model.HttpResponse.response/)).toBeInTheDocument();
    expect(screen.getByText(/withStatusCode\(200\)/)).toBeInTheDocument();
    expect(screen.queryByText(/llmResponse/)).not.toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // Refine in Composer handoff (generic HTTP only)
  // -------------------------------------------------------------------------

  it('hands a generic capture off to the Composer via editExpectation', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const genericParsed: GenericParsed = {
      kind: 'generic',
      method: 'GET',
      path: '/api/health',
      statusCode: 200,
    };

    useDashboardStore.setState({ pendingEditExpectation: null, view: 'traffic' });

    renderDialog({
      onClose,
      parsed: genericParsed,
      path: '/api/health',
      itemValue: {
        httpRequest: { method: 'GET', path: '/api/health' },
        httpResponse: { statusCode: 200, body: '{"status":"ok"}' },
      },
    });

    await user.click(screen.getByRole('button', { name: /Refine in Composer/ }));

    const state = useDashboardStore.getState();
    expect(state.view).toBe('composer');
    expect(state.pendingEditExpectation).toHaveProperty('httpRequest');
    expect(state.pendingEditExpectation).toHaveProperty('httpResponse');
    expect(onClose).toHaveBeenCalled();
  });

  it('does not offer Refine in Composer for LLM traffic', () => {
    renderDialog();
    expect(screen.queryByRole('button', { name: /Refine in Composer/ })).not.toBeInTheDocument();
  });
});
