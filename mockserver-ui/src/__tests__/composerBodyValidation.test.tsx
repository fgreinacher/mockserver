/**
 * Tests that the Composer blocks the Register button when the request body
 * matcher holds malformed JSON (for the JSON / JSON Schema body types), so an
 * invalid body cannot be submitted. The Monaco editor is replaced by the global
 * test-setup stand-in (a textarea with data-testid="monaco-textarea"), so we
 * drive the body value through that textarea.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderComposer() {
  try {
    globalThis.sessionStorage?.setItem('mockserver-composer-mode', 'advanced');
  } catch {
    /* noop */
  }
  return render(
    <ThemeProvider theme={buildTheme('light')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

describe('Composer body matcher JSON validation gate', () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [] });
  });

  it('disables Register when the JSON body matcher is malformed, and re-enables it once fixed', async () => {
    const user = userEvent.setup();
    renderComposer();

    // A path is required before Register can ever be enabled.
    await user.type(screen.getByLabelText('Path'), '/orders');

    // Switch the body type to JSON.
    const bodyType = screen.getByLabelText('Body type');
    await user.click(bodyType);
    await user.click(await screen.findByRole('option', { name: 'JSON' }));

    // Scope to the request-body matcher editor (its aria-label tracks the body
    // type) — the static response action also renders a Monaco editor. Queried
    // with findBy: the editor is behind a lazy import, so how many ticks it
    // takes to appear depends on the module graph, and a synchronous getBy here
    // fails as soon as anything widens ComposerView's imports.
    const bodyEditor = within(await screen.findByLabelText('JSON body matcher')).getByTestId('monaco-textarea');
    // Unclosed array — invalid JSON. `[[` escapes userEvent's `[` special key
    // so the literal text typed is `[1,2`.
    await user.type(bodyEditor, '[[1,2');

    const registerButton = screen.getByRole('button', { name: /Register expectation/ });
    expect(registerButton).toBeDisabled();

    // Replace with well-formed JSON — the gate should clear.
    await user.clear(bodyEditor);
    await user.type(bodyEditor, '[[1,2]');
    expect(screen.getByRole('button', { name: /Register expectation/ })).toBeEnabled();
  });

  it('disables Register when the JSON Schema body matcher is malformed', async () => {
    const user = userEvent.setup();
    renderComposer();

    await user.type(screen.getByLabelText('Path'), '/orders');

    const bodyType = screen.getByLabelText('Body type');
    await user.click(bodyType);
    await user.click(await screen.findByRole('option', { name: 'JSON Schema' }));

    const bodyEditor = within(screen.getByLabelText('JSON Schema')).getByTestId('monaco-textarea');
    // Unclosed array — invalid JSON (`[[` escapes userEvent's `[` special key).
    await user.type(bodyEditor, '[[1,2');

    expect(screen.getByRole('button', { name: /Register expectation/ })).toBeDisabled();
  });

  it('allows a non-JSON body type (regex) through without JSON parsing', async () => {
    const user = userEvent.setup();
    renderComposer();

    await user.type(screen.getByLabelText('Path'), '/orders');

    const bodyType = screen.getByLabelText('Body type');
    await user.click(bodyType);
    await user.click(await screen.findByRole('option', { name: 'Regex' }));

    const bodyEditor = within(screen.getByLabelText('Regex pattern')).getByTestId('monaco-textarea');
    // Not valid JSON, but valid for a regex body matcher — must not block.
    await user.type(bodyEditor, '^Hello.*$');

    expect(screen.getByRole('button', { name: /Register expectation/ })).toBeEnabled();
  });
});
